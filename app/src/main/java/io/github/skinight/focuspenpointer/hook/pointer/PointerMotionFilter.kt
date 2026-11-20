package io.github.skinight.focuspenpointer.hook.pointer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Shapes the raw relative motion Xiaomi feeds into `LaserPointerController.move(dx, dy)`.
 * The vendor simply adds every delta to the laser position, so this is where sensitivity
 * and tremor smoothing are applied.
 *
 * - **Sensitivity** scales every delta.
 * - **Smoothing** runs a 2-D One Euro filter over the accumulated pointer path: strong
 *   filtering while the pen is nearly still (hand tremor), little lag when moving fast.
 *
 * Thread-safe.
 */
internal class PointerMotionFilter(private val clock: () -> Long) {

    data class Settings(
        /** Multiplier applied to raw deltas; 1.0 keeps Xiaomi's speed. */
        val sensitivity: Float,
        /** 0 disables filtering, 1 is the strongest smoothing. */
        val smoothing: Float,
    )

    /** What the `move` hook should do with Xiaomi's sample. */
    sealed interface Result {
        /** Replace the sample's delta. */
        data class Move(val dx: Float, val dy: Float) : Result

        /** Swallow the sample (pointer held still). */
        data object Drop : Result
    }

    // One Euro filter state over the accumulated path.
    private var hasSample = false
    private var lastSampleTime = 0L
    private var rawX = 0f
    private var rawY = 0f
    private var filteredX = 0f
    private var filteredY = 0f
    private var speed = 0f

    /** Returns the shaped delta, or null for a sample that must be dropped. */
    @Synchronized
    fun shape(dx: Float, dy: Float, settings: Settings): Pair<Float, Float>? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        val scaledX = dx * settings.sensitivity
        val scaledY = dy * settings.sensitivity
        if (settings.smoothing <= 0f) {
            hasSample = false
            return scaledX to scaledY
        }
        return smooth(scaledX, scaledY, clock(), settings.smoothing)
    }

    /** Restart filtering from the next sample (e.g. after the pointer was held still). */
    @Synchronized
    fun resetSmoothing() {
        hasSample = false
    }

    @Synchronized
    fun reset() {
        hasSample = false
    }

    private fun smooth(dx: Float, dy: Float, now: Long, smoothing: Float): Pair<Float, Float> {
        val elapsedMs = now - lastSampleTime
        if (!hasSample || elapsedMs > IDLE_RESET_MS) {
            // First sample after a pause: start a fresh path without lag.
            hasSample = true
            lastSampleTime = now
            rawX = dx
            rawY = dy
            filteredX = dx
            filteredY = dy
            speed = 0f
            return dx to dy
        }
        val dt = elapsedMs.coerceAtLeast(1L) / 1000f
        lastSampleTime = now
        rawX += dx
        rawY += dy

        val instantSpeed = hypot(dx, dy) / dt
        speed += alpha(SPEED_CUTOFF_HZ, dt) * (instantSpeed - speed)
        val a = alpha(minCutoffHz(smoothing) + BETA * speed, dt)

        val previousX = filteredX
        val previousY = filteredY
        filteredX += a * (rawX - filteredX)
        filteredY += a * (rawY - filteredY)
        return (filteredX - previousX) to (filteredY - previousY)
    }

    companion object {
        const val IDLE_RESET_MS = 120L

        /** Speed adaptivity, in Hz per px/s. */
        private const val BETA = 0.008f
        private const val SPEED_CUTOFF_HZ = 1f

        // Minimum cutoff interpolated logarithmically: light at 1%, very strong at 100%.
        private const val LIGHTEST_MIN_CUTOFF_HZ = 6f
        private const val STRONGEST_MIN_CUTOFF_HZ = 0.4f

        fun minCutoffHz(smoothing: Float): Float {
            val s = smoothing.coerceIn(0f, 1f)
            val logLight = ln(LIGHTEST_MIN_CUTOFF_HZ)
            val logStrong = ln(STRONGEST_MIN_CUTOFF_HZ)
            return exp(logLight + (logStrong - logLight) * s)
        }

        private fun alpha(cutoffHz: Float, dtSeconds: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoffHz)
            return 1f / (1f + tau / dtSeconds)
        }
    }
}
