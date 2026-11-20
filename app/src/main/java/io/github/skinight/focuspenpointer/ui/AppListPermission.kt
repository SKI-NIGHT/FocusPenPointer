package io.github.skinight.focuspenpointer.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * HyperOS/MIUI hides other apps from PackageManager unless the vendor runtime permission
 * "获取应用列表" is granted, even with QUERY_ALL_PACKAGES. ROMs that do not define the
 * permission are treated as granted.
 */
internal object AppListPermission {
    const val NAME = "com.android.permission.GET_INSTALLED_APPS"

    fun isDefined(context: Context): Boolean = runCatching {
        context.packageManager.getPermissionInfo(NAME, 0)
    }.isSuccess

    fun isGranted(context: Context): Boolean =
        !isDefined(context) ||
            context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED
}

/**
 * Requests the app-list permission once per screen instance when it is missing, and
 * returns a key that changes whenever the grant state changes, whether through the
 * dialog or after the user returns from system settings. Key the app-list load on it.
 */
@Composable
internal fun rememberAppListReloadKey(activity: ComponentActivity): Int {
    var granted by remember { mutableStateOf(AppListPermission.isGranted(activity)) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var requested by rememberSaveable { mutableStateOf(false) }

    fun onGrantState(nowGranted: Boolean) {
        if (nowGranted != granted) {
            granted = nowGranted
            reloadKey += 1
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> onGrantState(result || AppListPermission.isGranted(activity)) }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            runCatching { launcher.launch(AppListPermission.NAME) }
        }
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onGrantState(AppListPermission.isGranted(activity))
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    return reloadKey
}
