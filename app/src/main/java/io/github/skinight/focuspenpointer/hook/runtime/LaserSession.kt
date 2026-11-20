package io.github.skinight.focuspenpointer.hook.runtime

/**
 * Tracks whether Xiaomi's virtual laser (and thus the stylus mouse) is active, and owns the
 * two ways the module turns it off.
 *
 * Xiaomi reports laser state through several asynchronous callbacks that can arrive late or
 * out of order. Two guards keep the mouse from being resurrected by stale callbacks:
 *
 * - **Explicit off**: after the laser was turned off, a final queued `LaserView.setPosition`
 *   is not a new activation.
 * - **Startup exit guard**: when the user exits within [STARTUP_RACE_WINDOW_MS] of the module
 *   itself enabling the laser, Xiaomi's enable pipeline may still be running. For
 *   [STARTUP_EXIT_GUARD_MS] every enable report is ignored and the vendor is told to turn the
 *   laser off again a few times.
 *
 * Pure Kotlin: Android and vendor access go through [Vendor] and [Listener].
 */
internal class LaserSession(
    private val clock: () -> Long,
    private val tasks: Tasks,
    private val vendor: Vendor,
    private val listener: Listener,
) {
    interface Tasks {
        fun post(block: () -> Unit)
        fun postDelayed(delayMs: Long, block: () -> Unit)
    }

    interface Vendor {
        /** Xiaomi's shortcut-ring entry: initializes the laser layer and pen motion. */
        fun enable(): Boolean

        /** Xiaomi's full laser exit. */
        fun turnOff(): Boolean
    }

    interface Listener {
        /** The laser became active or inactive. */
        fun onLaserChanged(enabled: Boolean, source: String)

        /** An exit was requested: stop interpreting pen input immediately (any thread). */
        fun onExitRequested(reason: String)

        /** Worker-thread cleanup before the vendor is told to exit. */
        fun onExitCleanup(reason: String)

        fun event(message: String)

        fun status(message: String)
    }

    private val lock = Any()
    private var enabled = false
    private var explicitOff = false
    private var lastModuleEnableAt = NEVER
    private var suppressEnableUntil = 0L
    private var guardActive = false
    private var exitPending = false

    val isEnabled: Boolean
        get() = synchronized(lock) { enabled }

    /** True while late enable reports from a cancelled startup must be ignored. */
    val isEnableSuppressed: Boolean
        get() = synchronized(lock) { clock() < suppressEnableUntil }

    /** A laser state report from Xiaomi (or our view hook). */
    fun onVendorState(enabled: Boolean, source: String) {
        val changed = synchronized(lock) {
            if (enabled && source == SOURCE_VIEW_POSITION && explicitOff) return
            if (enabled && clock() < suppressEnableUntil) return
            explicitOff = !enabled
            val changed = this.enabled != enabled
            this.enabled = enabled
            changed
        }
        if (changed) listener.onLaserChanged(enabled, source)
    }

    /** Turns the laser on through Xiaomi's own entry point. */
    fun requestEnable(): Boolean {
        synchronized(lock) { lastModuleEnableAt = clock() }
        val success = runCatching { vendor.enable() }.getOrDefault(false)
        if (!success) synchronized(lock) { lastModuleEnableAt = NEVER }
        return success
    }

    /**
     * The user touched the screen or Xiaomi hid the mouse presentation: exit completely so
     * the next activation starts from a clean state. [armGuard] is true when the exit may race
     * a module-initiated startup (stylus mouse in use).
     */
    fun exit(reason: String, armGuard: Boolean) {
        if (armGuard) armStartupExitGuard(reason)
        requestCompleteExit(reason)
    }

    private fun requestCompleteExit(reason: String) {
        val startCleanup = synchronized(lock) {
            explicitOff = true
            enabled = false
            if (exitPending) {
                false
            } else {
                exitPending = true
                true
            }
        }
        // Stop interpreting input before leaving the current system callback.
        listener.onExitRequested(reason)
        if (!startCleanup) return
        // Cleanup and the vendor call run on the worker to avoid re-entering dispatch.
        tasks.post {
            try {
                listener.onExitCleanup(reason)
                val success = runCatching { vendor.turnOff() }.getOrDefault(false)
                listener.event("$reason → 完整退出虚拟激光/手写笔鼠标${if (success) "成功" else "失败"}")
                listener.status(
                    if (success) "$reason，已退出虚拟激光/手写笔鼠标"
                    else "$reason，模块映射已停止；厂商退出调用失败",
                )
            } finally {
                synchronized(lock) { exitPending = false }
            }
        }
    }

    private fun armStartupExitGuard(reason: String) {
        val guardUntil = synchronized(lock) {
            val now = clock()
            val requestedAt = lastModuleEnableAt
            if (requestedAt == NEVER ||
                now < requestedAt ||
                now - requestedAt > STARTUP_RACE_WINDOW_MS ||
                guardActive
            ) {
                return
            }
            guardActive = true
            enabled = false
            suppressEnableUntil = now + STARTUP_EXIT_GUARD_MS
            suppressEnableUntil
        }
        listener.event("$reason：命中四击启动竞态保护")
        STARTUP_EXIT_RETRY_DELAYS_MS.forEach { delay ->
            tasks.postDelayed(delay) { forceVendorOffDuringGuard() }
        }
        tasks.postDelayed(STARTUP_EXIT_GUARD_MS) {
            val released = synchronized(lock) {
                if (suppressEnableUntil != guardUntil) return@synchronized false
                suppressEnableUntil = 0L
                guardActive = false
                lastModuleEnableAt = NEVER
                true
            }
            if (released) {
                listener.event("四击启动竞态保护已解除")
                listener.status("手写笔鼠标已完整退出")
            }
        }
    }

    private fun forceVendorOffDuringGuard() {
        synchronized(lock) {
            if (!guardActive || clock() >= suppressEnableUntil) return
            enabled = false
        }
        listener.onExitCleanup("四击启动竞态清理")
        runCatching { vendor.turnOff() }
    }

    companion object {
        const val SOURCE_VIEW_POSITION = "LaserView.setPosition"
        const val STARTUP_RACE_WINDOW_MS = 2_000L
        const val STARTUP_EXIT_GUARD_MS = 1_600L
        val STARTUP_EXIT_RETRY_DELAYS_MS = longArrayOf(120L, 480L, 1_100L)
        private const val NEVER = Long.MIN_VALUE
    }
}
