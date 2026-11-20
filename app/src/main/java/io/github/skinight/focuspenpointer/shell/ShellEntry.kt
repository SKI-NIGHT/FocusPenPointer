package io.github.skinight.focuspenpointer.shell

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Xposed entry point. Only system_server is hooked. */
class ShellEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return
        runCatching { ModuleHost.install(lpparam.classLoader) }
            .onFailure {
                XposedBridge.log(
                    "FocusPenPointer: installation failed; all events remain original\n" +
                        it.stackTraceToString(),
                )
            }
    }
}
