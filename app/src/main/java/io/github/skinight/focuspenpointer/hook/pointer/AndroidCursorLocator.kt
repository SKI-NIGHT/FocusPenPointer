package io.github.skinight.focuspenpointer.hook.pointer

import io.github.skinight.focuspenpointer.shell.Xp
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.view.Display
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the pointer position from Xiaomi's LaserPointerController or, when requested,
 * from system_server's InputManagerInternal. All reflection failures resolve to null.
 */
internal class AndroidCursorLocator(
    private val context: Context,
    private val laserController: AtomicReference<Any?>,
    systemServerClassLoader: ClassLoader,
) : CursorLocator {
    private val inputManagerInternal: Any?
    private val getCursorPositionMethod: Method?

    init {
        var internal: Any? = null
        var cursorMethod: Method? = null
        runCatching {
            val localServices = Class.forName(
                "com.android.server.LocalServices",
                false,
                systemServerClassLoader,
            )
            val internalClass = Class.forName(
                "com.android.server.input.InputManagerInternal",
                false,
                systemServerClassLoader,
            )
            internal = localServices.getDeclaredMethod(
                "getService",
                Class::class.java,
            ).invoke(null, internalClass)
            cursorMethod = internalClass.getDeclaredMethod(
                "getCursorPosition",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }
        inputManagerInternal = internal
        getCursorPositionMethod = cursorMethod
    }

    fun capabilitySummary(): String =
        "internal=${inputManagerInternal != null}, cursor=${getCursorPositionMethod != null}"

    override fun laserPosition(): CursorPoint? {
        val controller = laserController.get() ?: return null
        val raw = FloatArray(2)
        val valid = runCatching {
            Xp.callMethod(controller, "getPosition", raw)
            isOnScreen(raw[0], raw[1])
        }.getOrDefault(false)
        return if (valid) CursorPoint(raw[0], raw[1]) else null
    }

    override fun systemCursorPosition(): CursorPoint? {
        val point = runCatching {
            getCursorPositionMethod?.invoke(
                inputManagerInternal,
                Display.DEFAULT_DISPLAY,
            ) as? PointF
        }.getOrNull() ?: return null
        return if (isOnScreen(point.x, point.y)) CursorPoint(point.x, point.y) else null
    }

    override fun isOnScreen(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val (width, height) = displayBounds()
        return x >= 0f && y >= 0f && x < width && y < height
    }

    override fun screenSize(): Pair<Int, Int> = displayBounds()

    private fun displayBounds(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return if (display != null) {
            Point().also(display::getRealSize).let { it.x to it.y }
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }
}
