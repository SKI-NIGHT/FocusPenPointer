package io.github.skinight.focuspenpointer.shell

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * The only door from reloadable code to the Xposed API.
 *
 * LSPosed renames the Xposed API package inside the module dex it loads at boot, so a dex
 * we load ourselves during a hot reload cannot link against `de.robv.android.xposed.*`.
 * The shell is part of the boot-loaded dex, so routing every call through here keeps the
 * reloadable packages free of Xposed references. Never import Xposed outside `shell`.
 */
object Xp {
    fun log(message: String) = XposedBridge.log(message)

    fun findClass(name: String, classLoader: ClassLoader): Class<*> =
        XposedHelpers.findClass(name, classLoader)

    fun getObjectField(target: Any?, field: String): Any? =
        XposedHelpers.getObjectField(target, field)

    fun getIntField(target: Any?, field: String): Int = XposedHelpers.getIntField(target, field)

    fun getBooleanField(target: Any?, field: String): Boolean =
        XposedHelpers.getBooleanField(target, field)

    fun getStaticObjectField(clazz: Class<*>, field: String): Any? =
        XposedHelpers.getStaticObjectField(clazz, field)

    fun callMethod(target: Any?, method: String, vararg args: Any?): Any? =
        XposedHelpers.callMethod(target, method, *args)

    fun getAdditionalInstanceField(target: Any?, key: String): Any? =
        XposedHelpers.getAdditionalInstanceField(target, key)

    fun setAdditionalInstanceField(target: Any?, key: String, value: Any?): Any? =
        XposedHelpers.setAdditionalInstanceField(target, key, value)

    fun removeAdditionalInstanceField(target: Any?, key: String): Any? =
        XposedHelpers.removeAdditionalInstanceField(target, key)
}

/** Xposed-free view of a hooked call, handed to [HookModule]. */
interface HookParam {
    val args: Array<Any?>

    /** Every hook point is an instance method or constructor, so this is never null. */
    val thisObject: Any
    val throwable: Throwable?

    /** Setting this (even to null) in a before-hook skips the original method. */
    var result: Any?

    fun getExtra(key: String): Any?

    fun setExtra(key: String, value: Any?)
}

internal class XposedHookParam(private val param: XC_MethodHook.MethodHookParam) : HookParam {
    override val args: Array<Any?> get() = param.args
    override val thisObject: Any get() = param.thisObject
    override val throwable: Throwable? get() = param.throwable
    override var result: Any?
        get() = param.result
        set(value) {
            param.result = value
        }

    override fun getExtra(key: String): Any? = param.getObjectExtra(key)

    override fun setExtra(key: String, value: Any?) {
        param.setObjectExtra(key, value)
    }
}
