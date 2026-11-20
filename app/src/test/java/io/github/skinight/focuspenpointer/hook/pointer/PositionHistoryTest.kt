package io.github.skinight.focuspenpointer.hook.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionHistoryTest {
    private var now = 1_000L
    private val history = PositionHistory({ now }, capacity = 8)

    @Test
    fun emptyHistoryKnowsNothing() {
        assertNull(history.latest())
        assertNull(history.positionAt(now))
    }

    @Test
    fun positionAtReturnsTheNewestSampleNotLaterThanTheTime() {
        history.record(10f, 10f) // t=1000
        now = 1_050
        history.record(20f, 20f)
        now = 1_100
        history.record(30f, 30f)

        assertEquals(CursorPoint(30f, 30f), history.latest())
        assertEquals(CursorPoint(20f, 20f), history.positionAt(1_080))
        assertEquals(CursorPoint(10f, 10f), history.positionAt(1_000))
        assertNull(history.positionAt(999))
    }

    @Test
    fun pinnedAtAnEdgeThePositionDoesNotChange() {
        // The pen keeps pushing left but Xiaomi clamps the pointer at x=1.
        repeat(10) {
            history.record(1f, 500f)
            now += 15
        }
        assertEquals(history.latest(), history.positionAt(now - 120))
    }

    @Test
    fun oldSamplesAreOverwrittenBeyondCapacity() {
        repeat(20) { i ->
            history.record(i.toFloat(), 0f)
            now += 10
        }
        assertEquals(CursorPoint(19f, 0f), history.latest())
        assertNull(history.positionAt(1_000)) // that far back is gone
    }

    @Test
    fun sinceListsRecentSamplesOldestFirst() {
        history.record(1f, 0f)
        now = 1_050
        history.record(2f, 0f)
        now = 1_100
        history.record(3f, 0f)
        assertEquals(listOf(CursorPoint(2f, 0f), CursorPoint(3f, 0f)), history.since(1_050))
        assertEquals(emptyList<CursorPoint>(), history.since(1_101))
    }

    @Test
    fun nonFinitePositionsAreIgnored() {
        history.record(Float.NaN, 1f)
        assertNull(history.latest())
    }
}
