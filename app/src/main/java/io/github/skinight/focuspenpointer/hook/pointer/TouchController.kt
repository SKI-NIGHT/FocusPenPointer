package io.github.skinight.focuspenpointer.hook.pointer

/**
 * The single virtual finger the pen controls: put down at the pointer, dragged along with
 * it, lifted on release. Apps see an ordinary touch, so taps, long presses, scrolling and
 * system edge gestures all behave exactly as with a real finger.
 *
 * Two refinements make pinches tap like a finger:
 * - **Guard**: the finger stays where it went down for a while, so the pen's release spring
 *   cannot turn a tap into a drag ([isGuarding]).
 * - **Deferred down**: a pinch takes 100–250 ms, a finger tap ~35 ms, and HyperOS's small
 *   window wants the second tap of a double tap to go down within ~200 ms of the first. A
 *   deferred press therefore touches nothing until it is released (then taps instantly) or
 *   the guard runs out (then goes down where it was pressed, backdated to the press).
 *
 * Also owns the last known pointer position, the stuck-finger watchdog and recognition of
 * our own injected touches. Calls arrive from the worker (gestures, watchdog), the policy
 * thread (pinches) and the LaserView thread (movement); all mutation is under this lock.
 */
internal class TouchController(
    private val cursor: CursorLocator,
    private val sink: TouchSink,
    private val scheduler: DelayScheduler,
    private val clock: () -> Long,
    private val onEvent: (String) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    private var lastValidPosition: CursorPoint? = null

    @Volatile
    private var touch: Touch? = null
    private var watchdog: Cancellable? = null
    private var watchdogGeneration = 0L
    private var guardTimer: Cancellable? = null

    /** Down times of recent touches we injected, to recognise our own events. */
    private val recentDownTimes = LongArray(4) { Long.MIN_VALUE }
    private var recentIndex = 0

    /** Uptime of the last lift (tap or drag end), for spotting double taps. */
    @Volatile
    var lastLiftTime: Long = Long.MIN_VALUE
        private set

    /** True while a press is in progress (even one whose finger is not down yet). */
    val isTouching: Boolean
        get() = touch != null

    @Synchronized
    fun canInject(preferSystemCursor: Boolean = false): Boolean =
        currentPosition(preferSystemCursor) != null && sink.canInject()

    /**
     * Starts a press at the pointer. With [guardMs] > 0 the finger stays where it was
     * pressed for that long, whatever the pointer does. With [deferDown] (needs a guard) the
     * finger does not even go down until release or the end of the guard. [at] overrides the
     * pointer position (the caller may still be gliding the drawn pointer there).
     */
    @Synchronized
    fun press(guardMs: Long = 0L, deferDown: Boolean = false, at: CursorPoint? = null): Boolean {
        touch?.let { stale -> finish(stale, cancel = true) } // a lost release
        val point = at?.takeIf { cursor.isOnScreen(it.x, it.y) }
            ?: currentPosition(false) ?: lastValidPosition ?: return false
        lastValidPosition = point
        val now = clock()
        val guarded = guardMs > 0L
        val session = Touch(
            pressTime = now,
            downPoint = point,
            point = point,
            lastMoveTime = now,
            guardUntil = if (guarded) now + guardMs else Long.MIN_VALUE,
            guarding = guarded,
        )
        if (guarded && deferDown) {
            touch = session
            scheduleWatchdog()
            guardTimer = scheduler.schedule(guardMs) { onGuardExpired(session) }
            onEvent("触摸待按下")
            return true
        }
        if (!sendDown(session, now)) return false
        touch = session
        scheduleWatchdog()
        onEvent("触摸按下")
        return true
    }

    /** Uptime at which the current press started, or null when there is none. */
    val downTime: Long?
        get() = touch?.pressTime

    /** Where the current press started, or null when there is none. */
    val downPoint: CursorPoint?
        get() = touch?.downPoint

    /**
     * True while the finger has not moved from where the press started because the guard
     * held it there (it may not even be down yet).
     */
    val isGuarding: Boolean
        get() = touch?.guarding == true

    /**
     * Lifts the finger (a tap if it never moved). With [at], the finger first moves there,
     * so the caller can undo the pen's spring-back on release. A press whose finger never
     * went down becomes an instant tap where it started.
     */
    @Synchronized
    fun release(reason: String, at: CursorPoint? = null) {
        val session = takeTouch() ?: return
        if (!session.sentDown) {
            val now = clock()
            val success = sendDown(session, now) &&
                sink.touchUp(session.downPoint, session.eventDownTime, now + 1)
            lift(reason, success)
            return
        }
        if (at != null && at != session.point && cursor.isOnScreen(at.x, at.y)) {
            session.point = at
            sink.touchMove(at, session.eventDownTime, clock().coerceAtLeast(session.eventDownTime + 1))
        }
        lift(reason, finish(session, cancel = false))
    }

    /**
     * Lifts a finger the guard held in place, after replaying [path] (pointer samples with
     * their real times) so a quick flick keeps its shape and speed. The lift is stamped right
     * after the last sample, so the dropped release spring does not read as a pause.
     */
    @Synchronized
    fun releaseAlong(reason: String, path: List<PositionHistory.Sample>) {
        val session = takeTouch() ?: return
        if (!session.sentDown && !sendDown(session, session.pressTime)) {
            lift(reason, false)
            return
        }
        var lastTime = session.eventDownTime
        for (sample in path) {
            val time = sample.time.coerceAtLeast(lastTime + 1)
            if (!cursor.isOnScreen(sample.point.x, sample.point.y)) continue
            session.point = sample.point
            sink.touchMove(sample.point, session.eventDownTime, time)
            lastTime = time
        }
        lift(reason, sink.touchUp(session.point, session.eventDownTime, lastTime + 1))
    }

    /** Abandons the touch with ACTION_CANCEL so it cannot register as a tap. */
    @Synchronized
    fun cancel(reason: String) {
        val session = takeTouch() ?: return
        finish(session, cancel = true)
        onEvent("$reason → 触摸已取消")
    }

    /** The pointer moved; the finger follows. No-op while no press is in progress. */
    @Synchronized
    fun onPointerMoved(x: Float, y: Float) {
        val session = touch ?: return
        if (!x.isFinite() || !y.isFinite() || !cursor.isOnScreen(x, y)) return
        val now = clock()
        if (session.guarding) {
            lastValidPosition = CursorPoint(x, y)
            refreshWatchdog()
            if (now < session.guardUntil) return
            if (!endGuard(session)) return
        }
        if (now - session.lastMoveTime < MIN_MOVE_INTERVAL_MS) {
            refreshWatchdog() // throttled, but the pen is demonstrably moving
            return
        }
        val point = CursorPoint(x, y)
        session.point = point
        session.lastMoveTime = now
        lastValidPosition = point
        if (sink.touchMove(point, session.eventDownTime, now.coerceAtLeast(session.eventDownTime + 1))) {
            refreshWatchdog()
        }
    }

    /** Extends the stuck-finger watchdog while the pinch is still held. */
    @Synchronized
    fun refreshWatchdog() {
        if (watchdog == null) return
        scheduleWatchdog()
    }

    /** True when [downTime] belongs to a touch this controller injected. */
    @Synchronized
    fun isOwnTouch(downTime: Long): Boolean = recentDownTimes.any { it == downTime }

    @Synchronized
    fun scroll(amount: Float, preferSystemCursor: Boolean): Boolean {
        if (!amount.isFinite() || amount == 0f) return false
        val point = currentPosition(preferSystemCursor) ?: lastValidPosition ?: return false
        return sink.scroll(point, amount, clock())
    }

    /** Guard over while still pressed: put a deferred finger down, backdated to the press. */
    @Synchronized
    private fun onGuardExpired(session: Touch) {
        if (touch !== session) return
        guardTimer = null
        if (session.guarding) endGuard(session)
    }

    /** Ends the guard; false when a deferred finger could not be put down (press dropped). */
    private fun endGuard(session: Touch): Boolean {
        session.guarding = false
        guardTimer?.cancel()
        guardTimer = null
        if (session.sentDown) return true
        if (sendDown(session, session.pressTime)) return true
        takeTouch()
        return false
    }

    /** Injects the finger's DOWN at the press point, stamped [downTime]. */
    private fun sendDown(session: Touch, downTime: Long): Boolean {
        // Record before injecting: the dispatched DOWN can be observed before we return.
        recentDownTimes[recentIndex] = downTime
        recentIndex = (recentIndex + 1) % recentDownTimes.size
        session.eventDownTime = downTime
        if (!sink.touchDown(session.downPoint, downTime)) {
            sink.touchCancel(session.downPoint, downTime, downTime + 1)
            onEvent("触摸按下失败，已安全撤销")
            return false
        }
        session.sentDown = true
        return true
    }

    private fun lift(reason: String, success: Boolean) {
        lastLiftTime = clock()
        onEvent("$reason → 触摸抬起${if (success) "" else "失败"}")
    }

    private fun takeTouch(): Touch? {
        cancelWatchdog()
        guardTimer?.cancel()
        guardTimer = null
        val session = touch ?: return null
        touch = null
        return session
    }

    private fun finish(session: Touch, cancel: Boolean): Boolean {
        touch = null
        if (!session.sentDown) {
            if (cancel) return true // nothing reached the screen
            val now = clock()
            return sendDown(session, now) && sink.touchUp(session.downPoint, session.eventDownTime, now + 1)
        }
        val eventTime = clock().coerceAtLeast(session.eventDownTime + 1)
        return if (cancel) {
            sink.touchCancel(session.point, session.eventDownTime, eventTime)
        } else {
            sink.touchUp(session.point, session.eventDownTime, eventTime)
        }
    }

    private fun currentPosition(preferSystemCursor: Boolean): CursorPoint? {
        if (preferSystemCursor) {
            cursor.systemCursorPosition()?.let {
                lastValidPosition = it
                return it
            }
        }
        return cursor.laserPosition()?.also { lastValidPosition = it }
    }

    private fun scheduleWatchdog() {
        watchdog?.cancel()
        val generation = ++watchdogGeneration
        watchdog = scheduler.schedule(idleTimeoutMs) { onWatchdogFired(generation) }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
        watchdogGeneration += 1
    }

    @Synchronized
    private fun onWatchdogFired(generation: Long) {
        if (generation != watchdogGeneration) return
        watchdog = null
        val session = touch ?: return
        guardTimer?.cancel()
        guardTimer = null
        finish(session, cancel = false)
        lastLiftTime = clock()
        onEvent("触摸 60 秒无变化，已强制抬起")
    }

    private class Touch(
        val pressTime: Long,
        val downPoint: CursorPoint,
        var point: CursorPoint,
        var lastMoveTime: Long,
        val guardUntil: Long = Long.MIN_VALUE,
        @Volatile var guarding: Boolean = false,
    ) {
        /** Whether the finger's DOWN has been injected. */
        var sentDown = false

        /** The downTime stamped on this finger's events (set when the DOWN is sent). */
        var eventDownTime = pressTime
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
        const val MIN_MOVE_INTERVAL_MS = 8L
    }
}
