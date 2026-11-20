package io.github.skinight.focuspenpointer.hook.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchControllerTest {
    private val cursor = FakeCursor()
    private val sink = RecordingSink()
    private val scheduler = FakeScheduler()
    private var now = 1_000L
    private val events = mutableListOf<String>()
    private val controller = TouchController(
        cursor = cursor,
        sink = sink,
        scheduler = scheduler,
        clock = { now },
        onEvent = events::add,
    )

    @Test
    fun tapIsDownThenUpAtThePointer() {
        cursor.laser = CursorPoint(200f, 300f)

        assertTrue(controller.press())
        assertTrue(controller.isTouching)
        now += 40
        controller.release("松开")

        assertFalse(controller.isTouching)
        assertEquals(listOf("down (200.0,300.0) @1000", "up (200.0,300.0) down=1000 t=1040"), sink.calls)
        assertEquals(listOf("触摸按下", "松开 → 触摸抬起"), events)
    }

    @Test
    fun fingerFollowsThePointerWhileDown() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        now += 20
        controller.onPointerMoved(200f, 260f)
        now += 20
        controller.release("松开")

        assertEquals(
            listOf(
                "down (200.0,200.0) @1000",
                "move (200.0,260.0) down=1000 t=1020",
                "up (200.0,260.0) down=1000 t=1040",
            ),
            sink.calls,
        )
    }

    @Test
    fun guardHoldsTheFingerSoAQuickTapStaysATap() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 270)
        now += 160
        controller.onPointerMoved(150f, 200f) // release spring right after the lock
        assertTrue(controller.isGuarding)
        now += 5
        controller.release("松开")

        assertEquals(
            listOf("down (200.0,200.0) @1000", "up (200.0,200.0) down=1000 t=1165"),
            sink.calls,
        )
    }

    @Test
    fun pressCanGoDownAtAGivenPointWhileThePointerIsElsewhere() {
        cursor.laser = CursorPoint(300f, 300f) // still gliding back
        controller.press(at = CursorPoint(250f, 280f))
        controller.release("松开")

        assertEquals("down (250.0,280.0) @1000", sink.calls.first())
        assertEquals("up (250.0,280.0) down=1000 t=1001", sink.calls.last())
    }

    @Test
    fun deferredPressTapsInstantlyOnRelease() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 270, deferDown = true)
        assertTrue(controller.isTouching)
        assertTrue(sink.calls.isEmpty())
        now += 150
        controller.onPointerMoved(150f, 200f)
        controller.release("松开")

        assertEquals(
            listOf("down (200.0,200.0) @1150", "up (200.0,200.0) down=1150 t=1151"),
            sink.calls,
        )
        assertEquals(1150L, controller.lastLiftTime)
        assertTrue(controller.isOwnTouch(1150))
    }

    @Test
    fun deferredPressGoesDownBackdatedWhenTheGuardRunsOut() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 270, deferDown = true)
        now += 270
        scheduler.takeAll().last()() // the guard timer
        assertEquals(listOf("down (200.0,200.0) @1000"), sink.calls)
        assertFalse(controller.isGuarding)

        now += 30
        controller.onPointerMoved(260f, 200f)
        now += 500
        controller.release("松开")
        assertEquals(
            listOf("move (260.0,200.0) down=1000 t=1300", "up (260.0,200.0) down=1000 t=1800"),
            sink.calls.drop(1),
        )
    }

    @Test
    fun deferredPressMovedAfterTheGuardGoesDownFirst() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 100, deferDown = true)
        now += 120
        controller.onPointerMoved(300f, 200f)

        assertEquals(
            listOf("down (200.0,200.0) @1000", "move (300.0,200.0) down=1000 t=1120"),
            sink.calls,
        )
    }

    @Test
    fun cancellingADeferredPressSendsNothing() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 270, deferDown = true)
        controller.cancel("中止")

        assertTrue(sink.calls.isEmpty())
        assertFalse(controller.isTouching)
    }

    @Test
    fun fingerCatchesUpWhenTheGuardEnds() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 100)
        now += 50
        controller.onPointerMoved(240f, 200f)
        now += 60
        controller.onPointerMoved(300f, 200f)

        assertFalse(controller.isGuarding)
        assertEquals("move (300.0,200.0) down=1000 t=1110", sink.calls.last())
    }

    @Test
    fun guardedFlickIsReplayedWithItsOwnTimes() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press(guardMs = 270)
        now += 200
        controller.releaseAlong(
            "松开",
            listOf(
                PositionHistory.Sample(1160, CursorPoint(250f, 200f)),
                PositionHistory.Sample(1170, CursorPoint(320f, 200f)),
            ),
        )

        assertEquals(
            listOf(
                "move (250.0,200.0) down=1000 t=1160",
                "move (320.0,200.0) down=1000 t=1170",
                "up (320.0,200.0) down=1000 t=1171",
            ),
            sink.calls.drop(1),
        )
        assertFalse(controller.isTouching)
    }

    @Test
    fun releaseCanLiftWhereThePointerWasBeforeTheSpringBack() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        now += 20
        controller.onPointerMoved(500f, 200f) // swipe
        now += 20
        controller.onPointerMoved(460f, 210f) // release spring-back
        now += 20
        controller.release("松开", at = CursorPoint(500f, 200f))

        assertEquals(
            listOf(
                "move (500.0,200.0) down=1000 t=1060",
                "up (500.0,200.0) down=1000 t=1060",
            ),
            sink.calls.takeLast(2),
        )
    }

    @Test
    fun releaseAtTheSamePointSendsNoExtraMove() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        controller.release("松开", at = CursorPoint(200f, 200f))
        assertEquals(listOf("down (200.0,200.0) @1000", "up (200.0,200.0) down=1000 t=1001"), sink.calls)
    }

    @Test
    fun movesInsideThrottleWindowAreDropped() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        now += TouchController.MIN_MOVE_INTERVAL_MS - 1
        controller.onPointerMoved(201f, 201f)
        assertEquals(1, sink.calls.size)
    }

    @Test
    fun movesWithoutTouchOrOffScreenAreIgnored() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.onPointerMoved(300f, 300f)
        controller.press()
        now += 50
        controller.onPointerMoved(-1f, 5f)
        controller.onPointerMoved(Float.NaN, 5f)
        assertEquals(listOf("down (200.0,200.0) @1000"), sink.calls)
    }

    @Test
    fun cancelSendsActionCancelNotUp() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        controller.cancel("前台应用切换")
        assertEquals("cancel (200.0,200.0) down=1000 t=1001", sink.calls.last())
        assertFalse(controller.isTouching)
    }

    @Test
    fun releaseOrCancelWithoutTouchIsSilent() {
        controller.release("松开")
        controller.cancel("取消")
        assertEquals(emptyList<String>(), sink.calls)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun pressWithoutAnyPositionFails() {
        assertFalse(controller.press())
        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun failedDownIsCancelledAndNotKept() {
        cursor.laser = CursorPoint(200f, 200f)
        sink.downSucceeds = false

        assertFalse(controller.press())
        assertFalse(controller.isTouching)
        assertEquals("cancel (200.0,200.0) down=1000 t=1001", sink.calls.last())
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun aStaleTouchIsCancelledBeforeTheNextPress() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        now += 100
        controller.press() // release was lost

        assertEquals(
            listOf("down (200.0,200.0) @1000", "cancel (200.0,200.0) down=1000 t=1100", "down (200.0,200.0) @1100"),
            sink.calls,
        )
    }

    @Test
    fun touchStartsExactlyAtThePointerEvenAtAnEdge() {
        cursor.laser = CursorPoint(500f, 998f)
        controller.press()
        assertEquals("down (500.0,998.0) @1000", sink.calls.first())
    }

    @Test
    fun ownTouchesAreRecognisedByDownTime() {
        cursor.laser = CursorPoint(200f, 200f)
        assertFalse(controller.isOwnTouch(1_000L))
        controller.press()
        controller.release("松开")
        assertTrue(controller.isOwnTouch(1_000L))
        assertFalse(controller.isOwnTouch(999L))
    }

    @Test
    fun watchdogLiftsAStuckFinger() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        scheduler.runAll()
        assertFalse(controller.isTouching)
        assertTrue(sink.calls.last().startsWith("up"))
        assertEquals("触摸 60 秒无变化，已强制抬起", events.last())
    }

    @Test
    fun watchdogFromAnEndedTouchDoesNotLiftANewOne() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        val stale = scheduler.takeAll()
        controller.release("松开")
        controller.press()
        stale.forEach { it() }
        assertTrue(controller.isTouching)
    }

    @Test
    fun movementReschedulesWatchdog() {
        cursor.laser = CursorPoint(200f, 200f)
        controller.press()
        now += 50
        controller.onPointerMoved(210f, 210f)
        assertEquals(1, scheduler.pending)
        assertEquals(TouchController.IDLE_TIMEOUT_MS, scheduler.lastDelay)
    }

    @Test
    fun scrollUsesSystemCursorOnlyWhenRequested() {
        cursor.laser = CursorPoint(1f, 1f)
        cursor.system = CursorPoint(5f, 5f)
        controller.scroll(3f, preferSystemCursor = true)
        controller.scroll(3f, preferSystemCursor = false)
        assertEquals(listOf("scroll 3.0 (5.0,5.0)", "scroll 3.0 (1.0,1.0)"), sink.calls)
    }

    @Test
    fun scrollIgnoresZeroAndNonFiniteAmounts() {
        cursor.laser = CursorPoint(1f, 1f)
        assertFalse(controller.scroll(0f, preferSystemCursor = false))
        assertFalse(controller.scroll(Float.NaN, preferSystemCursor = false))
        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun canInjectRequiresPositionAndEndpoint() {
        assertFalse(controller.canInject())
        cursor.laser = CursorPoint(1f, 1f)
        assertTrue(controller.canInject())
        sink.available = false
        assertFalse(controller.canInject())
    }

    private class FakeCursor : CursorLocator {
        var laser: CursorPoint? = null
        var system: CursorPoint? = null
        override fun laserPosition() = laser
        override fun systemCursorPosition() = system
        override fun isOnScreen(x: Float, y: Float) =
            x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x < 1000f && y < 1000f

        override fun screenSize() = 1000 to 1000
    }

    private class RecordingSink : TouchSink {
        val calls = mutableListOf<String>()
        var available = true
        var downSucceeds = true

        override fun canInject() = available

        override fun touchDown(point: CursorPoint, downTime: Long): Boolean {
            calls += "down ${point.fmt()} @$downTime"
            return downSucceeds
        }

        override fun touchMove(point: CursorPoint, downTime: Long, eventTime: Long): Boolean {
            calls += "move ${point.fmt()} down=$downTime t=$eventTime"
            return true
        }

        override fun touchUp(point: CursorPoint, downTime: Long, eventTime: Long): Boolean {
            calls += "up ${point.fmt()} down=$downTime t=$eventTime"
            return true
        }

        override fun touchCancel(point: CursorPoint, downTime: Long, eventTime: Long): Boolean {
            calls += "cancel ${point.fmt()} down=$downTime t=$eventTime"
            return true
        }

        override fun scroll(point: CursorPoint, amount: Float, eventTime: Long): Boolean {
            calls += "scroll $amount ${point.fmt()}"
            return true
        }

        private fun CursorPoint.fmt() = "($x,$y)"
    }

    private class FakeScheduler : DelayScheduler {
        private class Task(val block: () -> Unit) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()
        var lastDelay = -1L

        val pending: Int
            get() = tasks.count { !it.cancelled }

        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            lastDelay = delayMs
            val task = Task(block)
            tasks += task
            return Cancellable { task.cancelled = true }
        }

        fun runAll() {
            takeAll().forEach { it() }
        }

        fun takeAll(): List<() -> Unit> {
            val live = tasks.filterNot { it.cancelled }.map { it.block }
            tasks.clear()
            return live
        }
    }
}
