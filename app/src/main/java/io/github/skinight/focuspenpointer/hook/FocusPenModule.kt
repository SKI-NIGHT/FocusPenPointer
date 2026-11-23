package io.github.skinight.focuspenpointer.hook

import io.github.skinight.focuspenpointer.shell.Xp
import io.github.skinight.focuspenpointer.shell.HookParam
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.skinight.focuspenpointer.hook.pointer.PointerMotionFilter
import io.github.skinight.focuspenpointer.shell.HookModule
import io.github.skinight.focuspenpointer.shell.HookPoints
import io.github.skinight.focuspenpointer.shell.ModuleHost
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * All hook behavior, dispatched by [HookPoints] id. Instantiated reflectively by
 * [ModuleHost] at boot and again on every hot reload.
 */
@Suppress("unused") // Created by name from ModuleHost.
internal class FocusPenModule(
    private val systemServerClassLoader: ClassLoader,
) : HookModule {
    private val runtime = AtomicReference<FocusPenRuntime?>()
    private val pendingLaserController = AtomicReference<Any?>()
    private val initLock = Any()

    @Volatile
    private var disposed = false

    override fun shellVersion(): Int = HookPoints.SHELL_VERSION

    override fun attach() {
        // At boot nothing exists yet; after a hot reload Xiaomi's singletons are already
        // live, so pick the state up from them.
        recoverLaserController()?.let(pendingLaserController::set)
        recoverTouchFilmManager()?.let { manager ->
            ensureRuntime(manager)?.attachTouchFilmManager(manager)
        }
    }

    override fun dispose() {
        disposed = true
        runtime.getAndSet(null)?.dispose()
    }

    override fun beforeHook(id: Int, param: HookParam) {
        if (disposed) return
        when (id) {
            HookPoints.LASER_TURN_OFF_SHOW -> captureMousePresentation(param)
            HookPoints.VIEW_SET_KEEP_PATH -> {
                val keepPath = param.args.firstOrNull() as? Boolean ?: return
                if (keepPath &&
                    runtime.get()?.shouldSuppressVendorDrawing() == true &&
                    LaserCursorRenderer.isActive(param.thisObject)
                ) {
                    param.result = null
                }
            }
            HookPoints.VIEW_SET_MODE -> {
                val mode = param.args.firstOrNull() as? Int ?: return
                if (mode == 1 &&
                    runtime.get()?.shouldSuppressVendorDrawing() == true &&
                    LaserCursorRenderer.isActive(param.thisObject)
                ) {
                    param.result = null
                }
            }
            HookPoints.LASER_MOVE -> filterMotion(param)
            HookPoints.POLICY_INTERCEPT_KEY -> {
                val event = param.args.firstOrNull() as? KeyEvent ?: return
                if (!isFocusPenEvent(event)) return
                if (ensureRuntime(param.thisObject)?.intercept(event) == true) {
                    param.result = 0
                }
            }
            HookPoints.TOUCH_FILM_FOCUS_CHANGED ->
                ensureRuntime(param.thisObject)?.updateForeground(param.args.firstOrNull())
            HookPoints.INPUT_INTERCEPT_DISPATCH -> {
                val event = param.args.firstOrNull { it is KeyEvent } as? KeyEvent ?: return
                if (event.repeatCount == 0 || !isFocusPenEvent(event)) return
                if (runtime.get()?.shouldDropPenKeyRepeat(event) == true) {
                    // InputManagerService contract: a negative delay drops the key.
                    param.result = -1L
                }
            }
            HookPoints.TOUCH_FILM_INTERCEPT_KEY -> {
                val event = param.args.firstOrNull() as? KeyEvent ?: return
                if (!isFocusPenEvent(event)) return
                val interactive = param.args.getOrNull(1) as? Boolean ?: false
                if (ensureRuntime(param.thisObject)
                        ?.shouldConsumeVendorOffLaserPinch(event, interactive) == true
                ) {
                    // This ROM's boolean contract is "handled/consumed".
                    param.result = true
                }
            }
            HookPoints.TOUCH_FILM_POINTER_EVENT -> {
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                val currentRuntime = ensureRuntime(param.thisObject) ?: return
                if (currentRuntime.isOwnInjectedTouch(event)) {
                    // Our own virtual finger: Xiaomi would treat it as a real touch and
                    // close the laser, and so would the module's touch-to-exit rule.
                    param.result = null
                    return
                }
                currentRuntime.onPointerEvent(event)
            }
        }
    }

    override fun afterHook(id: Int, param: HookParam) {
        if (disposed || param.throwable != null) return
        when (id) {
            HookPoints.TOUCH_FILM_INIT ->
                ensureRuntime(param.thisObject)?.attachTouchFilmManager(param.thisObject)
            HookPoints.LASER_INIT -> {
                pendingLaserController.set(param.thisObject)
                ensureRuntime(param.thisObject)?.attachLaserController(param.thisObject)
            }
            HookPoints.LASER_TURN_OFF_SHOW -> {
                val wasMouse = param.getExtra(PRESENTATION_WAS_MOUSE_EXTRA) as? Boolean
                    ?: false
                runtime.get()?.onLaserPresentationHidden("turnoffvirtuallasershow", wasMouse)
            }
            HookPoints.VIEW_SET_POSITION -> onViewPosition(param)
            HookPoints.INPUT_POINTER_CAPTURE ->
                runtime.get()?.onPointerCaptureRequested(
                    param.args.getOrNull(0) as? android.os.IBinder,
                    (param.args.getOrNull(1) as? Int) ?: 0,
                )
            HookPoints.LASER_SAFE_MARGIN -> {
                // Xiaomi keeps the laser 8dp (~22px) inside the screen. Edge gestures (back,
                // home, dock) start closer than that, so let the pointer reach the edge.
                if (runtime.get()?.shouldReachScreenEdges() == true) {
                    param.result = REACHABLE_EDGE_MARGIN_PX
                }
            }
            HookPoints.TOUCH_FILM_ENABLE_LASER ->
                onLaserState(param, enabled = true, "enableVirtualLaser", isController = false)
            HookPoints.TOUCH_FILM_TURN_ON_LASER ->
                onLaserState(param, enabled = true, "turnonvirtuallaser", isController = false)
            HookPoints.TOUCH_FILM_TURN_OFF_LASER ->
                onLaserState(param, enabled = false, "turnoffvirtuallaser", isController = false)
            HookPoints.LASER_ENABLE_LASER ->
                onLaserState(param, enabled = true, "enableVirtualLaser", isController = true)
            HookPoints.LASER_TURN_ON_LASER ->
                onLaserState(param, enabled = true, "turnonvirtuallaser", isController = true)
            HookPoints.LASER_TURN_OFF_LASER ->
                onLaserState(param, enabled = false, "turnoffvirtuallaser", isController = true)
        }
    }

    private fun captureMousePresentation(param: HookParam) {
        // This private vendor method is also reached while Xiaomi is managing its original
        // drawing presentation. Capture whether the view owned by this exact controller is
        // currently carrying our mouse overlay; a cursor left on another/stale LaserView must
        // never turn an original brush clear into a complete laser exit.
        val wasMousePresentation = runCatching {
            val laserView = Xp.getObjectField(param.thisObject, "mLaserView")
                ?: return@runCatching false
            val laserState = Xp.getObjectField(param.thisObject, "mLaserState")
                ?: return@runCatching false
            // Verified ROM contract: 0 = pointer, 1 = original brush.
            if (Xp.getIntField(laserState, "mCurrentMode") != 0) {
                return@runCatching false
            }
            LaserCursorRenderer.isActive(laserView) && LaserCursorRenderer.isLaserMode(laserView)
        }.getOrDefault(false)
        param.setExtra(PRESENTATION_WAS_MOUSE_EXTRA, wasMousePresentation)
    }

    private fun filterMotion(param: HookParam) {
        val dx = param.args.getOrNull(0) as? Float ?: return
        val dy = param.args.getOrNull(1) as? Float ?: return
        val currentRuntime = runtime.get() ?: return
        when (val result = currentRuntime.filterPointerMotion(dx, dy)) {
            null -> Unit
            PointerMotionFilter.Result.Drop -> param.result = null
            is PointerMotionFilter.Result.Move -> {
                param.args[0] = result.dx
                param.args[1] = result.dy
            }
        }
    }

    private fun onViewPosition(param: HookParam) {
        val currentRuntime = runtime.get() ?: return
        val x = param.args.getOrNull(0) as? Float ?: return
        val y = param.args.getOrNull(1) as? Float ?: return
        currentRuntime.recordPointerPosition(x, y)
        if (currentRuntime.shouldReplaceLaserPresentation() &&
            LaserCursorRenderer.isLaserMode(param.thisObject)
        ) {
            val installed = runCatching {
                LaserCursorRenderer.install(param.thisObject, x, y)
            }.getOrElse {
                Xp.log(
                    "FocusPenPointer: laser drawable install failed; original retained\n" +
                        it.stackTraceToString(),
                )
                false
            }
            if (installed) {
                currentRuntime.updateLaser(true, "LaserView.setPosition")
                currentRuntime.onLaserPositionChanged(x, y)
            }
        } else {
            LaserCursorRenderer.restore(param.thisObject)
            // A touch in progress keeps following the pointer even while the pointer is not
            // drawn (e.g. focus briefly unknown during a swipe-home transition).
            currentRuntime.onLaserPositionChanged(x, y)
        }
    }

    private fun onLaserState(
        param: HookParam,
        enabled: Boolean,
        source: String,
        isController: Boolean,
    ) {
        if (isController) pendingLaserController.set(param.thisObject)
        ensureRuntime(param.thisObject)?.apply {
            if (isController) attachLaserController(param.thisObject)
            updateLaser(enabled, source)
        }
    }

    private fun ensureRuntime(source: Any): FocusPenRuntime? {
        if (disposed) return null
        runtime.get()?.let { return it }
        synchronized(initLock) {
            if (disposed) return null
            runtime.get()?.let { return it }
            val context = findContext(source) ?: run {
                Xp.log(
                    "FocusPenPointer: Context unavailable from ${source.javaClass.name}; fail-open",
                )
                return null
            }
            return runCatching {
                FocusPenRuntime(context, systemServerClassLoader).also { created ->
                    runtime.set(created)
                    created.start()
                    recoverLaserController()?.also(pendingLaserController::set)
                    pendingLaserController.get()?.let(created::attachLaserController)
                    recoverTouchFilmManager()?.let(created::attachTouchFilmManager)
                    recoverCurrentFocus()?.let(created::updateForeground)
                    ModuleHost.onSystemContextAvailable(context)
                    Xp.log(
                        "FocusPenPointer: runtime initialized from ${source.javaClass.name}",
                    )
                }
            }.getOrElse {
                runtime.set(null)
                Xp.log(
                    "FocusPenPointer: runtime init failed; fail-open\n${it.stackTraceToString()}",
                )
                null
            }
        }
    }

    private fun staticField(className: String, field: String): Any? = runCatching {
        Xp.getStaticObjectField(
            Xp.findClass(className, systemServerClassLoader),
            field,
        )
    }.getOrNull()

    private fun recoverLaserController(): Any? = runCatching {
        val shortcut = staticField(CompatProfile.SHORTCUT_MANAGER_CLASS, "sInstance")
            ?: return null
        Xp.getObjectField(shortcut, "mLaserPointerController")
    }.getOrNull()

    private fun recoverTouchFilmManager(): Any? =
        staticField(CompatProfile.TOUCH_FILM_CLASS, "sInstance")

    private fun recoverCurrentFocus(): Any? = runCatching {
        val manager = recoverTouchFilmManager() ?: return null
        Xp.getObjectField(manager, "mCurrentFocusWindow")
    }.getOrNull()

    private fun findContext(instance: Any): Context? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            clazz.declaredFields.forEach { field ->
                if (!Modifier.isStatic(field.modifiers) &&
                    Context::class.java.isAssignableFrom(field.type)
                ) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(instance) as? Context
                    }.getOrNull()
                    if (value != null) return value
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun isFocusPenEvent(event: KeyEvent): Boolean {
        val supportedGesture =
            event.scanCode == CompatProfile.SCAN_PINCH_F19 ||
                event.keyCode == CompatProfile.KEY_SLIDE_UP ||
                event.keyCode == CompatProfile.KEY_SLIDE_DOWN
        if (!supportedGesture) return false
        val device = event.device ?: return false
        return device.vendorId == 0x0022 &&
            device.productId == 0x5081 &&
            device.name.startsWith("Xiaomi Focus Pen Pro")
    }

    private companion object {
        const val PRESENTATION_WAS_MOUSE_EXTRA = "focus_pen_presentation_was_mouse"

        /** Not 0: Xiaomi clamps to the frame's exclusive right/bottom, one pixel off-screen. */
        const val REACHABLE_EDGE_MARGIN_PX = 1f
    }
}
