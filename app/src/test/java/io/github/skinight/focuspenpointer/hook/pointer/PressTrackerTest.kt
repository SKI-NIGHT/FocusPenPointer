package io.github.skinight.focuspenpointer.hook.pointer

import io.github.skinight.focuspenpointer.hook.pointer.PressTracker.Edge
import io.github.skinight.focuspenpointer.hook.pointer.PressTracker.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressTrackerTest {
    private val events = mutableListOf<String>()
    private var now = 1_000L
    private var lockMs = 150L
    private val scheduler = ManualScheduler()
    private val tracker = PressTracker(
        clock = { now },
        lockWindowMs = { lockMs },
        scheduler = scheduler,
        listener = object : PressTracker.Listener {
            override fun onPress() {
                events += "press"
            }

            override fun onRelease() {
                events += "release"
            }

            override fun onCancel() {
                events += "cancel"
            }
        },
    )

    // ---- anywhere on screen --------------------------------------------------------------

    @Test
    fun pinchPutsTheFingerDownImmediately() {
        tracker.onPinchDown()
        assertEquals(listOf("press"), events)
        assertTrue(tracker.isTracking)
        tracker.onPinchUp()
        assertEquals(listOf("press", "release"), events)
        assertFalse(tracker.isTracking)
    }

    @Test
    fun motionIsHeldForTheLockWindowWhateverItsSize() {
        tracker.onPinchDown()
        now += 10
        assertEquals(Motion.HOLD, tracker.onMotion(40f)) // a 40 px jolt is still just a jolt
        now += lockMs - 11
        assertEquals(Motion.HOLD, tracker.onMotion(1f))
        now += 1
        assertEquals(Motion.PASS, tracker.onMotion(1f))
    }

    @Test
    fun zeroWindowNeverHolds() {
        lockMs = 0
        tracker.onPinchDown()
        assertEquals(Motion.PASS, tracker.onMotion(1f))
    }

    @Test
    fun motionPassesWhenIdle() {
        assertEquals(Motion.PASS, tracker.onMotion(5f))
    }

    @Test
    fun cancelAbandonsTheTouch() {
        tracker.onPinchDown()
        tracker.cancel()
        tracker.onPinchUp() // the release no longer belongs to a touch
        assertEquals(listOf("press", "cancel"), events)
    }

    @Test
    fun cancelWhenIdleIsSilent() {
        tracker.cancel()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun lostReleaseIsClosedByTheNextPinch() {
        tracker.onPinchDown()
        tracker.onPinchDown()
        assertEquals(listOf("press", "cancel", "press"), events)
    }

    // ---- at a left/right edge ------------------------------------------------------------

    @Test
    fun edgePressWaitsForTheInwardSlide() {
        tracker.onPinchDown(Edge.LEFT)
        assertEquals(emptyList<String>(), events)
        assertTrue(tracker.isTracking)

        assertEquals(Motion.HOLD, tracker.onMotion(8f))
        assertEquals(emptyList<String>(), events)
        assertEquals(Motion.PASS, tracker.onMotion(9f)) // 17 px inward: the slide begins
        assertEquals(listOf("press"), events)
        assertEquals(Motion.PASS, tracker.onMotion(30f)) // no lock window once sliding
    }

    @Test
    fun rightEdgeSlidesLeft() {
        tracker.onPinchDown(Edge.RIGHT)
        assertEquals(Motion.HOLD, tracker.onMotion(20f)) // outward: still waiting
        assertEquals(Motion.PASS, tracker.onMotion(-17f))
        assertEquals(listOf("press"), events)
    }

    @Test
    fun pushingIntoTheEdgeDoesNotDelayTheSlide() {
        tracker.onPinchDown(Edge.LEFT)
        tracker.onMotion(-50f) // still pressing outward
        assertEquals(Motion.PASS, tracker.onMotion(17f))
        assertEquals(listOf("press"), events)
    }

    @Test
    fun squeezeWobbleDoesNotStartAnEdgeSlide() {
        tracker.onPinchDown(Edge.LEFT)
        repeat(5) { assertEquals(Motion.HOLD, tracker.onMotion(3f)) } // 15 px of wobble
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun edgeStartIsUnsmoothedThenNormal() {
        tracker.onPinchDown(Edge.LEFT)
        assertTrue(tracker.wantsUnsmoothedMotion)
        tracker.onMotion(20f)
        assertTrue(tracker.wantsUnsmoothedMotion)
        now += PressTracker.EDGE_BURST_MS
        assertFalse(tracker.wantsUnsmoothedMotion)
    }

    @Test
    fun ordinaryPressesKeepSmoothing() {
        tracker.onPinchDown()
        assertFalse(tracker.wantsUnsmoothedMotion)
    }

    @Test
    fun releaseOnTheEdgeIsATap() {
        tracker.onPinchDown(Edge.LEFT)
        tracker.onMotion(2f)
        tracker.onPinchUp()
        assertEquals(listOf("press", "release"), events)
        scheduler.runAll()
        assertEquals(listOf("press", "release"), events) // timeout was cancelled
    }

    @Test
    fun holdingStillOnTheEdgePutsTheFingerDownForALongPress() {
        tracker.onPinchDown(Edge.RIGHT)
        scheduler.runAll()
        assertEquals(listOf("press"), events)
        assertEquals(Motion.PASS, tracker.onMotion(-1f))
        tracker.onPinchUp()
        assertEquals(listOf("press", "release"), events)
    }

    @Test
    fun cancellingAPendingEdgePressSendsNothing() {
        tracker.onPinchDown(Edge.LEFT)
        tracker.cancel()
        scheduler.runAll()
        tracker.onPinchUp()
        assertEquals(emptyList<String>(), events)
    }

    private class ManualScheduler : DelayScheduler {
        private class Task(val block: () -> Unit) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val task = Task(block)
            tasks += task
            return Cancellable { task.cancelled = true }
        }

        fun runAll() {
            val due = tasks.toList()
            tasks.clear()
            due.filterNot { it.cancelled }.forEach { it.block() }
        }
    }
}
