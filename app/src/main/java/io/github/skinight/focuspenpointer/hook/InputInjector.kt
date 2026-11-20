package io.github.skinight.focuspenpointer.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import io.github.skinight.focuspenpointer.config.ConfigContract
import java.lang.reflect.Method

/**
 * Resolves the platform input-injection endpoint (InputManager on HyperOS 3,
 * InputManagerGlobal on HyperOS 4) and injects key events through it. Touch events are
 * built by [io.github.skinight.focuspenpointer.hook.pointer.InjectedTouchSink] and injected
 * via [inject].
 */
@SuppressLint("BlockedPrivateApi")
internal class InputInjector(
    private val systemServerClassLoader: ClassLoader,
    private val platformSelection: () -> String,
) {
    private var endpointResolutionError: String? = null
    private val os3Endpoint = resolveInjectionEndpoint("android.hardware.input.InputManager")
    private val os4Endpoint = resolveInjectionEndpoint("android.hardware.input.InputManagerGlobal")

    fun canInjectKey(): Boolean = selectedEndpoint() != null

    fun capabilitySummary(cursorCapabilities: String): String {
        val selected = CompatProfile.effectivePlatform(platformSelection())
        val endpoint = selectedEndpoint()
        return "profile=$selected, inject=${endpoint?.className ?: "unavailable"}, " +
            cursorCapabilities +
            (endpointResolutionError?.let { ", reason=$it" } ?: "")
    }

    fun injectKeyClick(keyCode: Int): Boolean {
        if (!canInjectKey()) return false
        val downTime = SystemClock.uptimeMillis()
        val down = keyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode)
        val up = keyEvent(downTime, downTime + 1, KeyEvent.ACTION_UP, keyCode)
        val downSucceeded = inject(down)
        val upSucceeded = inject(up)
        return downSucceeded && upSucceeded
    }

    fun inject(event: InputEvent): Boolean = runCatching {
        val endpoint = selectedEndpoint() ?: return false
        endpoint.method.invoke(
            endpoint.instance,
            event,
            INJECT_INPUT_EVENT_MODE_ASYNC,
        ) as? Boolean
    }.getOrNull() == true

    private fun keyEvent(downTime: Long, eventTime: Long, action: Int, keyCode: Int) =
        KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            0,
            VIRTUAL_KEYBOARD_DEVICE_ID,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD,
        )

    private fun selectedEndpoint(): InjectionEndpoint? = when (
        CompatProfile.effectivePlatform(platformSelection())
    ) {
        ConfigContract.PLATFORM_OS3 -> os3Endpoint ?: os4Endpoint
        ConfigContract.PLATFORM_OS4 -> os4Endpoint ?: os3Endpoint
        else -> os4Endpoint ?: os3Endpoint
    }

    private fun resolveInjectionEndpoint(className: String): InjectionEndpoint? =
        runCatching {
            val clazz = Class.forName(className, false, systemServerClassLoader)
            val instance = clazz.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null) ?: error("${clazz.simpleName}.getInstance() returned null")
            val method = clazz.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            InjectionEndpoint(clazz.name, instance, method)
        }.onFailure { error ->
            endpointResolutionError = listOfNotNull(
                endpointResolutionError,
                "$className: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}",
            ).joinToString("; ")
        }.getOrNull()

    private data class InjectionEndpoint(
        val className: String,
        val instance: Any,
        val method: Method,
    )

    private companion object {
        const val VIRTUAL_KEYBOARD_DEVICE_ID = -1
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
