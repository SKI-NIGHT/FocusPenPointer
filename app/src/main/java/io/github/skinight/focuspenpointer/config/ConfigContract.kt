package io.github.skinight.focuspenpointer.config

import android.net.Uri

object ConfigContract {
    const val AUTHORITY = "io.github.skinight.focuspenpointer.config"
    val URI: Uri = Uri.parse("content://$AUTHORITY/config")

    const val METHOD_GET_SNAPSHOT = "get_snapshot"
    const val METHOD_REPORT_RUNTIME = "report_runtime"
    const val METHOD_APPEND_EVENT = "append_event"
    const val METHOD_GET_RUNTIME = "get_runtime"

    const val KEY_ENABLED = "enabled"
    const val KEY_BLACKLIST = "blacklist"
    const val KEY_MULTI_CLICK_MS = "multi_click_ms"
    const val KEY_GLOBAL_ACTIONS_ENABLED = "global_actions_enabled"
    const val KEY_TRIPLE_ACTION = "triple_action"
    const val KEY_TRIPLE_PACKAGE = "triple_package"
    const val KEY_FOUR_HOLD_ACTION = "four_hold_action"
    const val KEY_FOUR_HOLD_PACKAGE = "four_hold_package"
    const val KEY_GESTURE_ACTIONS = "gesture_actions"
    const val KEY_SCROLL_AMOUNT = "scroll_amount"
    const val KEY_POINTER_SENSITIVITY = "pointer_sensitivity"
    const val KEY_POINTER_SMOOTHING = "pointer_smoothing"
    const val KEY_PRESS_LOCK_MS = "press_lock_ms"
    const val KEY_REACH_SCREEN_EDGES = "reach_screen_edges"
    const val KEY_REVISION = "revision"
    const val KEY_PLATFORM_PROFILE = "platform_profile"
    const val PLATFORM_AUTO = "auto"
    const val PLATFORM_OS3 = "os3"
    const val PLATFORM_OS4 = "os4"
    val PLATFORM_PROFILES = setOf(PLATFORM_AUTO, PLATFORM_OS3, PLATFORM_OS4)

    const val KEY_RUNTIME_ACTIVE = "runtime_active"
    const val KEY_RUNTIME_COMPATIBLE = "runtime_compatible"
    const val KEY_RUNTIME_MESSAGE = "runtime_message"
    const val KEY_RUNTIME_LASER = "runtime_laser"
    const val KEY_RUNTIME_FOREGROUND = "runtime_foreground"
    const val KEY_RUNTIME_EVENTS = "runtime_events"

    const val TRIPLE_ACTION_NONE = "none"
    const val TRIPLE_ACTION_ENABLE_LASER = "enable_laser"
    const val TRIPLE_ACTION_LAUNCH_APP = "launch_app"

    const val GESTURE_SWIPE_UP = "laser_swipe_up"
    const val GESTURE_SWIPE_DOWN = "laser_swipe_down"
    const val GESTURE_OFF_DOUBLE_TAP = "off_double_tap"
    const val GESTURE_OFF_DOUBLE_HOLD = "off_double_hold"
    const val GESTURE_OFF_SWIPE_UP = "off_swipe_up"
    const val GESTURE_OFF_SWIPE_DOWN = "off_swipe_down"

    const val ACTION_SYSTEM = "system"
    const val ACTION_CONSUME = "consume"
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"
    const val ACTION_BACK = "back"
    const val ACTION_HOME = "home"
    const val ACTION_RECENTS = "recents"
    const val ACTION_SCROLL_UP = "scroll_up"
    const val ACTION_SCROLL_DOWN = "scroll_down"

    const val SCROLL_SHORT = "short"
    const val SCROLL_MEDIUM = "medium"
    const val SCROLL_LONG = "long"

    const val MIN_POINTER_SENSITIVITY = 20
    const val MAX_POINTER_SENSITIVITY = 200
    const val DEFAULT_POINTER_SENSITIVITY = 100
    const val DEFAULT_POINTER_SMOOTHING = 40

    /** How long (ms) after a pinch the pointer ignores the squeeze jolt. */
    const val MIN_PRESS_LOCK_MS = 0
    const val MAX_PRESS_LOCK_MS = 200
    const val DEFAULT_PRESS_LOCK_MS = 150

    /** Every action a gesture mapping may hold. */
    val GESTURE_ACTION_IDS = setOf(
        ACTION_SYSTEM,
        ACTION_CONSUME,
        TRIPLE_ACTION_ENABLE_LASER,
        ACTION_VOLUME_UP,
        ACTION_VOLUME_DOWN,
        ACTION_BACK,
        ACTION_HOME,
        ACTION_RECENTS,
        ACTION_SCROLL_UP,
        ACTION_SCROLL_DOWN,
    )

    val DEFAULT_GESTURE_ACTIONS: Map<String, String> = linkedMapOf(
        GESTURE_SWIPE_UP to ACTION_SCROLL_UP,
        GESTURE_SWIPE_DOWN to ACTION_SCROLL_DOWN,
        GESTURE_OFF_DOUBLE_TAP to ACTION_SYSTEM,
        GESTURE_OFF_DOUBLE_HOLD to ACTION_SYSTEM,
        GESTURE_OFF_SWIPE_UP to ACTION_SYSTEM,
        GESTURE_OFF_SWIPE_DOWN to ACTION_SYSTEM,
    )

    fun encodeGestureActions(actions: Map<String, String>): Set<String> =
        actions.mapTo(linkedSetOf()) { (gesture, action) -> "$gesture\t$action" }

    fun decodeGestureActions(entries: Collection<String>?): Map<String, String> {
        val decoded = entries.orEmpty().mapNotNull { entry ->
            val separator = entry.indexOf('\t')
            if (separator <= 0 || separator == entry.lastIndex) null
            else entry.substring(0, separator) to entry.substring(separator + 1)
        }.toMap().filter { (gesture, action) ->
            // Drop gestures and actions that no longer exist (e.g. the removed mouse clicks)
            // so they fall back to their defaults.
            gesture in DEFAULT_GESTURE_ACTIONS && action in GESTURE_ACTION_IDS
        }
        return DEFAULT_GESTURE_ACTIONS + decoded
    }
}
