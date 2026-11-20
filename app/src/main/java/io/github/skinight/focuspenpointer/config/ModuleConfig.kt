package io.github.skinight.focuspenpointer.config

import android.os.Bundle

data class ModuleConfig(
    val enabled: Boolean = false,
    val blacklist: Set<String> = emptySet(),
    val multiClickMs: Long = 360L,
    val globalActionsEnabled: Boolean = false,
    val tripleAction: String = ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
    val triplePackage: String = "",
    val fourHoldAction: String = ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
    val fourHoldPackage: String = "",
    val gestureActions: Map<String, String> = ConfigContract.DEFAULT_GESTURE_ACTIONS,
    val scrollAmount: String = ConfigContract.SCROLL_MEDIUM,
    /** Pointer speed relative to Xiaomi's raw laser motion, in percent. */
    val pointerSensitivity: Int = ConfigContract.DEFAULT_POINTER_SENSITIVITY,
    /** Tremor filtering strength, 0 (off) to 100 (strongest). */
    val pointerSmoothing: Int = ConfigContract.DEFAULT_POINTER_SMOOTHING,
    /** How long (ms) after a pinch the pointer ignores the squeeze jolt. */
    val pressLockMs: Int = ConfigContract.DEFAULT_PRESS_LOCK_MS,
    /** Lift Xiaomi's 8dp edge margin so the pointer can start edge gestures. */
    val reachScreenEdges: Boolean = true,
    val platformProfile: String = ConfigContract.PLATFORM_AUTO,
    val revision: Long = 0L,
) {
    /** The module works in every app except blacklisted ones. */
    fun appliesTo(packageName: String?): Boolean =
        enabled && !packageName.isNullOrBlank() && packageName !in blacklist

    fun blocksAllHooks(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName in blacklist

    /** The virtual laser becomes the stylus pointer wherever the module applies. */
    fun usesLaserMouse(packageName: String?): Boolean = appliesTo(packageName)

    /** Returns a copy with every value clamped to its supported range. */
    fun normalized(): ModuleConfig = copy(
        multiClickMs = multiClickMs.coerceIn(220L, 600L),
        pointerSensitivity = pointerSensitivity.coerceIn(
            ConfigContract.MIN_POINTER_SENSITIVITY,
            ConfigContract.MAX_POINTER_SENSITIVITY,
        ),
        pointerSmoothing = pointerSmoothing.coerceIn(0, 100),
        pressLockMs = pressLockMs.coerceIn(
            ConfigContract.MIN_PRESS_LOCK_MS,
            ConfigContract.MAX_PRESS_LOCK_MS,
        ),
        platformProfile = platformProfile.takeIf { it in ConfigContract.PLATFORM_PROFILES }
            ?: ConfigContract.PLATFORM_AUTO,
    )

    fun toBundle() = Bundle().apply {
        putBoolean(ConfigContract.KEY_ENABLED, enabled)
        putStringArrayList(ConfigContract.KEY_BLACKLIST, ArrayList(blacklist))
        putLong(ConfigContract.KEY_MULTI_CLICK_MS, multiClickMs)
        putBoolean(ConfigContract.KEY_GLOBAL_ACTIONS_ENABLED, globalActionsEnabled)
        putString(ConfigContract.KEY_TRIPLE_ACTION, tripleAction)
        putString(ConfigContract.KEY_TRIPLE_PACKAGE, triplePackage)
        putString(ConfigContract.KEY_FOUR_HOLD_ACTION, fourHoldAction)
        putString(ConfigContract.KEY_FOUR_HOLD_PACKAGE, fourHoldPackage)
        putStringArrayList(
            ConfigContract.KEY_GESTURE_ACTIONS,
            ArrayList(ConfigContract.encodeGestureActions(gestureActions)),
        )
        putString(ConfigContract.KEY_SCROLL_AMOUNT, scrollAmount)
        putInt(ConfigContract.KEY_POINTER_SENSITIVITY, pointerSensitivity)
        putInt(ConfigContract.KEY_POINTER_SMOOTHING, pointerSmoothing)
        putInt(ConfigContract.KEY_PRESS_LOCK_MS, pressLockMs)
        putBoolean(ConfigContract.KEY_REACH_SCREEN_EDGES, reachScreenEdges)
        putString(ConfigContract.KEY_PLATFORM_PROFILE, platformProfile)
        putLong(ConfigContract.KEY_REVISION, revision)
    }

    companion object {
        fun fromBundle(bundle: Bundle): ModuleConfig = ModuleConfig(
            enabled = bundle.getBoolean(ConfigContract.KEY_ENABLED, false),
            blacklist = bundle.getStringArrayList(ConfigContract.KEY_BLACKLIST)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty(),
            multiClickMs = bundle.getLong(ConfigContract.KEY_MULTI_CLICK_MS, 360L),
            globalActionsEnabled = bundle.getBoolean(
                ConfigContract.KEY_GLOBAL_ACTIONS_ENABLED,
                false,
            ),
            tripleAction = bundle.getString(
                ConfigContract.KEY_TRIPLE_ACTION,
                ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
            ) ?: ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
            triplePackage = bundle.getString(ConfigContract.KEY_TRIPLE_PACKAGE).orEmpty(),
            fourHoldAction = bundle.getString(
                ConfigContract.KEY_FOUR_HOLD_ACTION,
                ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
            ) ?: ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
            fourHoldPackage = bundle.getString(ConfigContract.KEY_FOUR_HOLD_PACKAGE).orEmpty(),
            gestureActions = ConfigContract.decodeGestureActions(
                bundle.getStringArrayList(ConfigContract.KEY_GESTURE_ACTIONS),
            ),
            scrollAmount = bundle.getString(
                ConfigContract.KEY_SCROLL_AMOUNT,
                ConfigContract.SCROLL_MEDIUM,
            ) ?: ConfigContract.SCROLL_MEDIUM,
            pointerSensitivity = bundle.getInt(
                ConfigContract.KEY_POINTER_SENSITIVITY,
                ConfigContract.DEFAULT_POINTER_SENSITIVITY,
            ),
            pointerSmoothing = bundle.getInt(
                ConfigContract.KEY_POINTER_SMOOTHING,
                ConfigContract.DEFAULT_POINTER_SMOOTHING,
            ),
            pressLockMs = bundle.getInt(
                ConfigContract.KEY_PRESS_LOCK_MS,
                ConfigContract.DEFAULT_PRESS_LOCK_MS,
            ),
            reachScreenEdges = bundle.getBoolean(ConfigContract.KEY_REACH_SCREEN_EDGES, true),
            platformProfile = bundle.getString(
                ConfigContract.KEY_PLATFORM_PROFILE,
                ConfigContract.PLATFORM_AUTO,
            ) ?: ConfigContract.PLATFORM_AUTO,
            revision = bundle.getLong(ConfigContract.KEY_REVISION, 0L),
        ).normalized()
    }
}
