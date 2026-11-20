package io.github.skinight.focuspenpointer.hook.pointer

import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import io.github.skinight.focuspenpointer.BuildConfig
import io.github.skinight.focuspenpointer.shell.Xp

/**
 * Builds single-finger SOURCE_TOUCHSCREEN MotionEvents (plus a wheel event for scroll
 * actions) and hands them to the platform injection endpoint.
 */
internal class InjectedTouchSink(
    private val canInjectEvents: () -> Boolean,
    private val inject: (InputEvent) -> Boolean,
) : TouchSink {

    override fun canInject(): Boolean = canInjectEvents()

    override fun touchDown(point: CursorPoint, downTime: Long): Boolean =
        touch("DOWN", MotionEvent.ACTION_DOWN, point, downTime, downTime)

    override fun touchMove(point: CursorPoint, downTime: Long, eventTime: Long): Boolean =
        touch("MOVE", MotionEvent.ACTION_MOVE, point, downTime, eventTime)

    override fun touchUp(point: CursorPoint, downTime: Long, eventTime: Long): Boolean =
        touch("UP", MotionEvent.ACTION_UP, point, downTime, eventTime)

    override fun touchCancel(point: CursorPoint, downTime: Long, eventTime: Long): Boolean =
        touch("CANCEL", MotionEvent.ACTION_CANCEL, point, downTime, eventTime)

    override fun scroll(point: CursorPoint, amount: Float, eventTime: Long): Boolean {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = point.x
            y = point.y
            pressure = 0f
            size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
        }
        return injectAndRecycle(
            MotionEvent.obtain(
                eventTime,
                eventTime,
                MotionEvent.ACTION_SCROLL,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                INJECTED_DEVICE_ID,
                0,
                InputDevice.SOURCE_MOUSE,
                0,
            ),
        )
    }

    private fun touch(
        label: String,
        action: Int,
        point: CursorPoint,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        if (BuildConfig.DEBUG) {
            Xp.log(
                "FocusPenPointer trace: ${android.os.SystemClock.uptimeMillis()} TOUCH $label " +
                    "x=${point.x.toInt()} y=${point.y.toInt()} t=+${eventTime - downTime}ms",
            )
        }
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = point.x
            y = point.y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        }
        return injectAndRecycle(
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                INJECTED_DEVICE_ID,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            ),
        )
    }

    private fun injectAndRecycle(event: MotionEvent): Boolean = try {
        inject(event)
    } finally {
        event.recycle()
    }

    companion object {
        /** Device id of every event we inject (KeyCharacterMap.VIRTUAL_KEYBOARD). */
        const val INJECTED_DEVICE_ID = -1
    }
}
