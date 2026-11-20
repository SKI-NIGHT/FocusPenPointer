package io.github.skinight.focuspenpointer.hook.runtime

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import io.github.skinight.focuspenpointer.hook.GestureAction
import io.github.skinight.focuspenpointer.hook.InputInjector
import io.github.skinight.focuspenpointer.hook.pointer.TouchController

/** Runs the actions a gesture can be mapped to, and tells whether one can run right now. */
internal class ActionExecutor(
    private val context: Context,
    private val injector: InputInjector,
    private val touch: TouchController,
    private val laser: LaserSession,
    private val scrollMagnitude: () -> Float,
) {
    /** Whether [actionId] has what it needs (an injection endpoint, a pointer position). */
    fun canRun(actionId: String, preferSystemCursor: Boolean): Boolean = when {
        GestureAction.requiresPointer(actionId) -> touch.canInject(preferSystemCursor)
        GestureAction.requiresKeyInjection(actionId) -> injector.canInjectKey()
        else -> true
    }

    /** Injects a ready-made input event (e.g. a cleaned-up copy of a pen key). */
    fun injectEvent(event: android.view.InputEvent): Boolean = injector.inject(event)

    /** Runs a one-shot action. */
    fun run(actionId: String): Boolean = when (GestureAction.fromId(actionId)) {
        GestureAction.CONSUME -> true
        GestureAction.ENABLE_LASER -> laser.requestEnable()
        GestureAction.SCROLL_UP -> scroll(scrollMagnitude())
        GestureAction.SCROLL_DOWN -> scroll(-scrollMagnitude())
        GestureAction.VOLUME_UP -> injector.injectKeyClick(KeyEvent.KEYCODE_VOLUME_UP)
        GestureAction.VOLUME_DOWN -> injector.injectKeyClick(KeyEvent.KEYCODE_VOLUME_DOWN)
        GestureAction.BACK -> injector.injectKeyClick(KeyEvent.KEYCODE_BACK)
        GestureAction.HOME -> injector.injectKeyClick(KeyEvent.KEYCODE_HOME)
        GestureAction.RECENTS -> injector.injectKeyClick(KeyEvent.KEYCODE_APP_SWITCH)
        GestureAction.SYSTEM, null -> false
    }

    fun launchApp(packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isEmpty()) return false
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(target)
                ?: error("No launcher activity for $target")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
    }

    // With the laser off there is no laser dot, so wheel events go to the system pointer.
    private fun scroll(amount: Float): Boolean =
        touch.scroll(amount, preferSystemCursor = !laser.isEnabled)
}
