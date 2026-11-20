package io.github.skinight.focuspenpointer.hook.pointer

/**
 * Platform-neutral building blocks of the stylus pointer. Nothing in this file touches
 * Android or Xposed, so the pointer logic can be exercised by plain JVM tests.
 */

/** A screen position in display pixels on the default display. */
internal data class CursorPoint(val x: Float, val y: Float)

/** Where the pointer currently is. Implementations validate on-screen bounds. */
internal interface CursorLocator {
    /** Position of Xiaomi's virtual-laser dot, or null if unavailable/off-screen. */
    fun laserPosition(): CursorPoint?

    /** Position of the system pointer (InputManagerInternal), or null if unavailable. */
    fun systemCursorPosition(): CursorPoint?

    fun isOnScreen(x: Float, y: Float): Boolean

    /** Default display size in pixels (width, height). */
    fun screenSize(): Pair<Int, Int>
}

/**
 * Emits a single finger (and the wheel used by scroll actions). Timing decisions are made
 * by the caller; the sink only builds and injects the events.
 */
internal interface TouchSink {
    fun canInject(): Boolean

    /** Finger ACTION_DOWN at [downTime]. */
    fun touchDown(point: CursorPoint, downTime: Long): Boolean

    /** Finger ACTION_MOVE. */
    fun touchMove(point: CursorPoint, downTime: Long, eventTime: Long): Boolean

    /** Finger ACTION_UP. */
    fun touchUp(point: CursorPoint, downTime: Long, eventTime: Long): Boolean

    /** ACTION_CANCEL: the gesture is abandoned and must not count as a tap. */
    fun touchCancel(point: CursorPoint, downTime: Long, eventTime: Long): Boolean

    /** A single vertical wheel step at [point]. Positive [amount] scrolls up. */
    fun scroll(point: CursorPoint, amount: Float, eventTime: Long): Boolean
}

internal fun interface Cancellable {
    fun cancel()
}

internal fun interface DelayScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}
