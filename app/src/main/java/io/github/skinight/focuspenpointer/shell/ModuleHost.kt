package io.github.skinight.focuspenpointer.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import dalvik.system.InMemoryDexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.skinight.focuspenpointer.BuildConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * Owns the installed hooks and routes them to the current [HookModule]. In debug builds it
 * also hot-reloads the module whenever the module APK is reinstalled, so logic changes can
 * be tested without restarting system_server.
 */
object ModuleHost {
    private const val MODULE_CLASS = "io.github.skinight.focuspenpointer.hook.FocusPenModule"
    private const val PACKAGE = "io.github.skinight.focuspenpointer"

    /** Packages loaded from the new APK on reload; everything else comes from the boot APK. */
    private val RELOADABLE_PACKAGES = listOf(
        "$PACKAGE.hook.",
        "$PACKAGE.gesture.",
        "$PACKAGE.config.",
    )

    private val current = AtomicReference<HookModule?>()
    private val reloadTriggerRegistered = AtomicBoolean(false)
    private lateinit var systemServerClassLoader: ClassLoader
    private var reloadHandler: Handler? = null

    fun install(classLoader: ClassLoader) {
        systemServerClassLoader = classLoader
        val module = createModule(ModuleHost::class.java.classLoader!!)
        current.set(module)

        // Resolve every class first: a missing class must abort before any hook is placed.
        val classes = HookPoints.ALL.associate { point ->
            point.className to XposedHelpers.findClass(point.className, classLoader)
        }
        HookPoints.ALL.forEach { point ->
            val clazz = classes.getValue(point.className)
            val callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = current.get() ?: return
                    try {
                        target.beforeHook(point.id, XposedHookParam(param))
                    } catch (throwable: Throwable) {
                        logFailure(point, throwable)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = current.get() ?: return
                    try {
                        target.afterHook(point.id, XposedHookParam(param))
                    } catch (throwable: Throwable) {
                        logFailure(point, throwable)
                    }
                }
            }
            if (point.method == null) {
                XposedBridge.hookAllConstructors(clazz, callback)
            } else {
                XposedBridge.hookAllMethods(clazz, point.method, callback)
            }
        }
        module.attach()
        XposedBridge.log(
            "FocusPenPointer: shell v${HookPoints.SHELL_VERSION} installed " +
                "${HookPoints.ALL.size} hook points" +
                if (BuildConfig.DEBUG) " (hot reload enabled)" else "",
        )
    }

    /**
     * Called by the module once system_server has a usable Context. Debug builds then
     * reload the module whenever its APK is replaced.
     */
    fun onSystemContextAvailable(context: Context) {
        if (!BuildConfig.DEBUG || !reloadTriggerRegistered.compareAndSet(false, true)) return
        val thread = HandlerThread("FocusPenPointerReload").apply { start() }
        val handler = Handler(thread.looper)
        reloadHandler = handler
        val filter = IntentFilter().apply {
            // A reinstall also sends PACKAGE_ADDED; listening to both would reload twice.
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (intent.data?.schemeSpecificPart != PACKAGE) return
                        reload(context)
                    }
                },
                filter,
                null,
                handler,
            )
            XposedBridge.log("FocusPenPointer: hot reload armed (reinstall the debug APK to reload)")
        }.onFailure {
            reloadTriggerRegistered.set(false)
            XposedBridge.log("FocusPenPointer: hot reload unavailable\n${it.stackTraceToString()}")
        }
    }

    private fun reload(context: Context) {
        val started = System.nanoTime()
        val next = try {
            val apk = context.packageManager.getApplicationInfo(PACKAGE, 0).sourceDir
            createModule(childFirstDexLoader(readDexFiles(apk), ModuleHost::class.java.classLoader!!))
        } catch (throwable: Throwable) {
            XposedBridge.log(
                "FocusPenPointer: hot reload failed to load new module; keeping current\n" +
                    throwable.stackTraceToString(),
            )
            return
        }
        if (next.shellVersion() != HookPoints.SHELL_VERSION) {
            XposedBridge.log(
                "FocusPenPointer: hot reload refused: hook points changed " +
                    "(shell v${HookPoints.SHELL_VERSION}, module v${next.shellVersion()}); reboot required",
            )
            return
        }
        val previous = current.getAndSet(null)
        runCatching { previous?.dispose() }.onFailure {
            XposedBridge.log("FocusPenPointer: disposing previous module failed\n${it.stackTraceToString()}")
        }
        current.set(next)
        runCatching { next.attach() }.onFailure {
            XposedBridge.log("FocusPenPointer: new module attach failed\n${it.stackTraceToString()}")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        XposedBridge.log("FocusPenPointer: hot reloaded module in ${elapsedMs}ms")
    }

    private fun createModule(loader: ClassLoader): HookModule =
        Class.forName(MODULE_CLASS, true, loader)
            .getConstructor(ClassLoader::class.java)
            .newInstance(systemServerClassLoader) as HookModule

    private fun readDexFiles(apkPath: String): Array<ByteBuffer> =
        ZipFile(apkPath).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedWith(compareBy({ it.name.length }, { it.name })) // classes.dex first
                .map { entry -> ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() }) }
                .toList()
                .toTypedArray()
        }

    private fun logFailure(point: HookPoints.Point, throwable: Throwable) {
        XposedBridge.log(
            "FocusPenPointer: hook ${point.className}#${point.method ?: "<init>"} failed; " +
                "original kept\n${throwable.stackTraceToString()}",
        )
    }

    /**
     * Loads [RELOADABLE_PACKAGES] from the new dex files and everything else (the shell,
     * Kotlin, Android, Xposed) from the boot-time module class loader.
     */
    private fun childFirstDexLoader(dex: Array<ByteBuffer>, parent: ClassLoader): ClassLoader =
        InMemoryDexClassLoader(dex, FilteringParent(parent))

    private class FilteringParent(private val delegate: ClassLoader) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (RELOADABLE_PACKAGES.any(name::startsWith)) throw ClassNotFoundException(name)
            return delegate.loadClass(name)
        }
    }
}
