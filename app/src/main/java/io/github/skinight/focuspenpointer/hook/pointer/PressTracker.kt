package io.github.skinight.focuspenpointer.hook.pointer

/**
 * Turns pen-body pinches into a single finger while the stylus pointer is active.
 *
 * Anywhere on screen:
 * ```
 * pinch down ─ finger down, pointer locked for the lock window ─ finger follows the pointer
 * pinch up   ─ finger up
 * ```
 * Squeezing the pen body tilts it. Measured on a Focus Pen Pro, the tilt starts ~150 ms
 * before the pinch registers and fades within ~100 ms after it, while a deliberate swipe
 * starts ~200 ms after the press. So the press rewinds the pointer to where it was before
 * the squeeze (see the runtime) and every motion sample in the lock window is discarded.
 * A pure time window, not a distance threshold: the squeeze jolt is 20–40 px, larger than
 * any comfortable threshold, so a threshold would mistake it for intent.
 *
 * At a left or right screen edge:
 * ```
 * pinch down ─ nothing yet, pointer stays on the edge ─┬─ moves inward → finger down + follows
 *                                                      ├─ released     → tap on the edge
 *                                                      └─ held still   → finger down (long press)
 * ```
 * HyperOS's back-gesture strips cancel a touch that has not moved 150 ms after going down,
 * and a pen needs longer than that to start sliding (plus the lock window). Putting the
 * finger down only when the inward slide begins makes the system see what a real finger
 * produces: down and moving at once.
 *
 * Tap, double tap, long press, scroll and drag are left to the app. Thread-safe: pinches
 * arrive on the input policy thread, motion on the laser controller's caller, the edge
 * timeout on the worker.
 */
internal class PressTracker(
    private val listener: Listener,
    private val clock: () -> Long,
    private val lockWindowMs: () -> Long,
    private val scheduler: DelayScheduler,
    private val edgeHoldMs: Long = EDGE_HOLD_MS,
    private val edgeStartPx: Float = EDGE_START_PX,
) {
    interface Listener {
        /** Put the finger down at the pointer. */
        fun onPress()

        /** Lift the finger. */
        fun onRelease()

        /** Abandon the touch (laser exit, app switch, config change). */
        fun onCancel()
    }

    /** What to do with a motion sample. */
    enum class Motion {
        /** Apply the motion normally. */
        PASS,

        /** Pointer locked: swallow the motion. */
        HOLD,
    }

    /** Which screen edge a press starts on; [inwardX] is the sign of motion into the screen. */
    enum class Edge(val inwardX: Int) { LEFT(1), RIGHT(-1) }

    private enum class State { IDLE, EDGE_PENDING, TOUCHING }

    private var state = State.IDLE
    private var lockedUntil = Long.MIN_VALUE
    private var edge: Edge? = null
    private var edgeTravel = 0f
    private var edgeSlideStartedAt = Long.MIN_VALUE
    private var generation = 0L
    private var edgeTimeout: Cancellable? = null

    /** True from pinch down to pinch up. */
    val isTracking: Boolean
        @Synchronized get() = state != State.IDLE

    /**
     * True while an edge press is waiting for its slide or has just started it. Smoothing
     * must be off then: its start-up lag would slow the first ~150 ms of the slide, which is
     * exactly when HyperOS decides whether the back gesture is alive.
     */
    val wantsUnsmoothedMotion: Boolean
        @Synchronized get() = state == State.EDGE_PENDING ||
            (
                state == State.TOUCHING &&
                    edgeSlideStartedAt != Long.MIN_VALUE &&
                    clock() - edgeSlideStartedAt < EDGE_BURST_MS
                )

    /** Starts a press. [edge] is non-null when the pointer sits on a left/right edge. */
    fun onPinchDown(edge: Edge? = null) {
        val lostRelease: Boolean
        val pressNow: Boolean
        synchronized(this) {
            lostRelease = state == State.TOUCHING
            edgeTimeout?.cancel()
            edgeTimeout = null
            val expected = ++generation
            this.edge = edge
            edgeTravel = 0f
            edgeSlideStartedAt = Long.MIN_VALUE
            if (edge == null) {
                state = State.TOUCHING
                lockedUntil = clock() + lockWindowMs().coerceAtLeast(0L)
                pressNow = true
            } else {
                state = State.EDGE_PENDING
                lockedUntil = Long.MIN_VALUE
                edgeTimeout = scheduler.schedule(edgeHoldMs) { onEdgeTimeout(expected) }
                pressNow = false
            }
        }
        // A press still open here means its release was lost; close that touch first.
        if (lostRelease) listener.onCancel()
        if (pressNow) listener.onPress()
    }

    fun onPinchUp() {
        val previous = synchronized(this) {
            val current = state
            edgeTimeout?.cancel()
            edgeTimeout = null
            generation += 1
            state = State.IDLE
            current
        }
        when (previous) {
            State.TOUCHING -> listener.onRelease()
            State.EDGE_PENDING -> {
                // Released on the edge without sliding: a tap there.
                listener.onPress()
                listener.onRelease()
            }
            State.IDLE -> Unit
        }
    }

    /** Decides a motion sample; [dx] is the shaped horizontal delta. */
    fun onMotion(dx: Float): Motion {
        val startTouch = synchronized(this) {
            when (state) {
                State.IDLE -> return Motion.PASS
                State.TOUCHING -> return if (clock() < lockedUntil) Motion.HOLD else Motion.PASS
                State.EDGE_PENDING -> {
                    // Pushing into the edge (outward) must not delay the inward start.
                    edgeTravel = (edgeTravel + dx * (edge?.inwardX ?: 0)).coerceAtLeast(0f)
                    if (edgeTravel < edgeStartPx) return Motion.HOLD
                    edgeTimeout?.cancel()
                    edgeTimeout = null
                    generation += 1
                    state = State.TOUCHING
                    lockedUntil = Long.MIN_VALUE // the slide has begun: follow at once
                    edgeSlideStartedAt = clock()
                    true
                }
            }
        }
        // Finger down on the edge first; this very sample then moves it inward.
        if (startTouch) listener.onPress()
        return Motion.PASS
    }

    fun cancel() {
        val previous = synchronized(this) {
            val current = state
            edgeTimeout?.cancel()
            edgeTimeout = null
            generation += 1
            state = State.IDLE
            current
        }
        if (previous == State.TOUCHING) listener.onCancel()
    }

    private fun onEdgeTimeout(expected: Long) {
        synchronized(this) {
            if (generation != expected || state != State.EDGE_PENDING) return
            edgeTimeout = null
            state = State.TOUCHING
            lockedUntil = Long.MIN_VALUE
        }
        // Held still on the edge: put the finger down so the app sees a long press.
        listener.onPress()
    }

    companion object {
        /**
         * Inward travel (px) that starts an edge slide. Large enough that squeeze wobble does
         * not put the finger down before the hand really slides (it must then keep moving).
         */
        const val EDGE_START_PX = 16f

        /** How long after an edge slide starts motion stays unsmoothed. */
        const val EDGE_BURST_MS = 150L

        /** Holding still this long on an edge puts the finger down anyway. */
        const val EDGE_HOLD_MS = 500L
    }
}
