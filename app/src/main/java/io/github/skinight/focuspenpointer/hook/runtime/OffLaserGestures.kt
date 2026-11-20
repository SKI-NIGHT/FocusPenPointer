package io.github.skinight.focuspenpointer.hook.runtime

import android.os.Handler
import android.view.KeyEvent
import io.github.skinight.focuspenpointer.config.ConfigContract
import io.github.skinight.focuspenpointer.config.ModuleConfig
import io.github.skinight.focuspenpointer.gesture.HierarchicalTapHoldDetector
import io.github.skinight.focuspenpointer.hook.CompatProfile
import io.github.skinight.focuspenpointer.hook.GestureAction
import io.github.skinight.focuspenpointer.shell.Xp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pen gestures while the laser is off: two pinches (and hold), the global four pinches (and
 * hold), and pen-body swipes.
 *
 * Xiaomi reacts to the first pinch immediately, while multi-pinch gestures can only be
 * recognised after the tap window. When any of them is configured, the pinch stream is kept
 * from Xiaomi; every pinch of the sequence is buffered and, once the window closes without
 * one of our gestures, replayed to Xiaomi with its original spacing. Xiaomi's own single and
 * double pinches (e.g. in Notes) therefore keep working, slightly delayed.
 */
internal class OffLaserGestures(
    private val worker: Handler,
    private val config: () -> ModuleConfig,
    private val foreground: () -> String?,
    private val laserEnabled: () -> Boolean,
    private val executor: ActionExecutor,
    private val touchFilmManager: AtomicReference<Any?>,
    private val report: Reporter,
    private val onError: (Throwable) -> Unit,
) {
    interface Reporter {
        fun event(message: String)
        fun status(message: String)
    }

    private val replaying = AtomicBoolean(false)
    private val holdAwaitingRelease = AtomicBoolean(false)
    /** Down times of the clean pinch copies we injected for the app. */
    private val cleanTapDownTimes = ArrayDeque<Long>()

    private val replayLock = Any()
    private val buffered = ArrayList<BufferedKey>()
    private var replayInteractive = false
    private var replayTask: Runnable? = null
    private var replayGeneration = 0

    private val detector = HierarchicalTapHoldDetector(
        maxTaps = 4,
        tapMilestones = setOf(2, 4),
        holdMilestones = setOf(2, 4),
        scheduler = HierarchicalTapHoldDetector.Scheduler { delay, block ->
            val runnable = Runnable(block)
            worker.postDelayed(runnable, delay)
            HierarchicalTapHoldDetector.Cancellable { worker.removeCallbacks(runnable) }
        },
        intervalMs = { config().multiClickMs },
        holdMs = { FOUR_TAP_HOLD_MS },
        onGesture = ::onTapGesture,
        onIncomplete = {},
    )

    fun reset() {
        detector.reset()
        holdAwaitingRelease.set(false)
        discardBufferedPinch()
    }

    /**
     * Called from Xiaomi's touch-film key hook. Returns true when the vendor must not see
     * this pinch event (it is buffered for replay instead).
     */
    fun consumeVendorPinch(event: KeyEvent, interactive: Boolean, laserEnabled: Boolean): Boolean {
        if (replaying.get() || laserEnabled || event.scanCode != CompatProfile.SCAN_PINCH_F19) {
            return false
        }
        val snapshot = config()
        if (snapshot.blocksAllHooks(foreground())) return false
        val consume = snapshot.enabled &&
            (globalGesturesEnabled(snapshot) || normalGesturesEnabled(snapshot)) &&
            vendorShowsShortcutPanel()
        if (consume) bufferForReplay(event, interactive)
        return consume
    }

    /**
     * When Xiaomi passes pinches on to the app (any function but the shortcut panel), a held
     * pinch would reach it as a stream of key repeats and e.g. flip brush and eraser over and
     * over. Instead the real key is swallowed and the app gets one clean press-and-release
     * copy the moment the pinch goes down. Returns true when the real event is swallowed.
     */
    private fun passToAppAsCleanTap(event: KeyEvent, snapshot: ModuleConfig): Boolean {
        if (!snapshot.enabled || snapshot.blocksAllHooks(foreground()) || vendorShowsShortcutPanel()) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = android.os.SystemClock.uptimeMillis()
            val down = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, event.keyCode, 0, event.metaState,
                event.deviceId, event.scanCode, event.flags, event.source,
            )
            val up = KeyEvent.changeAction(KeyEvent.changeTimeRepeat(down, now + 1, 0), KeyEvent.ACTION_UP)
            synchronized(cleanTapDownTimes) {
                cleanTapDownTimes.addLast(now)
                while (cleanTapDownTimes.size > 4) cleanTapDownTimes.removeFirst()
            }
            // Inject outside this key callback.
            worker.post {
                if (!executor.injectEvent(down) || !executor.injectEvent(up)) {
                    report.event("轻捏转交应用失败")
                }
            }
        }
        return true
    }

    private fun isCleanTapCopy(event: KeyEvent): Boolean =
        synchronized(cleanTapDownTimes) { event.downTime in cleanTapDownTimes }

    /**
     * Only the "shortcut panel" pinch function is handled by Xiaomi itself (it pops the
     * panel up on the first pinch, so we hold pinches back). With any other function (e.g.
     * switching brush and eraser) Xiaomi passes the key on to the app, which must get it
     * untouched: a replay through the vendor hook would never reach the app.
     */
    private fun vendorShowsShortcutPanel(): Boolean {
        val manager = touchFilmManager.get() ?: return true
        return runCatching {
            Xp.getIntField(manager, "mCurrentPinchFunction") == PINCH_FUNCTION_SHORTCUT_PANEL &&
                !Xp.getBooleanField(manager, "mEnableStylusCaseForApp")
        }.getOrDefault(true)
    }

    /**
     * Handles pen keys while the laser is off (or the tail of a four-pinch hold that turned
     * it on). Returns null when the event is not an off-laser concern.
     */
    fun intercept(event: KeyEvent, laserEnabled: Boolean): Boolean? {
        val snapshot = config()
        if (event.scanCode == CompatProfile.SCAN_PINCH_F19 && holdAwaitingRelease.get()) {
            if (event.action == KeyEvent.ACTION_UP) holdAwaitingRelease.set(false)
            // The fourth DOWN was consumed while the laser was off. Its repeats and final UP
            // must not reach Xiaomi (or the mouse) after the hold changed the laser state.
            return true
        }
        if (laserEnabled) return null

        if (event.scanCode == CompatProfile.SCAN_PINCH_F19) {
            if (isCleanTapCopy(event)) return false
            if (!snapshot.enabled ||
                (!globalGesturesEnabled(snapshot) && !normalGesturesEnabled(snapshot))
            ) {
                detector.reset()
                return passToAppAsCleanTap(event, snapshot)
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    if (event.repeatCount == 0) detector.onDown(event.eventTime)
                KeyEvent.ACTION_UP -> {
                    val heldMs = (event.eventTime - event.downTime).coerceAtLeast(0L)
                    detector.onUp(event.eventTime, heldMs)
                }
            }
            // The shortcut-panel case is handled inside Xiaomi's hook (consumeVendorPinch).
            return passToAppAsCleanTap(event, snapshot)
        }

        val up = event.keyCode == CompatProfile.KEY_SLIDE_UP
        if (!up && event.keyCode != CompatProfile.KEY_SLIDE_DOWN) return null
        if (!snapshot.appliesTo(foreground())) return false
        val gestureId = if (up) ConfigContract.GESTURE_OFF_SWIPE_UP else ConfigContract.GESTURE_OFF_SWIPE_DOWN
        val action = snapshot.gestureActions[gestureId] ?: ConfigContract.ACTION_SYSTEM
        if (action == ConfigContract.ACTION_SYSTEM) return false
        if (GestureAction.requiresKeyInjection(action) && !executor.canRun(action, true)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val success = executor.run(action)
            report.event(
                "普通模式${if (up) "上滑" else "下滑"} → ${GestureAction.label(action)} " +
                    if (success) "成功" else "失败",
            )
            if (!success) return false
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    private fun onTapGesture(tapCount: Int, hold: Boolean) {
        val snapshot = config()
        when (tapCount) {
            4 -> {
                if (!snapshot.enabled ||
                    !snapshot.globalActionsEnabled ||
                    snapshot.blocksAllHooks(foreground())
                ) return
                discardBufferedPinch()
                if (hold) holdAwaitingRelease.set(true)
                worker.post { runGlobalAction(hold) }
            }
            2 -> {
                val gestureId = if (hold) {
                    ConfigContract.GESTURE_OFF_DOUBLE_HOLD
                } else {
                    ConfigContract.GESTURE_OFF_DOUBLE_TAP
                }
                val action = snapshot.gestureActions[gestureId] ?: ConfigContract.ACTION_SYSTEM
                if (!snapshot.appliesTo(foreground()) ||
                    action == ConfigContract.ACTION_SYSTEM ||
                    !executor.canRun(action, preferSystemCursor = true)
                ) return
                discardBufferedPinch()
                if (hold) holdAwaitingRelease.set(true)
                worker.post {
                    val success = executor.run(action)
                    report.event(
                        "普通模式${if (hold) "双击长按" else "双击"} → " +
                            "${GestureAction.label(action)} ${if (success) "成功" else "失败"}",
                    )
                }
            }
        }
    }

    private fun runGlobalAction(hold: Boolean) {
        val snapshot = config()
        if (!snapshot.enabled ||
            !snapshot.globalActionsEnabled ||
            snapshot.blocksAllHooks(foreground()) ||
            (!hold && laserEnabled())
        ) return
        val action = if (hold) snapshot.fourHoldAction else snapshot.tripleAction
        val packageName = if (hold) snapshot.fourHoldPackage else snapshot.triplePackage
        val name = if (hold) "全局四击长按" else "全局四击"
        when (action) {
            ConfigContract.TRIPLE_ACTION_NONE -> Unit
            ConfigContract.TRIPLE_ACTION_ENABLE_LASER -> {
                val success = executor.run(action)
                report.event("$name → 开启虚拟激光${if (success) "成功" else "失败"}")
                if (!success) report.status("$name 开启激光失败，已保持系统原行为")
            }
            ConfigContract.TRIPLE_ACTION_LAUNCH_APP -> {
                val success = executor.launchApp(packageName)
                report.event(
                    "$name → 启动${packageName.trim().ifBlank { "未指定应用" }}" +
                        if (success) "成功" else "失败",
                )
                if (!success) report.status("$name 启动应用失败，请重新选择应用")
            }
            else -> {
                val success = executor.run(action)
                report.event("$name → ${GestureAction.label(action)} ${if (success) "成功" else "失败"}")
            }
        }
    }

    private fun normalGesturesEnabled(snapshot: ModuleConfig): Boolean =
        snapshot.appliesTo(foreground()) &&
            (snapshot.gestureActions[ConfigContract.GESTURE_OFF_DOUBLE_TAP] != ConfigContract.ACTION_SYSTEM ||
                snapshot.gestureActions[ConfigContract.GESTURE_OFF_DOUBLE_HOLD] != ConfigContract.ACTION_SYSTEM)

    private fun globalGesturesEnabled(snapshot: ModuleConfig): Boolean =
        snapshot.globalActionsEnabled &&
            (snapshot.tripleAction != ConfigContract.TRIPLE_ACTION_NONE ||
                snapshot.fourHoldAction != ConfigContract.TRIPLE_ACTION_NONE)

    private fun bufferForReplay(event: KeyEvent, interactive: Boolean) {
        synchronized(replayLock) {
            replayTask?.let(worker::removeCallbacks)
            replayTask = null
            if (event.repeatCount != 0) return
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (buffered.isEmpty()) replayInteractive = interactive
                    if (buffered.size < MAX_BUFFERED_KEYS) {
                        buffered += BufferedKey(KeyEvent(event), android.os.SystemClock.uptimeMillis())
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (buffered.isEmpty()) return
                    if (buffered.size < MAX_BUFFERED_KEYS) {
                        buffered += BufferedKey(KeyEvent(event), android.os.SystemClock.uptimeMillis())
                    }
                    val task = Runnable { replayBufferedPinches() }
                    replayTask = task
                    worker.postDelayed(
                        task,
                        config().multiClickMs.coerceIn(220L, 600L) + VENDOR_REPLAY_GRACE_MS,
                    )
                }
            }
        }
    }

    /** Hands the buffered pinches back to Xiaomi, keeping their original spacing. */
    private fun replayBufferedPinches() {
        val (keys, interactive, generation) = synchronized(replayLock) {
            val keys = ArrayList(buffered)
            buffered.clear()
            replayTask = null
            Triple(keys, replayInteractive, ++replayGeneration)
        }
        if (keys.isEmpty()) return
        detector.reset()
        val manager = touchFilmManager.get() ?: return
        val start = keys.first().arrivedAt
        val pinches = keys.count { it.event.action == KeyEvent.ACTION_DOWN }
        keys.forEachIndexed { index, key ->
            worker.postDelayed({
                if (synchronized(replayLock) { generation != replayGeneration }) return@postDelayed
                replaying.set(true)
                try {
                    runCatching {
                        Xp.callMethod(manager, "interceptKeyBeforeQueueing", key.event, interactive)
                    }.onFailure(onError)
                } finally {
                    replaying.set(false)
                }
                if (index == keys.lastIndex) {
                    report.event("轻捏 $pinches 次未形成手势 → 已交还小米")
                }
            }, key.arrivedAt - start)
        }
    }

    private fun discardBufferedPinch() {
        synchronized(replayLock) {
            replayTask?.let(worker::removeCallbacks)
            replayTask = null
            buffered.clear()
            replayGeneration += 1
        }
    }

    private class BufferedKey(val event: KeyEvent, val arrivedAt: Long)

    private companion object {
        const val FOUR_TAP_HOLD_MS = 650L
        const val VENDOR_REPLAY_GRACE_MS = 30L

        /** Xiaomi's `mCurrentPinchFunction` value for the pinch shortcut panel. */
        const val PINCH_FUNCTION_SHORTCUT_PANEL = 5

        /** Enough for four pinches (DOWN + UP each). */
        const val MAX_BUFFERED_KEYS = 8
    }
}
