package io.github.skinight.focuspenpointer.hook.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserSessionTest {
    private var now = 10_000L
    private val tasks = FakeTasks()
    private val calls = mutableListOf<String>()
    private var vendorEnableSucceeds = true

    private val session = LaserSession(
        clock = { now },
        tasks = tasks,
        vendor = object : LaserSession.Vendor {
            override fun enable(): Boolean {
                calls += "vendor.enable"
                return vendorEnableSucceeds
            }

            override fun turnOff(): Boolean {
                calls += "vendor.turnOff"
                return true
            }
        },
        listener = object : LaserSession.Listener {
            override fun onLaserChanged(enabled: Boolean, source: String) {
                calls += "changed $enabled"
            }

            override fun onExitRequested(reason: String) {
                calls += "exitRequested"
            }

            override fun onExitCleanup(reason: String) {
                calls += "cleanup"
            }

            override fun event(message: String) = Unit
            override fun status(message: String) = Unit
        },
    )

    @Test
    fun vendorReportsToggleState() {
        session.onVendorState(true, "turnonvirtuallaser")
        assertTrue(session.isEnabled)
        session.onVendorState(true, "turnonvirtuallaser") // no duplicate notification
        session.onVendorState(false, "turnoffvirtuallaser")
        assertFalse(session.isEnabled)
        assertEquals(listOf("changed true", "changed false"), calls)
    }

    @Test
    fun lateViewPositionAfterOffDoesNotReactivate() {
        session.onVendorState(true, "turnonvirtuallaser")
        session.onVendorState(false, "turnoffvirtuallaser")
        session.onVendorState(true, LaserSession.SOURCE_VIEW_POSITION)
        assertFalse(session.isEnabled)
    }

    @Test
    fun exitTurnsVendorOffOnTheWorker() {
        session.onVendorState(true, "turnonvirtuallaser")
        calls.clear()

        session.exit("手指触屏", armGuard = false)
        assertFalse(session.isEnabled)
        assertEquals(listOf("exitRequested"), calls)

        tasks.runDue()
        assertEquals(listOf("exitRequested", "cleanup", "vendor.turnOff"), calls)
    }

    @Test
    fun concurrentExitsRunCleanupOnce() {
        session.exit("a", armGuard = false)
        session.exit("b", armGuard = false)
        tasks.runDue()
        assertEquals(1, calls.count { it == "vendor.turnOff" })
    }

    @Test
    fun exitRightAfterModuleEnableArmsStartupGuard() {
        assertTrue(session.requestEnable())
        now += 300
        session.exit("手指触屏", armGuard = true)

        assertTrue(session.isEnableSuppressed)
        session.onVendorState(true, "turnonvirtuallaser") // late pipeline callback
        assertFalse(session.isEnabled)

        // Retries run inside the guard window, then the guard-release task.
        tasks.runAll()
        assertEquals(4, calls.count { it == "vendor.turnOff" }) // exit + 3 guard retries
        assertFalse(session.isEnableSuppressed)
    }

    @Test
    fun guardIsNotArmedLongAfterEnable() {
        session.requestEnable()
        now += LaserSession.STARTUP_RACE_WINDOW_MS + 1
        session.exit("手指触屏", armGuard = true)
        assertFalse(session.isEnableSuppressed)
    }

    @Test
    fun failedEnableDoesNotArmGuard() {
        vendorEnableSucceeds = false
        assertFalse(session.requestEnable())
        session.exit("手指触屏", armGuard = true)
        assertFalse(session.isEnableSuppressed)
    }

    private class FakeTasks : LaserSession.Tasks {
        private val immediate = mutableListOf<() -> Unit>()
        private val delayed = mutableListOf<() -> Unit>()

        override fun post(block: () -> Unit) {
            immediate += block
        }

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            delayed += block
        }

        fun runDue() {
            val due = immediate.toList()
            immediate.clear()
            due.forEach { it() }
        }

        fun runAll() {
            runDue()
            val due = delayed.toList()
            delayed.clear()
            due.forEach { it() }
        }
    }
}
