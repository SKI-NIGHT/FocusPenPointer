package io.github.skinight.focuspenpointer.hook

import io.github.skinight.focuspenpointer.shell.Xp
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps Xiaomi's LaserView mode and lifecycle untouched. The laser-dot paints are made
 * transparent while a mouse pointer Drawable is attached to the view's overlay. The pointer
 * is the system's own arrow icon (same artwork and hotspot as a real mouse), falling back
 * to a drawn arrow if the ROM's resource cannot be loaded.
 *
 * Extra-field keys include this class loader's identity so a hot-reloaded module never
 * mistakes the previous module's overlay for its own.
 */
internal object LaserCursorRenderer {
    private val generation = Integer.toHexString(System.identityHashCode(javaClass.classLoader))
    private val CURSOR_KEY = "focus_pen_laser_cursor@$generation"
    private val INSTALL_LOGGED_KEY = "focus_pen_cursor_install_logged@$generation"

    /** Shared across generations: there is only one set of original alphas per view. */
    private const val PAINT_ALPHAS_KEY = "focus_pen_laser_paint_alphas"

    private val activeViews = WeakHashMap<Any, Boolean>()

    fun isLaserMode(laserView: Any): Boolean =
        Xp.getIntField(laserView, "mMode") == 0

    fun isActive(laserView: Any): Boolean = synchronized(activeViews) {
        activeViews.containsKey(laserView)
    }

    fun hasActiveCursor(): Boolean = synchronized(activeViews) {
        activeViews.isNotEmpty()
    }

    fun install(laserView: Any, x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !isLaserMode(laserView)) return false
        val view = laserView as View
        val paints = laserPaints(laserView)

        if (Xp.getAdditionalInstanceField(laserView, PAINT_ALPHAS_KEY) == null) {
            Xp.setAdditionalInstanceField(
                laserView,
                PAINT_ALPHAS_KEY,
                paints.map(Paint::getAlpha).toIntArray(),
            )
        }
        paints.forEach { it.alpha = 0 }

        var cursor = Xp.getAdditionalInstanceField(laserView, CURSOR_KEY)
            as? PointerDrawable
        if (cursor == null) {
            cursor = PointerDrawable.create(view.context)
            Xp.setAdditionalInstanceField(laserView, CURSOR_KEY, cursor)
            view.overlay.add(cursor.drawable)
        }
        cursor.moveTo(x, y)
        synchronized(activeViews) {
            activeViews[laserView] = true
        }
        view.invalidate()

        if (Xp.getAdditionalInstanceField(laserView, INSTALL_LOGGED_KEY) == null) {
            Xp.setAdditionalInstanceField(laserView, INSTALL_LOGGED_KEY, true)
            Xp.log(
                "FocusPenPointer: Xiaomi laser dot replaced by ${cursor.description} mouse pointer",
            )
        }
        return true
    }

    fun restore(laserView: Any) {
        val view = laserView as? View ?: return
        val wasActive = isActive(laserView)
        val cursor = Xp.getAdditionalInstanceField(laserView, CURSOR_KEY)
            as? PointerDrawable
        if (cursor != null) {
            view.overlay.remove(cursor.drawable)
            Xp.removeAdditionalInstanceField(laserView, CURSOR_KEY)
        }

        val originalAlphas = Xp.getAdditionalInstanceField(
            laserView,
            PAINT_ALPHAS_KEY,
        ) as? IntArray
        if (originalAlphas != null) {
            laserPaints(laserView).forEachIndexed { index, paint ->
                paint.alpha = originalAlphas.getOrElse(index) { 255 }
            }
            Xp.removeAdditionalInstanceField(laserView, PAINT_ALPHAS_KEY)
        }
        synchronized(activeViews) {
            activeViews.remove(laserView)
        }
        if (wasActive) {
            runCatching { Xp.callMethod(laserView, "clearPath") }
        }
        view.invalidate()
    }

    fun restoreAll() {
        val views = synchronized(activeViews) { activeViews.keys.toList() }
        views.forEach { laserView ->
            val view = laserView as? View ?: return@forEach
            view.post { runCatching { restore(laserView) } }
        }
    }

    /** Restores every view on its own thread and waits, so a reload starts from clean views. */
    fun restoreAllBlocking(timeoutMs: Long) {
        val views = synchronized(activeViews) { activeViews.keys.toList() }
        if (views.isEmpty()) return
        val latch = CountDownLatch(views.size)
        views.forEach { laserView ->
            val view = laserView as? View
            if (view == null) {
                latch.countDown()
                return@forEach
            }
            val task = Runnable {
                try {
                    runCatching { restore(laserView) }
                } finally {
                    latch.countDown()
                }
            }
            if (view.handler?.looper == Looper.myLooper()) task.run()
            else if (!view.post(task)) latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun laserPaints(laserView: Any): List<Paint> = listOf(
        Xp.getObjectField(laserView, "mOutBlurPaint") as Paint,
        Xp.getObjectField(laserView, "mOutFillPaint") as Paint,
        Xp.getObjectField(laserView, "mInPaint") as Paint,
        Xp.getObjectField(laserView, "mPathPaint") as Paint,
    )

    /** A pointer image plus the hotspot that must sit exactly on the pointer position. */
    private class PointerDrawable(
        val drawable: Drawable,
        private val hotSpotX: Float,
        private val hotSpotY: Float,
        val description: String,
    ) {
        fun moveTo(x: Float, y: Float) {
            val left = (x - hotSpotX).toInt()
            val top = (y - hotSpotY).toInt()
            drawable.setBounds(
                left,
                top,
                left + drawable.intrinsicWidth.coerceAtLeast(1),
                top + drawable.intrinsicHeight.coerceAtLeast(1),
            )
        }

        companion object {
            private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

            fun create(context: Context): PointerDrawable =
                runCatching { systemArrow(context) }
                    .onFailure {
                        Xp.log(
                            "FocusPenPointer: system pointer icon unavailable, using built-in " +
                                "arrow (${it.javaClass.simpleName}: ${it.message})",
                        )
                    }
                    .getOrNull()
                    ?: fallbackArrow(context)

            /**
             * The framework's `<pointer-icon bitmap hotSpotX hotSpotY>` descriptions. On
             * some densities `pointer_arrow` is the bare PNG, so prefer the `_icon` entries
             * (what the `pointerIconArrow` theme attribute references).
             */
            private val ICON_CANDIDATES = listOf(
                "pointer_arrow_icon",
                "pointer_arrow_vector_icon",
                "pointer_arrow",
            )

            private fun systemArrow(context: Context): PointerDrawable {
                val failures = mutableListOf<String>()
                for (name in ICON_CANDIDATES) {
                    runCatching { return parsePointerIcon(context, name) }
                        .onFailure { failures += "$name: ${it.javaClass.simpleName} ${it.message}" }
                }
                error(failures.joinToString("; "))
            }

            private fun parsePointerIcon(context: Context, name: String): PointerDrawable {
                val res = Resources.getSystem()
                val iconId = res.getIdentifier(name, "drawable", "android")
                require(iconId != 0) { "missing" }
                res.getXml(iconId).use { parser ->
                    while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) Unit
                    require(parser.name == "pointer-icon") { "unexpected <${parser.name}>" }
                    val bitmapId = parser.getAttributeResourceValue(ANDROID_NS, "bitmap", 0)
                    require(bitmapId != 0) { "pointer-icon without bitmap" }
                    val attrs = intArrayOf(android.R.attr.hotSpotX, android.R.attr.hotSpotY)
                    val sorted = attrs.sortedArray()
                    val typed = res.obtainAttributes(parser, sorted)
                    val hotSpotX: Float
                    val hotSpotY: Float
                    try {
                        hotSpotX = typed.getDimension(sorted.indexOf(android.R.attr.hotSpotX), 0f)
                        hotSpotY = typed.getDimension(sorted.indexOf(android.R.attr.hotSpotY), 0f)
                    } finally {
                        typed.recycle()
                    }
                    val drawable = requireNotNull(context.getDrawable(bitmapId)) {
                        "pointer bitmap not loadable"
                    }.mutate()
                    return PointerDrawable(drawable, hotSpotX, hotSpotY, "system ($name)")
                }
            }

            private fun fallbackArrow(context: Context): PointerDrawable =
                PointerDrawable(
                    FallbackArrowDrawable(context.resources.displayMetrics.density),
                    hotSpotX = 0f,
                    hotSpotY = 0f,
                    description = "built-in",
                )
        }
    }

    private class FallbackArrowDrawable(density: Float) : Drawable() {
        private val scale = density.coerceAtLeast(1f)
        private val path = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * scale
            strokeJoin = Paint.Join.ROUND
        }

        override fun getIntrinsicWidth(): Int = (22f * scale).toInt()

        override fun getIntrinsicHeight(): Int = (29f * scale).toInt()

        override fun draw(canvas: Canvas) {
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            path.reset()
            path.moveTo(left, top)
            path.lineTo(left + 2.5f * scale, top + 22f * scale)
            path.lineTo(left + 7.5f * scale, top + 16.8f * scale)
            path.lineTo(left + 12.5f * scale, top + 27f * scale)
            path.lineTo(left + 17.2f * scale, top + 24.7f * scale)
            path.lineTo(left + 12.2f * scale, top + 14.8f * scale)
            path.lineTo(left + 20f * scale, top + 14.8f * scale)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            stroke.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            stroke.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
