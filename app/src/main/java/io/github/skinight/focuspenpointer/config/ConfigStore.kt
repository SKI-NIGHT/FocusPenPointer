package io.github.skinight.focuspenpointer.config

import android.content.Context

class ConfigStore(context: Context) {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val preferences = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (LEGACY_KEYS.any(preferences::contains)) {
            preferences.edit().apply { LEGACY_KEYS.forEach(::remove) }.apply()
        }
    }

    fun read(): ModuleConfig = ModuleConfig(
        enabled = preferences.getBoolean(ConfigContract.KEY_ENABLED, false),
        blacklist = preferences.getStringSet(ConfigContract.KEY_BLACKLIST, emptySet())
            ?.toSet()
            .orEmpty(),
        multiClickMs = preferences.getLong(ConfigContract.KEY_MULTI_CLICK_MS, 360L),
        globalActionsEnabled = preferences.getBoolean(
            ConfigContract.KEY_GLOBAL_ACTIONS_ENABLED,
            false,
        ),
        tripleAction = preferences.getString(
            ConfigContract.KEY_TRIPLE_ACTION,
            ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
        ) ?: ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
        triplePackage = preferences.getString(
            ConfigContract.KEY_TRIPLE_PACKAGE,
            "",
        ).orEmpty(),
        fourHoldAction = preferences.getString(
            ConfigContract.KEY_FOUR_HOLD_ACTION,
            ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
        ) ?: ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
        fourHoldPackage = preferences.getString(
            ConfigContract.KEY_FOUR_HOLD_PACKAGE,
            "",
        ).orEmpty(),
        gestureActions = ConfigContract.decodeGestureActions(
            preferences.getStringSet(ConfigContract.KEY_GESTURE_ACTIONS, emptySet()),
        ),
        scrollAmount = preferences.getString(
            ConfigContract.KEY_SCROLL_AMOUNT,
            ConfigContract.SCROLL_MEDIUM,
        ) ?: ConfigContract.SCROLL_MEDIUM,
        pointerSensitivity = preferences.getInt(
            ConfigContract.KEY_POINTER_SENSITIVITY,
            ConfigContract.DEFAULT_POINTER_SENSITIVITY,
        ),
        pointerSmoothing = preferences.getInt(
            ConfigContract.KEY_POINTER_SMOOTHING,
            ConfigContract.DEFAULT_POINTER_SMOOTHING,
        ),
        pressLockMs = preferences.getInt(
            ConfigContract.KEY_PRESS_LOCK_MS,
            ConfigContract.DEFAULT_PRESS_LOCK_MS,
        ),
        reachScreenEdges = preferences.getBoolean(ConfigContract.KEY_REACH_SCREEN_EDGES, true),
        platformProfile = preferences.getString(
            ConfigContract.KEY_PLATFORM_PROFILE,
            ConfigContract.PLATFORM_AUTO,
        ) ?: ConfigContract.PLATFORM_AUTO,
        revision = preferences.getLong(ConfigContract.KEY_REVISION, 0L),
    ).normalized()

    fun update(transform: (ModuleConfig) -> ModuleConfig): ModuleConfig {
        val next = transform(read()).normalized().copy(revision = System.currentTimeMillis())
        preferences.edit()
            .apply { LEGACY_KEYS.forEach(::remove) }
            .putBoolean(ConfigContract.KEY_ENABLED, next.enabled)
            .putStringSet(ConfigContract.KEY_BLACKLIST, next.blacklist)
            .putLong(ConfigContract.KEY_MULTI_CLICK_MS, next.multiClickMs)
            .putBoolean(
                ConfigContract.KEY_GLOBAL_ACTIONS_ENABLED,
                next.globalActionsEnabled,
            )
            .putString(ConfigContract.KEY_TRIPLE_ACTION, next.tripleAction)
            .putString(ConfigContract.KEY_TRIPLE_PACKAGE, next.triplePackage)
            .putString(ConfigContract.KEY_FOUR_HOLD_ACTION, next.fourHoldAction)
            .putString(ConfigContract.KEY_FOUR_HOLD_PACKAGE, next.fourHoldPackage)
            .putStringSet(
                ConfigContract.KEY_GESTURE_ACTIONS,
                ConfigContract.encodeGestureActions(next.gestureActions),
            )
            .putString(ConfigContract.KEY_SCROLL_AMOUNT, next.scrollAmount)
            .putInt(ConfigContract.KEY_POINTER_SENSITIVITY, next.pointerSensitivity)
            .putInt(ConfigContract.KEY_POINTER_SMOOTHING, next.pointerSmoothing)
            .putInt(ConfigContract.KEY_PRESS_LOCK_MS, next.pressLockMs)
            .putBoolean(ConfigContract.KEY_REACH_SCREEN_EDGES, next.reachScreenEdges)
            .putString(ConfigContract.KEY_PLATFORM_PROFILE, next.platformProfile)
            .putLong(ConfigContract.KEY_REVISION, next.revision)
            .apply()
        storageContext.contentResolver.notifyChange(ConfigContract.URI, null)
        return next
    }

    companion object {
        private const val PREFS = "module_config"

        /** Preferences of removed features, purged on startup and on every save. */
        private val LEGACY_KEYS = listOf(
            "safe_mode",
            "volume_mode",
            "charging_guard_enabled",
            // Whitelist-based scoping, replaced by "all apps except blacklist".
            "work_mode",
            "whitelist",
            "laser_mouse_apps",
            // Laser brush color customization.
            "laser_brush_color",
            "laser_brush_color_mode",
            "laser_gradient_colors",
            "laser_flashing_colors",
            "laser_marquee_speed",
            "laser_marquee_speed_tenths",
            "laser_marquee_random_speed",
            // Replaced by the anti-jitter range of the mouse-button model.
            "pinch_stabilization",
            // The pointer is a plain finger now; there is no mouse drag style.
            "drag_style",
            // The distance threshold misread the squeeze jolt; replaced by a time window.
            "press_slop",
        )
    }
}
