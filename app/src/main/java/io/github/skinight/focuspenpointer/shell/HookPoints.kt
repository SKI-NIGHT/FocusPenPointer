package io.github.skinight.focuspenpointer.shell

/**
 * The fixed set of system_server methods the shell hooks at boot. Everything that happens
 * inside those hooks lives in a reloadable [HookModule].
 *
 * Any change to this list (adding, removing or reordering points) must bump
 * [SHELL_VERSION]: a hot reload is refused when the running shell and the new module were
 * built against different versions, because the new module would expect hooks that were
 * never installed. Such changes need a reboot.
 */
object HookPoints {
    const val SHELL_VERSION = 5

    private const val TOUCH_FILM = "com.miui.server.input.stylus.MiuiStylusTouchFilmManager"
    private const val LASER = "com.miui.server.input.stylus.laser.LaserPointerController"
    private const val LASER_VIEW = "com.miui.server.input.stylus.laser.LaserView"
    private const val POLICY = "com.android.server.policy.MiuiPhoneWindowManager"
    private const val INPUT_MANAGER = "com.android.server.input.InputManagerService"

    const val TOUCH_FILM_INIT = 0
    const val LASER_INIT = 1
    const val LASER_TURN_OFF_SHOW = 2
    const val VIEW_SET_POSITION = 3
    const val VIEW_SET_KEEP_PATH = 4
    const val VIEW_SET_MODE = 5
    const val LASER_MOVE = 6
    const val POLICY_INTERCEPT_KEY = 7
    const val TOUCH_FILM_FOCUS_CHANGED = 8
    const val TOUCH_FILM_INTERCEPT_KEY = 9
    const val TOUCH_FILM_POINTER_EVENT = 10
    const val TOUCH_FILM_ENABLE_LASER = 11
    const val TOUCH_FILM_TURN_ON_LASER = 12
    const val TOUCH_FILM_TURN_OFF_LASER = 13
    const val LASER_ENABLE_LASER = 14
    const val LASER_TURN_ON_LASER = 15
    const val LASER_TURN_OFF_LASER = 16
    const val LASER_SAFE_MARGIN = 17
    const val INPUT_POINTER_CAPTURE = 18
    const val INPUT_INTERCEPT_DISPATCH = 19

    /** [method] null means all constructors. */
    class Point(val id: Int, val className: String, val method: String?)

    val ALL = listOf(
        Point(TOUCH_FILM_INIT, TOUCH_FILM, null),
        Point(LASER_INIT, LASER, null),
        Point(LASER_TURN_OFF_SHOW, LASER, "turnoffvirtuallasershow"),
        Point(VIEW_SET_POSITION, LASER_VIEW, "setPosition"),
        Point(VIEW_SET_KEEP_PATH, LASER_VIEW, "setKeepPath"),
        Point(VIEW_SET_MODE, LASER_VIEW, "setMode"),
        Point(LASER_MOVE, LASER, "move"),
        Point(POLICY_INTERCEPT_KEY, POLICY, "interceptKeyBeforeQueueing"),
        Point(TOUCH_FILM_FOCUS_CHANGED, TOUCH_FILM, "onDefaultDisplayFocusChangedLw"),
        Point(TOUCH_FILM_INTERCEPT_KEY, TOUCH_FILM, "interceptKeyBeforeQueueing"),
        Point(TOUCH_FILM_POINTER_EVENT, TOUCH_FILM, "onPointerEvent"),
        Point(TOUCH_FILM_ENABLE_LASER, TOUCH_FILM, "enableVirtualLaser"),
        Point(TOUCH_FILM_TURN_ON_LASER, TOUCH_FILM, "turnonvirtuallaser"),
        Point(TOUCH_FILM_TURN_OFF_LASER, TOUCH_FILM, "turnoffvirtuallaser"),
        Point(LASER_ENABLE_LASER, LASER, "enableVirtualLaser"),
        Point(LASER_TURN_ON_LASER, LASER, "turnonvirtuallaser"),
        Point(LASER_TURN_OFF_LASER, LASER, "turnoffvirtuallaser"),
        Point(LASER_SAFE_MARGIN, LASER, "getLaserSafeMargin"),
        Point(INPUT_POINTER_CAPTURE, INPUT_MANAGER, "requestPointerCapture"),
        Point(INPUT_INTERCEPT_DISPATCH, INPUT_MANAGER, "interceptKeyBeforeDispatching"),
    )
}

/**
 * Implemented by the reloadable module. Loaded by name so the shell never links against a
 * specific build of it.
 */
interface HookModule {
    /** The [HookPoints.SHELL_VERSION] this module was compiled against. */
    fun shellVersion(): Int

    /** Called once hooks are installed (boot) or the module replaced a previous one. */
    fun attach()

    fun beforeHook(id: Int, param: HookParam)

    fun afterHook(id: Int, param: HookParam)

    /** Releases everything this module holds: threads, receivers, overlays, held buttons. */
    fun dispose()
}
