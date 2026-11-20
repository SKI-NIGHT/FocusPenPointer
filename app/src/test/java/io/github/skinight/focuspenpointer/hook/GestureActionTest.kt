package io.github.skinight.focuspenpointer.hook

import io.github.skinight.focuspenpointer.config.ConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureActionTest {
    private val pointerIds = listOf(
        ConfigContract.ACTION_SCROLL_UP,
        ConfigContract.ACTION_SCROLL_DOWN,
    )
    private val keyIds = listOf(
        ConfigContract.ACTION_VOLUME_UP,
        ConfigContract.ACTION_VOLUME_DOWN,
        ConfigContract.ACTION_BACK,
        ConfigContract.ACTION_HOME,
        ConfigContract.ACTION_RECENTS,
    )
    private val passiveIds = listOf(
        ConfigContract.ACTION_SYSTEM,
        ConfigContract.ACTION_CONSUME,
        ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
    )

    @Test
    fun everyIdIsUniqueAndRoundTrips() {
        val ids = GestureAction.entries.map(GestureAction::id)
        assertEquals(ids.size, ids.toSet().size)
        GestureAction.entries.forEach { assertEquals(it, GestureAction.fromId(it.id)) }
        assertEquals((pointerIds + keyIds + passiveIds).toSet(), ids.toSet())
    }

    @Test
    fun capabilityRequirementsMatchLegacyRules() {
        pointerIds.forEach {
            assertTrue(it, GestureAction.requiresPointer(it))
            assertFalse(it, GestureAction.requiresKeyInjection(it))
        }
        keyIds.forEach {
            assertFalse(it, GestureAction.requiresPointer(it))
            assertTrue(it, GestureAction.requiresKeyInjection(it))
        }
        passiveIds.forEach {
            assertFalse(it, GestureAction.requiresPointer(it))
            assertFalse(it, GestureAction.requiresKeyInjection(it))
        }
    }

    @Test
    fun unknownIdsKeepLegacyFallbacks() {
        listOf("bogus", ConfigContract.TRIPLE_ACTION_NONE, ConfigContract.TRIPLE_ACTION_LAUNCH_APP)
            .forEach {
                assertNull(GestureAction.fromId(it))
                assertFalse(GestureAction.requiresPointer(it))
                assertTrue(GestureAction.requiresKeyInjection(it))
                assertEquals("未知动作", GestureAction.label(it))
            }
    }

    @Test
    fun removedMouseActionsAreUnknown() {
        listOf("mouse_left_click", "mouse_left_hold", "mouse_right_click", "mouse_right_hold")
            .forEach { assertNull(GestureAction.fromId(it)) }
    }
}
