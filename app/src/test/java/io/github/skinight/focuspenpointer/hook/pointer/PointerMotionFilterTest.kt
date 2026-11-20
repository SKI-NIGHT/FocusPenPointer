package io.github.skinight.focuspenpointer.hook.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PointerMotionFilterTest {
    private var now = 10_000L
    private val filter = PointerMotionFilter { now }

    private fun settings(sensitivity: Float = 1f, smoothing: Float = 0f) =
        PointerMotionFilter.Settings(sensitivity, smoothing)

    private fun shape(dx: Float, dy: Float, s: PointerMotionFilter.Settings) =
        requireNotNull(filter.shape(dx, dy, s))

    @Test
    fun sensitivityScalesDeltas() {
        val (x, y) = shape(10f, -4f, settings(sensitivity = 0.5f))
        assertEquals(5f, x, 1e-4f)
        assertEquals(-2f, y, 1e-4f)
    }

    @Test
    fun nonFiniteMotionIsDropped() {
        assertNull(filter.shape(Float.NaN, 1f, settings()))
    }

    @Test
    fun firstSampleAfterIdleIsNotDelayed() {
        assertEquals(8f, shape(8f, 0f, settings(smoothing = 1f)).first, 1e-4f)
    }

    @Test
    fun smoothingDampensTremor() {
        val s = settings(smoothing = 0.6f)
        shape(0f, 0f, s)
        var rawTravel = 0f
        var outTravel = 0f
        repeat(40) { i ->
            now += 8
            val dx = if (i % 2 == 0) 3f else -3f // tremor: alternating jitter
            rawTravel += abs(dx)
            outTravel += abs(shape(dx, 0f, s).first)
        }
        assertTrue("tremor should be reduced: $outTravel vs $rawTravel", outTravel < rawTravel * 0.5f)
    }

    @Test
    fun smoothingTracksFastDeliberateMotion() {
        val s = settings(smoothing = 0.6f)
        shape(0f, 0f, s)
        var outTravel = 0f
        repeat(30) {
            now += 8
            outTravel += shape(20f, 0f, s).first // ~2500 px/s sweep
        }
        assertTrue("fast motion should mostly pass: $outTravel of 600", outTravel > 600f * 0.8f)
    }

    @Test
    fun minCutoffIsMonotonicInSmoothing() {
        val light = PointerMotionFilter.minCutoffHz(0.01f)
        val mid = PointerMotionFilter.minCutoffHz(0.5f)
        val strong = PointerMotionFilter.minCutoffHz(1f)
        assertTrue(light > mid && mid > strong)
    }
}
