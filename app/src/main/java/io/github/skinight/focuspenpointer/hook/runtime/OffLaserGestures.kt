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
 * from Xiaomi; the first tap is buffered and replayed to Xiaomi once the window closes
 * without a gesture, so its own single pinch keeps working (slightly delayed).
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
    private val replayLock = Any()
    private var replayDown: KeyEvent? = null
    private var replayUp: KeyEvent? = null
    private var replayInteractive = false
    private var replayTask: Runnable? = null

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
        val consume = snapshot.enabled && (globalGesturesEnabled(snapshot) || normalGesturesEnabled(snapshot))
        if (consume) bufferForReplay(event, interactive)
        return consume
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
            if (!snapshot.enabled ||
                (!globalGesturesEnabled(snapshot) && !normalGesturesEnabled(snapshot))
            ) {
                detector.reset()
                return false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    if (event.repeatCount == 0) detector.onDown(event.eventTime)
                KeyEvent.ACTION_UP -> {
                    val heldMs = (event.eventTime - event.downTime).coerceAtLeast(0L)
                    detector.onUp(event.eventTime, heldMs)
                }
            }
            // Observe only: the vendor side is handled by consumeVendorPinch().
            return false
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
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (replayDown == null) {
                    replayDown = KeyEvent(event)
                    replayInteractive = interactive
                }
                return
            }
            if (event.action == KeyEvent.ACTION_UP && replayDown != null) {
                if (replayUp == null) replayUp = KeyEvent(event)
                val task = Runnable { replayBufferedPinch() }
                replayTask = task
                worker.postDelayed(
                    task,
                    config().multiClickMs.coerceIn(220L, 600L) + VENDOR_REPLAY_GRACE_MS,
                )
            }
        }
    }

    private fun replayBufferedPinch() {
        val pending = synchronized(replayLock) {
            val down = replayDown
            val up = replayUp
            val interactive = replayInteractive
            replayDown = null
            replayUp = null
            replayTask = null
            if (down == null || up == null) null else Triple(down, up, interactive)
        } ?: return
        detector.reset()
        val manager = touchFilmManager.get() ?: return
        replaying.set(true)
        try {
            val downResult = runCatching {
                Xp.callMethod(manager, "interceptKeyBeforeQueueing", pending.first, pending.third)
            }
            val upResult = runCatching {
                Xp.callMethod(manager, "interceptKeyBeforeQueueing", pending.second, pending.third)
            }
            if (downResult.isSuccess && upResult.isSuccess) {
                report.event("单击未形成四击 → 已恢复小米快捷环事件")
            } else {
                downResult.exceptionOrNull()?.let(onError)
                upResult.exceptionOrNull()?.let(onError)
            }
        } finally {
            replaying.set(false)
        }
    }

    private fun discardBufferedPinch() {
        synchronized(replayLock) {
            replayTask?.let(worker::removeCallbacks)
            replayTask = null
            replayDown = null
            replayUp = null
        }
    }

    private companion object {
        const val FOUR_TAP_HOLD_MS = 650L
        const val VENDOR_REPLAY_GRACE_MS = 30L
    }
}
