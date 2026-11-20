package io.github.skinight.focuspenpointer.hook

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.skinight.focuspenpointer.BuildConfig
import io.github.skinight.focuspenpointer.config.ConfigContract
import io.github.skinight.focuspenpointer.hook.pointer.AndroidCursorLocator
import io.github.skinight.focuspenpointer.hook.pointer.Cancellable
import io.github.skinight.focuspenpointer.hook.pointer.CursorPoint
import io.github.skinight.focuspenpointer.hook.pointer.DelayScheduler
import io.github.skinight.focuspenpointer.hook.pointer.InjectedTouchSink
import io.github.skinight.focuspenpointer.hook.pointer.PointerMotionFilter
import io.github.skinight.focuspenpointer.hook.pointer.PositionHistory
import io.github.skinight.focuspenpointer.hook.pointer.PressTracker
import io.github.skinight.focuspenpointer.hook.pointer.TouchController
import io.github.skinight.focuspenpointer.hook.runtime.ActionExecutor
import io.github.skinight.focuspenpointer.hook.runtime.LaserSession
import io.github.skinight.focuspenpointer.hook.runtime.OffLaserGestures
import io.github.skinight.focuspenpointer.shell.Xp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates the module inside system_server. It owns no gesture logic itself:
 *
 * - [LaserSession]: is the laser (stylus pointer) on, and turning it off cleanly.
 * - [PressTracker] + [TouchController] + [PointerMotionFilter]: the pointer and its finger.
 * - [OffLaserGestures]: two/four-pinch gestures and swipes while the laser is off.
 * - [ActionExecutor]: what a configured action does.
 *
 * This class keeps configuration and the foreground app, routes hook calls to those
 * components, and contains failures with a circuit breaker.
 */
internal class FocusPenRuntime(
    private val context: Context,
    systemServerClassLoader: ClassLoader,
) {
    private val workerThread = HandlerThread("FocusPenPointer").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val scheduler = DelayScheduler { delay, block ->
        val runnable = Runnable(block)
        worker.postDelayed(runnable, delay)
        Cancellable { worker.removeCallbacks(runnable) }
    }
    private val reporter = RuntimeReporter(context.contentResolver, worker)
    private val foreground = AtomicReference<String?>(null)
    private val pendingUnknownForeground = AtomicReference<Runnable?>(null)
    private val touchFilmManager = AtomicReference<Any?>(null)
    private val laserController = AtomicReference<Any?>(null)
    private val circuitOpen = AtomicBoolean(false)
    private val pointerMotionSeen = AtomicBoolean(false)
    private var cancellationReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var errorWindowStart = 0L
    private var errorCount = 0

    private val config = SystemConfigClient(context.contentResolver, worker) {
        cancelInteraction("配置变化")
        synchronizeCursor()
        reportStatus("配置已刷新")
    }
    private val injector = InputInjector(systemServerClassLoader) {
        config.snapshot.get().platformProfile
    }
    private val cursorLocator =
        AndroidCursorLocator(context, laserController, systemServerClassLoader)
    private val touch = TouchController(
        cursor = cursorLocator,
        sink = InjectedTouchSink(injector::canInjectKey, injector::inject),
        scheduler = scheduler,
        clock = SystemClock::uptimeMillis,
        onEvent = reporter::event,
    )
    private val pointerFilter = PointerMotionFilter(SystemClock::uptimeMillis)
    private val positions = PositionHistory(SystemClock::uptimeMillis)

    private val laser: LaserSession = LaserSession(
        clock = SystemClock::uptimeMillis,
        tasks = object : LaserSession.Tasks {
            override fun post(block: () -> Unit) {
                worker.post(block)
            }

            override fun postDelayed(delayMs: Long, block: () -> Unit) {
                worker.postDelayed(block, delayMs)
            }
        },
        vendor = object : LaserSession.Vendor {
            override fun enable(): Boolean {
                val manager = touchFilmManager.get() ?: return false
                Xp.callMethod(manager, "enableVirtualLaser")
                return true
            }

            override fun turnOff(): Boolean {
                val controller = laserController.get() ?: return false
                Xp.callMethod(controller, "turnoffvirtuallaser", 0)
                return true
            }
        },
        listener = object : LaserSession.Listener {
            override fun onLaserChanged(enabled: Boolean, source: String) {
                cancelInteraction("激光状态变化")
                reporter.event("虚拟激光=${if (enabled) "开启" else "关闭"} ($source)")
                synchronizeCursor()
                reportStatus("虚拟激光状态已更新")
            }

            override fun onExitRequested(reason: String) {
                offLaser.reset()
                press.cancel()
            }

            override fun onExitCleanup(reason: String) {
                cancelInteraction(reason)
                LaserCursorRenderer.restoreAll()
            }

            override fun event(message: String) = reporter.event(message)

            override fun status(message: String) = reportStatus(message)
        },
    )

    private val executor: ActionExecutor = ActionExecutor(
        context = context,
        injector = injector,
        touch = touch,
        laser = laser,
        scrollMagnitude = {
            when (config.snapshot.get().scrollAmount) {
                ConfigContract.SCROLL_SHORT -> 1f
                ConfigContract.SCROLL_LONG -> 6f
                else -> 3f
            }
        },
    )

    private val offLaser: OffLaserGestures = OffLaserGestures(
        worker = worker,
        config = { config.snapshot.get() },
        foreground = { foreground.get() },
        laserEnabled = { laser.isEnabled },
        executor = executor,
        touchFilmManager = touchFilmManager,
        report = object : OffLaserGestures.Reporter {
            override fun event(message: String) = reporter.event(message)
            override fun status(message: String) = reportStatus(message)
        },
        onError = ::recordError,
    )

    /** Screen edge the current press started on (set just before the press begins). */
    @Volatile
    private var pressEdge: PressTracker.Edge? = null

    /** Where the current press puts the finger (the pre-squeeze point). */
    @Volatile
    private var pressPoint: CursorPoint? = null

    /** Finger minus drawn pointer for the current press (the hidden pre-squeeze correction). */
    @Volatile
    private var pressOffsetX = 0f

    @Volatile
    private var pressOffsetY = 0f

    /** Until when pen motion after a release (its spring-back) is ignored. */
    @Volatile
    private var releaseSettleUntil = Long.MIN_VALUE

    private val press: PressTracker = PressTracker(
        clock = SystemClock::uptimeMillis,
        lockWindowMs = { config.snapshot.get().pressLockMs.toLong() },
        scheduler = scheduler,
        listener = object : PressTracker.Listener {
            override fun onPress() {
                // Edge presses must move within 150 ms or HyperOS cancels the back gesture.
                val guard = if (pressEdge == null) {
                    config.snapshot.get().pressLockMs.toLong() + TAP_GUARD_MS
                } else {
                    0L
                }
                // Put the finger down only on release (an instant tap) unless this pinch is
                // the second tap of a double tap, which must go down right away.
                val defer = guard > 0L &&
                    SystemClock.uptimeMillis() - touch.lastLiftTime > DOUBLE_TAP_WINDOW_MS
                touch.press(guard, deferDown = defer, at = pressPoint)
            }

            override fun onRelease() {
                if (touch.isGuarding) releaseGuarded() else touch.release("松开", positionBeforeReleaseSpring())
                pointerFilter.resetSmoothing()
                val settle = minOf(RELEASE_SETTLE_MS, config.snapshot.get().pressLockMs.toLong())
                releaseSettleUntil = SystemClock.uptimeMillis() + settle
            }

            override fun onCancel() {
                touch.cancel("按压中止")
            }
        },
    )

    fun start() {
        Xp.log("FocusPenPointer: ${injector.capabilitySummary(cursorLocator.capabilitySummary())}")
        config.start()
        registerCancellationReceiver()
        reporter.event("运行时初始化；激光=${laser.isEnabled}")
        reportStatus("Hook 已安装，等待配置")
    }

    /**
     * Tears this runtime down for a hot reload: releases any held button, restores Xiaomi's
     * laser presentation and stops every thread, observer and receiver it owns.
     */
    fun dispose() {
        circuitOpen.set(true)
        runCatching { cancelInteraction("模块热重载") }
        pendingUnknownForeground.getAndSet(null)?.let(worker::removeCallbacks)
        cancellationReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        cancellationReceiver = null
        debugReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        debugReceiver = null
        config.stop()
        runCatching { LaserCursorRenderer.restoreAllBlocking(DISPOSE_RESTORE_TIMEOUT_MS) }
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        Xp.log("FocusPenPointer: runtime disposed")
    }

    // ---- Vendor objects and state reports -------------------------------------------------

    fun attachLaserController(controller: Any) {
        laserController.set(controller)
        reporter.event("已取得 LaserPointerController")
        val active = runCatching { Xp.getBooleanField(controller, "mvirtuallaser") }
            .getOrDefault(false)
        if (active) laser.onVendorState(true, "LaserPointerController.mvirtuallaser")
        synchronizeCursor()
    }

    fun attachTouchFilmManager(manager: Any) {
        if (touchFilmManager.getAndSet(manager) !== manager) {
            reporter.event("已取得 MiuiStylusTouchFilmManager")
        }
    }

    fun updateLaser(enabled: Boolean, source: String) = laser.onVendorState(enabled, source)

    fun updateForeground(windowState: Any?) {
        val next = extractPackage(windowState)
        if (next == null && foreground.get() != null) {
            // Transient windows report no owner; only accept "unknown" if it persists.
            val delayed = object : Runnable {
                override fun run() {
                    if (pendingUnknownForeground.compareAndSet(this, null)) applyForeground(null)
                }
            }
            pendingUnknownForeground.getAndSet(delayed)?.let(worker::removeCallbacks)
            worker.postDelayed(delayed, UNKNOWN_FOREGROUND_GRACE_MS)
            return
        }
        pendingUnknownForeground.getAndSet(null)?.let(worker::removeCallbacks)
        applyForeground(next)
    }

    private fun applyForeground(next: String?) {
        if (foreground.getAndSet(next) == next) return
        if (touch.isTouching) {
            // System gestures (swipe home, recents) move focus while the finger is still
            // down. Cancelling would lift it mid-gesture; the touch ends on pinch release.
            reporter.event("前台应用=${next ?: "未知"}（触摸中，保持按下）")
        } else {
            cancelInteraction("前台应用切换")
            reporter.event("前台应用=${next ?: "未知"}")
        }
        synchronizeCursor()
        reportStatus("前台窗口已更新")
    }

    // ---- Stylus mouse ---------------------------------------------------------------------

    /** The laser is on and the foreground app uses the stylus pointer. */
    private fun pointerActive(): Boolean =
        !circuitOpen.get() && laser.isEnabled && shouldReplaceLaserPresentation()

    /**
     * Whether the laser should be presented as a mouse pointer in the foreground app.
     * Laser state is checked separately: LaserView drawing is itself proof the laser is on.
     */
    fun shouldReplaceLaserPresentation(): Boolean =
        !circuitOpen.get() &&
            !laser.isEnableSuppressed &&
            config.snapshot.get().usesLaserMouse(foreground.get())

    /** Whether the pointer may go right up to the screen edges in the foreground app. */
    fun shouldReachScreenEdges(): Boolean =
        config.snapshot.get().reachScreenEdges && shouldReplaceLaserPresentation()

    /** Xiaomi may switch to its drawing mode mid-press; keep the pointer while a touch lasts. */
    fun shouldSuppressVendorDrawing(): Boolean =
        pointerActive() && (press.isTracking || touch.isTouching)

    /**
     * Shapes one `LaserPointerController.move` sample. Returns null to leave it untouched.
     */
    fun filterPointerMotion(dx: Float, dy: Float): PointerMotionFilter.Result? {
        if (pointerMotionSeen.compareAndSet(false, true)) {
            Xp.log("FocusPenPointer: pointer motion hook active (LaserPointerController.move)")
        }
        if (!pointerActive()) return null
        val snapshot = config.snapshot.get()
        val settings = PointerMotionFilter.Settings(
            sensitivity = snapshot.pointerSensitivity / 100f,
            smoothing = if (press.wantsUnsmoothedMotion) 0f else snapshot.pointerSmoothing / 100f,
        )
        val (shapedX, shapedY) = pointerFilter.shape(dx, dy, settings)
            ?: return PointerMotionFilter.Result.Drop
        if (!press.isTracking && SystemClock.uptimeMillis() < releaseSettleUntil) {
            // The pen is springing back from the release: keep the pointer where it was.
            trace { "M raw=${f1(dx)},${f1(dy)} SETTLE" }
            pointerFilter.resetSmoothing()
            return PointerMotionFilter.Result.Drop
        }
        val decision = press.onMotion(shapedX)
        trace { "M raw=${f1(dx)},${f1(dy)} out=${f1(shapedX)},${f1(shapedY)} $decision" }
        if (decision == PressTracker.Motion.HOLD) {
            pointerFilter.resetSmoothing()
            return PointerMotionFilter.Result.Drop
        }
        return PointerMotionFilter.Result.Move(shapedX, shapedY)
    }

    /** Every drawn laser position, for the pre-squeeze rewind. */
    fun recordPointerPosition(x: Float, y: Float) = positions.record(x, y)

    fun onLaserPositionChanged(x: Float, y: Float) {
        if (!touch.isTouching) return
        runCatching { touch.onPointerMoved(x + pressOffsetX, y + pressOffsetY) }.onFailure(::recordError)
    }

    /** True for touch events this module injected itself (the virtual finger). */
    fun isOwnInjectedTouch(event: MotionEvent): Boolean =
        event.deviceId == InjectedTouchSink.INJECTED_DEVICE_ID &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            touch.isOwnTouch(event.downTime)

    /**
     * A pinch press. The pen starts tilting ~150 ms before the pinch registers, so the finger
     * goes down where the pointer was before the squeeze. The drawn pointer is left alone
     * (moving it back read as a jump); the finger keeps that small offset for the press.
     *
     * Most pinches come while still homing in on the target, so the pointer 120 ms earlier is
     * partly the user's own approach: the correction is capped at the measured squeeze size.
     * At a screen edge the pointer itself snaps back, as the back slide must start there.
     */
    private fun beginPress() {
        val now = SystemClock.uptimeMillis()
        val before = positions.positionAt(now - PRE_SQUEEZE_REWIND_MS)
        val current = positions.latest()
        val controller = laserController.get()
        var target = current
        if (before != null && current != null) {
            val dx = before.x - current.x
            val dy = before.y - current.y
            val distance = kotlin.math.hypot(dx, dy)
            val scale = if (distance > REWIND_MAX_PX) REWIND_MAX_PX / distance else 1f
            target = CursorPoint(current.x + dx * scale, current.y + dy * scale)
        }
        val edge = edgeAt(target)
        pointerFilter.resetSmoothing()
        if (edge != null && controller != null && target != null && target != current) {
            runCatching { Xp.callMethod(controller, "setPosition", target.x, target.y) }.onFailure(::recordError)
            pressOffsetX = 0f
            pressOffsetY = 0f
        } else if (target != null && current != null) {
            pressOffsetX = target.x - current.x
            pressOffsetY = target.y - current.y
        } else {
            pressOffsetX = 0f
            pressOffsetY = 0f
        }
        pressEdge = edge
        pressPoint = target
        trace {
            if (current != null && target != null && target != current) {
                "PRESS rewind ${f1(current.x)},${f1(current.y)} -> ${f1(target.x)},${f1(target.y)} " +
                    "(${if (edge != null) "snap" else "finger only"})"
            } else {
                "PRESS rewind none"
            }
        }
        trace { "PRESS edge=${edge ?: "none"}" }
        press.onPinchDown(edge)
    }

    /** Pointer samples since [from] up to [until], shifted to where the finger is. */
    private fun fingerSamples(from: Long, until: Long): List<PositionHistory.Sample> {
        val ox = pressOffsetX
        val oy = pressOffsetY
        return positions.samplesBetween(from, until).map {
            PositionHistory.Sample(it.time, CursorPoint(it.point.x + ox, it.point.y + oy))
        }
    }

    /**
     * Release while the guard still holds the finger where it went down. The pen springs on
     * release (starting ~75 ms before the key), so judge the press by the motion before that:
     * barely any and it is a tap, lifted where it went down; otherwise a quick flick, replayed
     * up to where the spring began.
     */
    private fun releaseGuarded() {
        val downAt = touch.downTime ?: return
        val origin = touch.downPoint ?: return
        val path = fingerSamples(downAt, SystemClock.uptimeMillis() - RELEASE_SPRING_MS)
        val travel = path.maxOfOrNull { kotlin.math.hypot(it.point.x - origin.x, it.point.y - origin.y) } ?: 0f
        if (travel < TAP_SLOP_PX) {
            trace { "RELEASE tap travel=${f1(travel)}" }
            touch.release("松开（点击）")
        } else {
            trace { "RELEASE flick travel=${f1(travel)} samples=${path.size}" }
            touch.releaseAlong("松开（快滑）", path)
        }
    }

    /**
     * Releasing the pinch springs the pen back, pulling the finger toward where it came from
     * right before it lifts. Gestures read that as a change of mind (the back gesture is
     * cancelled by it). Lift instead at the farthest point from the press reached in that
     * last stretch: unchanged while still moving outward, the pre-spring point if pulled back.
     */
    private fun positionBeforeReleaseSpring(): CursorPoint? {
        val downAt = touch.downTime ?: return null
        val origin = touch.downPoint ?: return null
        val now = SystemClock.uptimeMillis()
        val recent = fingerSamples(maxOf(now - RELEASE_SPRING_MS, downAt), now).map { it.point }
        return recent.maxByOrNull { kotlin.math.hypot(it.x - origin.x, it.y - origin.y) }
    }

    /** Left/right screen edge the pointer sits on, where back-gesture strips start. */
    private fun edgeAt(point: io.github.skinight.focuspenpointer.hook.pointer.CursorPoint?): PressTracker.Edge? {
        point ?: return null
        val width = cursorLocator.screenSize().first
        if (width <= 0) return null
        return when {
            point.x <= EDGE_ZONE_PX -> PressTracker.Edge.LEFT
            point.x >= width - 1 - EDGE_ZONE_PX -> PressTracker.Edge.RIGHT
            else -> null
        }
    }

    // ---- Pen keys -------------------------------------------------------------------------

    fun intercept(event: KeyEvent): Boolean {
        if (circuitOpen.get()) return false
        return try {
            interceptSafely(event)
        } catch (throwable: Throwable) {
            recordError(throwable)
            false
        }
    }

    fun shouldConsumeVendorOffLaserPinch(event: KeyEvent, interactive: Boolean): Boolean {
        if (circuitOpen.get()) return false
        return try {
            offLaser.consumeVendorPinch(event, interactive, laser.isEnabled)
        } catch (throwable: Throwable) {
            recordError(throwable)
            false
        }
    }

    private fun interceptSafely(event: KeyEvent): Boolean {
        val snapshot = config.snapshot.get()
        if (snapshot.blocksAllHooks(foreground.get())) {
            cancelInteraction("黑名单应用")
            return false
        }
        offLaser.intercept(event, laser.isEnabled)?.let { return it }
        if (!pointerActive()) {
            // A press that already started (e.g. a swipe to the launcher, whose
            // focus change briefly reports an unknown app) finishes normally on release.
            if (press.isTracking && event.scanCode == CompatProfile.SCAN_PINCH_F19) {
                return interceptMousePinch(event)
            }
            return false
        }
        return if (event.scanCode == CompatProfile.SCAN_PINCH_F19) {
            interceptMousePinch(event)
        } else {
            interceptMouseSwipe(event)
        }
    }

    private fun interceptMousePinch(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            trace {
                "KEY ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} " +
                    "lag=${SystemClock.uptimeMillis() - event.eventTime}ms"
            }
        }
        return interceptMousePinchKey(event)
    }

    private fun interceptMousePinchKey(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> when {
            event.repeatCount > 0 -> {
                if (touch.isTouching) touch.refreshWatchdog()
                press.isTracking
            }
            // No pointer or no injection endpoint: leave the pinch to Xiaomi.
            !touch.canInject() -> false
            else -> {
                beginPress()
                true
            }
        }
        KeyEvent.ACTION_UP -> if (press.isTracking) {
            press.onPinchUp()
            true
        } else {
            false
        }
        else -> false
    }

    private fun interceptMouseSwipe(event: KeyEvent): Boolean {
        val up = event.keyCode == CompatProfile.KEY_SLIDE_UP
        if (!up && event.keyCode != CompatProfile.KEY_SLIDE_DOWN) return false
        val gestureId = if (up) ConfigContract.GESTURE_SWIPE_UP else ConfigContract.GESTURE_SWIPE_DOWN
        val action = config.snapshot.get().gestureActions[gestureId]
            ?: ConfigContract.DEFAULT_GESTURE_ACTIONS.getValue(gestureId)
        if (action == ConfigContract.ACTION_SYSTEM) return false
        if (!executor.canRun(action, preferSystemCursor = false)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val success = executor.run(action)
            reporter.event(
                "${if (up) "上滑" else "下滑"} → ${GestureAction.label(action)} " +
                    if (success) "成功" else "失败",
            )
            if (!success) return false
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    // ---- Exits ----------------------------------------------------------------------------

    /**
     * A real finger or pen-tip touch while the laser is on: exit the laser and the pointer so
     * the next activation starts clean. Our own virtual finger never reaches here (it is
     * filtered by [isOwnInjectedTouch]). Blacklisted apps stay untouched.
     */
    fun onPointerEvent(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || event.pointerCount < 1) return
        val toolType = event.getToolType(0)
        val isFinger = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            toolType == MotionEvent.TOOL_TYPE_FINGER
        val isStylus = event.isFromSource(InputDevice.SOURCE_STYLUS) &&
            (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER)
        if ((!isFinger && !isStylus) || circuitOpen.get() || !laser.isEnabled) return
        val snapshot = config.snapshot.get()
        val target = foreground.get()
        if (snapshot.blocksAllHooks(target) ||
            (!snapshot.globalActionsEnabled && !snapshot.appliesTo(target))
        ) return
        laser.exit(
            reason = if (isStylus) "笔尖触屏" else "手指触屏",
            armGuard = snapshot.usesLaserMouse(target),
        )
    }

    /** Xiaomi hid the laser while it was showing our pointer: treat it as a full exit. */
    fun onLaserPresentationHidden(source: String, wasMousePresentation: Boolean) {
        if (circuitOpen.get() ||
            !wasMousePresentation ||
            !config.snapshot.get().usesLaserMouse(foreground.get())
        ) return
        val reason = "鼠标显示消失 ($source)"
        cancelInteraction(reason)
        LaserCursorRenderer.restoreAll()
        laser.exit(reason, armGuard = true)
    }

    // ---- Shared ---------------------------------------------------------------------------

    /** Abandons every in-flight gesture and lifts the virtual finger (as a cancel). */
    private fun cancelInteraction(reason: String) {
        offLaser.reset()
        press.cancel()
        touch.cancel(reason)
        pointerFilter.reset()
    }

    private fun synchronizeCursor() {
        if (!shouldReplaceLaserPresentation() || !laser.isEnabled) {
            LaserCursorRenderer.restoreAll()
        }
    }

    private fun extractPackage(windowState: Any?): String? {
        if (windowState == null) return null
        return runCatching {
            Xp.callMethod(windowState, "getOwningPackage") as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: runCatching {
                val attrs = Xp.callMethod(windowState, "getAttrs")
                Xp.getObjectField(attrs, "packageName") as? String
            }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun registerCancellationReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                cancelInteraction(intent?.action ?: "状态广播")
                reporter.event("状态机取消：${intent?.action ?: "未知广播"}")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        cancellationReceiver = receiver
        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    /**
     * Debug builds only: lets adb (which holds DUMP) toggle Xiaomi's stylus posture mode for a
     * package, bypassing the service's system-app check, to study what the pen then sends.
     *   adb shell am broadcast -a io.github.skinight.focuspenpointer.DEBUG_POSTURE \
     *     --es pkg com.miui.home --ez enable true
     */
    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.getStringExtra("pkg") ?: return
                val enable = intent.getBooleanExtra("enable", true)
                runCatching {
                    val manager = touchFilmManager.get() ?: run {
                        val type = Class.forName(TOUCH_FILM_CLASS, false, this@FocusPenRuntime.context.classLoader)
                        type.getMethod("getInstance").invoke(null)
                    }
                    Xp.callMethod(manager!!, "enableStylusPostureForApp", pkg, enable)
                }.onSuccess {
                    Xp.log("FocusPenPointer debug: stylus posture ${if (enable) "on" else "off"} for $pkg")
                }.onFailure {
                    Xp.log("FocusPenPointer debug: posture call failed: ${it.stackTraceToString()}")
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DEBUG_POSTURE_ACTION),
            android.Manifest.permission.DUMP,
            worker,
            Context.RECEIVER_EXPORTED,
        )
        debugReceiver = receiver
    }

    private fun recordError(throwable: Throwable) {
        Xp.log("FocusPenPointer: callback error: ${throwable.stackTraceToString()}")
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            if (now - errorWindowStart > 60_000L) {
                errorWindowStart = now
                errorCount = 0
            }
            errorCount += 1
            if (errorCount >= 5) {
                circuitOpen.set(true)
                cancelInteraction("异常熔断")
                reporter.event("熔断器已打开：60 秒内发生 5 次异常，后续全部放行")
                reportStatus("异常熔断：已停止拦截")
            }
        }
    }

    private fun reportStatus(message: String) {
        reporter.status(
            active = !circuitOpen.get(),
            compatible = true,
            message = message,
            laser = laser.isEnabled,
            foreground = foreground.get(),
        )
    }

    /** Debug builds: timestamped pointer/press trace in the LSPosed log for tuning. */
    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Xp.log("FocusPenPointer trace: ${SystemClock.uptimeMillis()} ${message()}")
    }

    private fun f1(value: Float): String = "%.1f".format(value)

    private companion object {
        const val UNKNOWN_FOREGROUND_GRACE_MS = 250L

        /** Measured squeeze onset before the pinch key fires (~150 ms), kept conservative. */
        const val PRE_SQUEEZE_REWIND_MS = 120L

        /** Within this many px of the left/right edge a press waits for the inward slide. */
        const val EDGE_ZONE_PX = 12f

        /** Measured: releasing the pinch tilts the pen back starting ~75 ms before the key. */
        const val RELEASE_SPRING_MS = 80L

        /**
         * After the anti-jitter lock the finger stays put this much longer, so a tap whose
         * release spring starts right as the lock ends still lifts as a tap (measured taps
         * last up to ~260 ms).
         */
        const val TAP_GUARD_MS = 120L

        /**
         * Largest pre-squeeze correction (px). From rest the squeeze moves the pointer
         * ~15–35 px; larger rewinds are mostly the user's own approach to the target.
         */
        const val REWIND_MAX_PX = 40f

        /**
         * Pen motion is ignored this long after a release: measured spring-back moves the
         * pointer ~30 px (up to 200 px) in the first 80 ms.
         */
        const val RELEASE_SETTLE_MS = 100L

        const val DEBUG_POSTURE_ACTION = "io.github.skinight.focuspenpointer.DEBUG_POSTURE"
        const val TOUCH_FILM_CLASS = "com.miui.server.input.stylus.MiuiStylusTouchFilmManager"

        /**
         * A pinch this soon after the last lift is the next tap of a double tap and goes down
         * at once (Android's double-tap timeout is 300 ms).
         */
        const val DOUBLE_TAP_WINDOW_MS = 300L

        /** Pre-spring travel below which a guarded press is a tap (system touch slop ~8 dp). */
        const val TAP_SLOP_PX = 20f
        const val DISPOSE_RESTORE_TIMEOUT_MS = 500L
    }
}
