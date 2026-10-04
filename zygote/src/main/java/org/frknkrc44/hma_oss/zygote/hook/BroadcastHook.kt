package org.frknkrc44.hma_oss.zygote.hook

import android.content.ComponentName
import android.content.IIntentReceiver
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.v7878.unsafe.invoke.EmulatedStackFrame
import icu.nullptr.hidemyapplist.common.CollectionUtils.firstOrNullWithType
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.common.Utils.getUserFromCallingUid
import org.frknkrc44.hma_oss.zygote.service.ReturnValue
import org.frknkrc44.hma_oss.zygote.util.Logcat.logD
import org.frknkrc44.hma_oss.zygote.util.Logcat.logI
import org.frknkrc44.hma_oss.zygote.util.ServiceUtils.getCallingApps
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.args
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.getArgument
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.getBooleanField
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.getIntField
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.getObjectField
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.ACTION_USB_STATE
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.ACTIVITY_MANAGER_SERVICE_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.BROADCAST_CONTROLLER_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.BROADCAST_PROCESS_QUEUE_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.BROADCAST_QUEUE_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.BROADCAST_QUEUE_IMPL_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.RESOLVE_INTENT_HELPER_CLASS
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.USB_FUNCTION_ADB

class BroadcastHook : IFrameworkHook {
    override val TAG = "BroadcastHook"

    companion object {
        // A class name that can never resolve: '#' is not a legal Java
        // class name character, so the component resolver lookup always
        // misses, the very same map-miss the funnel performs for a
        // component that belongs to an app that is not installed.
        private const val UNRESOLVABLE_RECEIVER_CLASS = "#hma_oss_disabled_receiver"

        // A package name that can never be installed: '#' is not a legal
        // Android package name character, so the package lookup always
        // misses, the very same map-miss the funnel performs for an app
        // that is not installed.
        private const val UNRESOLVABLE_PACKAGE = "#hma_oss_disabled"
    }

    // Cached per-target unresolvable receiver components: one allocation
    // per target app for the process lifetime instead of one per funnel
    // call, keeping the rewritten hot path allocation-free.
    private val unresolvableReceiverComponents =
        java.util.concurrent.ConcurrentHashMap<String, ComponentName>()

    // Intent fields rewritten by the funnel before hook and restored by
    // the after hook: the original component and the original package.
    // The funnel runs inside one binder call on the caller's binder
    // thread, so a per-thread LIFO stack is sufficient: nested calls of
    // the same thread unwind in order.
    private val savedTargets = object : ThreadLocal<ArrayList<Triple<Intent, ComponentName?, String?>>>() {
        override fun initialValue(): ArrayList<Triple<Intent, ComponentName?, String?>> = ArrayList()
    }

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                hookBefore(
                    BROADCAST_PROCESS_QUEUE_CLASS,
                    "enqueueOutgoingBroadcast",
                    hook = ::enqueueBroadcastLocked,
                )

                hookBefore(
                    BROADCAST_PROCESS_QUEUE_CLASS,
                    "enqueueOrReplaceBroadcast",
                    hook = ::enqueueBroadcastLocked,
                )
            } else {
                val targetClass = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    BROADCAST_QUEUE_CLASS
                } else {
                    BROADCAST_QUEUE_IMPL_CLASS
                }

                hookBefore(
                    targetClass,
                    "enqueueParallelBroadcastLocked",
                    hook = ::enqueueBroadcastLocked,
                )

                hookBefore(
                    targetClass,
                    "enqueueOrderedBroadcastLocked",
                    hook = ::enqueueBroadcastLocked,
                )
            }

            // Receiver resolution funnel. The enqueue hooks above make the
            // caller-visible outcome of a broadcast to a hidden app
            // identical to a not-installed one, but the resolution itself
            // still resolves the hidden app's receiver (receiver info +
            // ResolveInfo + applicationInfo construction) before the list is
            // cleared, which leaves the send call measurably slower than
            // for a not-installed target. A paired statistical timing probe
            // (candidate vs. known-missing control, many samples) can
            // extract that bias. Instead of paying for resolution and
            // undoing it, rewrite the intent to a name that can never
            // resolve — the component of an explicit intent, or the package
            // of a package-directed one, never adding a component where the
            // not-installed native path would not have one — so the funnel
            // runs its native "not installed" map-miss instructions,
            // bit-for-bit the same work as for a genuinely absent app. The
            // after hook restores the original fields before control returns
            // to broadcastIntentLocked (or the binder query caller), so the
            // record, the finish callback and every other observer see the
            // original intent untouched.
            setOf(RESOLVE_INTENT_HELPER_CLASS, COMPUTER_ENGINE_CLASS).forEach { clazz ->
                // The 7-argument overload is the shared funnel: the
                // broadcast path (forSend = true, filterCallingUid = the
                // real caller) and the direct query path (forSend = false)
                // both end there, the 6-argument one only forwards to it.
                // Register both so a ROM that only has one of the two is
                // still covered; the Boolean guard inside the before logic
                // makes the forwarding overload inert, and a registration
                // whose (class, method, arity) does not resolve is skipped
                // silently by the hooker. The before and after halves MUST
                // be registered as one hookWrap, never as separate
                // hookBefore and hookAfter calls: the hooker overwrites the
                // target entry point on every registration, so a second
                // registration for the same (class, method, arity) replaces
                // the first one entirely instead of chaining — separate
                // before/after registrations silently drop the before half
                // and the rewrite never runs.
                intArrayOf(7, 6).forEach { argumentCount ->
                    hookWrap(
                        clazz,
                        "queryIntentReceiversInternal",
                        argumentCount,
                        before = ::resolveReceiverQueryBefore,
                        after = ::resolveReceiverQueryAfter,
                    )
                }

                logI(TAG) {
                    "receiver query funnel " +
                        (if (isHookAvailable(clazz, "queryIntentReceiversInternal")) "hooked" else "unavailable") +
                        ": $clazz"
                }
            }

            // replace USB state receiver
            hookBefore(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    BROADCAST_CONTROLLER_CLASS
                } else {
                    ACTIVITY_MANAGER_SERVICE_CLASS
                },
                "broadcastIntentLocked",
            ) { _, frame, _ ->
                val intent = frame.args.firstOrNullWithType<Intent>() ?: return@hookBefore
                changeUsbStateBroadcast(intent)
            }
        }
    }

    private fun enqueueBroadcastLocked(
        methodName: String,
        frame: EmulatedStackFrame,
        returnValue: ReturnValue,
    ) {
        val record = frame.getArgument(1)
        val caller = getObjectField(record, "callerPackage") as? String ?: return
        val component = getObjectField(record, "targetComp") as? ComponentName ?: return
        val targetApp = component.packageName
        val userId = getIntField(record, "userId")

        if (service.shouldHideActivityLaunch(caller, targetApp, userId)) {
            logD(TAG) { "@$methodName: insecure query from $caller, target: $component" }

            // A broadcast aimed at an app that is not installed still gets a
            // BroadcastRecord (with zero receivers) enqueued, and its result
            // receiver is only called back asynchronously from the broadcast
            // queue after the sendOrderedBroadcast() binder call has already
            // returned. Replying to the result receiver synchronously inside
            // this hook is therefore trivially distinguishable from a real
            // "not installed" broadcast by pure call timing, which leaks the
            // presence of the hidden app to any caller that measures when the
            // callback arrives. Instead of faking the callback here, clear the
            // resolved receiver list and let the record flow through the
            // queue's normal zero-receiver completion path: the caller-visible
            // timing and result values are then identical to a broadcast sent
            // to an app that is not installed at all.
            val receivers = runCatching {
                @Suppress("UNCHECKED_CAST")
                getObjectField(record, "receivers") as? MutableList<Any>
            }.getOrNull()

            when {
                // A null receiver list completes on its own, nothing to do.
                receivers == null -> {}
                // The resolution was already rewritten to the not-found path
                // by the funnel hook (or the record was born empty for
                // another reason): the queue's natural zero-receiver
                // completion is exactly the not-installed trajectory, and
                // the funnel hook already counted it.
                receivers.isEmpty() -> {}
                else -> {
                    val receiversCleared = runCatching { receivers.clear() }.isSuccess

                    if (receiversCleared) {
                        service.increaseALFilterCount(caller)
                    } else {
                        // Fallback for a receiver list we cannot mutate in
                        // place: keep the old synchronous fake.
                        returnValue.result = null

                        val resultTo = getObjectField(record, "resultTo") as? IIntentReceiver
                        val haveATarget = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            true // getObjectField(record, "callerApp") != null
                        } else {
                            getObjectField(record, "resultToApp") != null
                        }

                        if (resultTo != null && haveATarget) {
                            resultTo.performReceive(
                                getObjectField(record, "intent") as Intent,
                                getIntField(record, "resultCode"),
                                getObjectField(record, "resultData") as? String,
                                getObjectField(record, "resultExtras") as? Bundle,
                                getBooleanField(record, "ordered"),
                                getBooleanField(record, "sticky"),
                                userId,
                            )
                        }

                        service.increaseALFilterCount(caller)
                    }
                }
            }
        }
    }

    private fun resolveReceiverQueryBefore(
        methodName: String,
        frame: EmulatedStackFrame,
        @Suppress("UNUSED_PARAMETER") returnValue: ReturnValue,
    ) {
        val intent = frame.args.firstOrNullWithType<Intent>() ?: return

        // Only the funnel overload carrying the forSend flag is actionable;
        // the forwarding overload without it is inert here.
        val forSend = frame.args.firstOrNullWithType<Boolean>() ?: return

        val component = intent.component
        val targetApp = component?.packageName ?: intent.`package` ?: return

        // The filterCallingUid is the last Int argument of the funnel; if a
        // ROM orders them differently the lookup simply finds the wrong uid,
        // no in-scope caller resolves and the hook degrades to a no-op (the
        // enqueue hooks above still hold the line).
        val callingUid = frame.args.filterIsInstance<Int>().lastOrNull() ?: return
        if (callingUid == Constants.UID_SYSTEM) return

        val callingUserId = getUserFromCallingUid(callingUid)
        val caller = getCallingApps(pms, callingUid).firstOrNull { service.isHookEnabled(it) } ?: return

        if (!service.shouldHideActivityLaunch(caller, targetApp, callingUserId)) return

        // Rewrite the intent so the funnel performs its native
        // not-installed miss for the whole resolution, while keeping the
        // native instruction path identical to a genuinely absent app:
        // - an explicit-component intent is rewritten in place: both the
        //   hidden probe and an absent-app probe take the same component
        //   branch of the funnel and pay the same resolver map miss;
        // - a package-directed intent must NOT gain a component: that
        //   would steer the funnel into the explicit-component branch,
        //   which a probe of an absent package never takes (it stays on
        //   the package branch, a plain package map miss). Rewrite the
        //   package instead: the funnel then performs the same
        //   package-branch miss it performs for an app that is not
        //   installed, instruction for instruction.
        // The after hook restores the original fields before anyone else
        // can observe the intent.
        val originalPackage = intent.`package`
        if (component != null) {
            intent.component =
                unresolvableReceiverComponents.computeIfAbsent(targetApp) {
                    ComponentName(it, UNRESOLVABLE_RECEIVER_CLASS)
                }
        } else {
            intent.`package` = UNRESOLVABLE_PACKAGE
        }
        savedTargets.get().add(Triple(intent, component, originalPackage))

        logD(TAG) { "@$methodName: resolution rewritten for $caller, target: $component" }

        // This is the single count for the rewritten path: the enqueue hook
        // only counts when it had receivers to clear. The increment is
        // deferred to a ring folded by a background thread: even the
        // optimized inline increment (lock + map update) is measurable
        // inside the resolution funnel of the caller's own binder call.
        if (forSend) {
            service.countALDeferred(caller)
        } else {
            service.countPMDeferred(caller)
        }
    }

    private fun resolveReceiverQueryAfter(
        @Suppress("UNUSED_PARAMETER") methodName: String,
        @Suppress("UNUSED_PARAMETER") frame: EmulatedStackFrame,
        @Suppress("UNUSED_PARAMETER") returnValue: ReturnValue,
    ) {
        val stack = savedTargets.get()
        if (stack.isEmpty()) return

        val saved = stack.removeAt(stack.size - 1)

        // Restore before broadcastIntentLocked (or the binder query caller)
        // gets control back, so the BroadcastRecord and the finish callback
        // see the original component and package.
        saved.first.component = saved.second
        saved.first.`package` = saved.third
    }

    private fun changeUsbStateBroadcast(intent: Intent) {
        if (config.disableActivityLaunchProtection) return

        if (intent.action == ACTION_USB_STATE) {
            intent.removeExtra(USB_FUNCTION_ADB)
        }
    }
}
