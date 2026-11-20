package io.github.skinight.focuspenpointer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.skinight.focuspenpointer.BuildConfig
import io.github.skinight.focuspenpointer.config.ConfigContract
import io.github.skinight.focuspenpointer.config.ConfigStore
import io.github.skinight.focuspenpointer.config.ModuleConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

private const val UI_PREFS = "focus_pen_ui"
private const val CURRENT_PROJECT_URL = "https://github.com/SKI-NIGHT/FocusPenPointer"
private const val UPSTREAM_PROJECT_URL = "https://github.com/HMQYHM/FocusPenProX"
private const val KEY_LANGUAGE = "language"
private const val KEY_LAUNCH_COUNT = "launch_count"
private const val KEY_STAR_COMPLETED = "star_completed"
private const val KEY_SHOW_ALL_APPS = "show_all_apps"
private const val TIP_COUNT = 17

private enum class UiLanguage(val code: String) {
    SIMPLIFIED("zh-CN"), TRADITIONAL("zh-TW"), ENGLISH("en");

    companion object {
        fun from(code: String?): UiLanguage = entries.firstOrNull { it.code == code } ?: SIMPLIFIED
    }
}

private enum class XPage { HOME, SCOPE, GESTURES, OTHER, BLACKLIST, APP_PICKER }

private enum class AppPickerTarget { FOUR_TAP, FOUR_HOLD }

private data class XAppEntry(
    val label: String,
    val packageName: String,
    val hasLauncher: Boolean,
    val autoExpandOnSelection: Boolean,
)

private data class XRuntime(
    val active: Boolean = false,
    val compatible: Boolean = false,
    val message: String = "",
    val laser: Boolean = false,
    val foreground: String = "",
    val events: List<String> = emptyList(),
)

private fun l(language: UiLanguage, simplified: String, traditional: String, english: String): String =
    when (language) {
        UiLanguage.SIMPLIFIED -> simplified
        UiLanguage.TRADITIONAL -> traditional
        UiLanguage.ENGLISH -> english
    }

private fun detectedPlatformLabel(language: UiLanguage): String {
    val display = Build.DISPLAY.orEmpty()
    val detected = when {
        display.contains("OS4", ignoreCase = true) || Build.VERSION.SDK_INT >= 37 -> "HyperOS 4"
        display.contains("OS3", ignoreCase = true) || Build.VERSION.SDK_INT == 36 -> "HyperOS 3"
        else -> l(language, "未识别", "未識別", "unknown")
    }
    return "$detected / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
}

@Composable
internal fun FocusPenPointerApp(activity: MainActivity) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        FocusPenPointerContent(activity)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FocusPenPointerContent(activity: MainActivity) {
    val store = remember { ConfigStore(activity) }
    val preferences = remember {
        activity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
    }
    var language by remember {
        mutableStateOf(UiLanguage.from(preferences.getString(KEY_LANGUAGE, null)))
    }
    var config by remember { mutableStateOf(store.read()) }
    var runtime by remember { mutableStateOf(XRuntime()) }
    var apps by remember { mutableStateOf(emptyList<XAppEntry>()) }
    var currentPage by rememberSaveable { mutableStateOf(XPage.HOME) }
    var backStack by rememberSaveable { mutableStateOf(listOf<XPage>()) }
    var navigatingForward by remember { mutableStateOf(true) }
    val predictiveProgress = remember { Animatable(0f) }
    var predictiveCommit by remember { mutableStateOf(false) }
    var pickerTarget by rememberSaveable { mutableStateOf(AppPickerTarget.FOUR_TAP) }
    var showStarPrompt by rememberSaveable { mutableStateOf(false) }
    var starSeconds by remember { mutableIntStateOf(6) }
    var railExpanded by rememberSaveable { mutableStateOf(true) }
    var tipOrder by rememberSaveable { mutableStateOf((0 until TIP_COUNT).shuffled()) }
    var tipPosition by rememberSaveable { mutableIntStateOf(0) }
    val tipIndex = tipOrder.getOrElse(tipPosition) { 0 }

    fun updateConfig(transform: (ModuleConfig) -> ModuleConfig) {
        config = store.update(transform)
    }

    fun navigate(page: XPage) {
        if (page == currentPage) return
        navigatingForward = true
        backStack = backStack + currentPage
        currentPage = page
    }

    fun navigateTopLevel(page: XPage) {
        if (page == currentPage) return
        navigatingForward = page.ordinal >= currentPage.ordinal
        backStack = if (currentPage == XPage.HOME) emptyList() else backStack
        currentPage = page
    }

    fun popPage() {
        val target = backStack.lastOrNull() ?: XPage.HOME
        navigatingForward = false
        backStack = if (backStack.isEmpty()) emptyList() else backStack.dropLast(1)
        currentPage = target
    }

    val appListReloadKey = rememberAppListReloadKey(activity)
    LaunchedEffect(appListReloadKey) {
        apps = withContext(Dispatchers.IO) { loadXApps(activity) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runtime = readXRuntime(activity)
            delay(1_000L)
        }
    }
    LaunchedEffect(Unit) {
        val launches = preferences.getInt(KEY_LAUNCH_COUNT, 0) + 1
        preferences.edit().putInt(KEY_LAUNCH_COUNT, launches).apply()
        if (launches > 3 && !preferences.getBoolean(KEY_STAR_COMPLETED, false)) {
            delay(650L)
            showStarPrompt = true
        }
    }
    LaunchedEffect(showStarPrompt) {
        if (!showStarPrompt) return@LaunchedEffect
        for (remaining in 6 downTo 1) {
            starSeconds = remaining
            delay(1_000L)
        }
        showStarPrompt = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(9_000L)
            if (tipPosition < tipOrder.lastIndex) {
                tipPosition += 1
            } else {
                val previous = tipOrder.lastOrNull()
                var nextOrder = (0 until TIP_COUNT).shuffled()
                if (nextOrder.firstOrNull() == previous && nextOrder.size > 1) {
                    nextOrder = nextOrder.drop(1) + nextOrder.first()
                }
                tipOrder = nextOrder
                tipPosition = 0
            }
        }
    }

    PredictiveBackHandler(enabled = backStack.isNotEmpty() || currentPage != XPage.HOME) { events ->
        try {
            events.collect { event -> predictiveProgress.snapTo(event.progress) }
            predictiveCommit = true
            popPage()
            predictiveProgress.animateTo(
                0f,
                tween(durationMillis = 150),
            )
            predictiveCommit = false
        } catch (_: CancellationException) {
            predictiveProgress.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f),
            )
        } finally {
            predictiveCommit = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight
        val backTranslation = with(LocalDensity.current) {
            (if (wide) 72.dp else 54.dp).toPx()
        } * predictiveProgress.value
        Scaffold(
            bottomBar = {
                if (!wide) {
                    CompactNavigation(currentPage, language, ::navigateTopLevel)
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    SideNavigation(
                        page = currentPage,
                        language = language,
                        expanded = railExpanded,
                        onExpandedChange = { railExpanded = it },
                        navigate = ::navigateTopLevel,
                    )
                }
                    AnimatedContent(
                        targetState = currentPage,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = if (wide) backTranslation else 0f
                                translationY = if (wide) 0f else backTranslation
                                val backScale = if (wide) 0.018f else 0.028f
                                scaleX = 1f - predictiveProgress.value * backScale
                                scaleY = 1f - predictiveProgress.value * backScale
                                alpha = 1f - predictiveProgress.value * 0.14f
                            },
                        transitionSpec = {
                            val direction = if (navigatingForward) 1 else -1
                            if (predictiveCommit) {
                                (fadeIn(tween(130)) + scaleIn(initialScale = 0.99f)) togetherWith
                                    fadeOut(tween(100)) using SizeTransform(clip = false)
                            } else (
                                slideInHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = 0.84f,
                                        stiffness = 420f,
                                    ),
                                ) { direction * it / 10 } +
                                    fadeIn(tween(170)) +
                                    scaleIn(
                                        initialScale = 0.985f,
                                        animationSpec = spring(
                                            dampingRatio = 0.9f,
                                            stiffness = 430f,
                                        ),
                                    )
                                ) togetherWith (
                                slideOutHorizontally(tween(180)) { -direction * it / 14 } +
                                    fadeOut(tween(150)) + scaleOut(targetScale = 0.992f)
                                ) using SizeTransform(clip = false)
                        },
                        label = "FocusPenPage",
                    ) { page ->
                        when (page) {
                            XPage.HOME -> PageWithTips(language, tipIndex) {
                                HomePage(
                                    language, config, runtime, updateConfig = ::updateConfig,
                                    onScope = { navigate(XPage.SCOPE) },
                                    onGestures = { navigate(XPage.GESTURES) },
                                    onOther = { navigate(XPage.OTHER) },
                                )
                            }
                            XPage.SCOPE -> PageWithTips(language, tipIndex) {
                                ScopePage(
                                    language, config,
                                    onBlacklist = { navigate(XPage.BLACKLIST) },
                                )
                            }
                            XPage.GESTURES -> PageWithTips(language, tipIndex) {
                                GesturesPage(language, config, apps, ::updateConfig) { target ->
                                    pickerTarget = target
                                    navigate(XPage.APP_PICKER)
                                }
                            }
                            XPage.OTHER -> PageWithTips(language, tipIndex) {
                                OtherPage(
                                    language = language,
                                    platformProfile = config.platformProfile,
                                    onPlatformProfile = { selected ->
                                        updateConfig { it.copy(platformProfile = selected) }
                                    },
                                    onLanguage = { selected ->
                                        language = selected
                                        preferences.edit().putString(KEY_LANGUAGE, selected.code).apply()
                                    },
                                    onCurrentProject = { openUrl(activity, CURRENT_PROJECT_URL) },
                                    onUpstreamProject = { openUrl(activity, UPSTREAM_PROJECT_URL) },
                                    onResetGestures = {
                                        updateConfig { current ->
                                            current.copy(
                                                globalActionsEnabled = false,
                                                tripleAction = ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
                                                triplePackage = "",
                                                fourHoldAction = ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
                                                fourHoldPackage = "",
                                                gestureActions = ConfigContract.DEFAULT_GESTURE_ACTIONS,
                                                scrollAmount = ConfigContract.SCROLL_MEDIUM,
                                            )
                                        }
                                    },
                                )
                            }
                            XPage.BLACKLIST -> BlacklistPage(
                                language, apps, config, ::updateConfig, ::popPage,
                            )
                            XPage.APP_PICKER -> AppPickerPage(
                                language = language,
                                apps = apps,
                                selectedPackage = if (pickerTarget == AppPickerTarget.FOUR_TAP) {
                                    config.triplePackage
                                } else {
                                    config.fourHoldPackage
                                },
                                onSave = { packageName ->
                                    updateConfig { current ->
                                        if (pickerTarget == AppPickerTarget.FOUR_TAP) {
                                            current.copy(triplePackage = packageName)
                                        } else {
                                            current.copy(fourHoldPackage = packageName)
                                        }
                                    }
                                    popPage()
                                },
                            )
                        }
                    }
            }
        }
    }

    if (showStarPrompt) {
        AlertDialog(
            onDismissRequest = { showStarPrompt = false },
            icon = { Text("🥺✨", style = MaterialTheme.typography.headlineMedium) },
            title = {
                Text(l(language, "喜欢这个项目吗？", "喜歡這個專案嗎？", "Enjoying this project?"))
            },
            text = {
                Text(
                    l(
                        language,
                        "喜欢的话，麻烦去 GitHub 项目地址点个 Star，感谢支持！",
                        "喜歡的話，麻煩到 GitHub 專案頁點個 Star，感謝支持！",
                        "If you like it, please give the project a Star on GitHub. Thank you!",
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { showStarPrompt = false }) {
                    Text(
                        l(
                            language,
                            "下次一定 · ${starSeconds}s",
                            "下次一定 · ${starSeconds}s",
                            "Maybe next time · ${starSeconds}s",
                        ),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    preferences.edit().putBoolean(KEY_STAR_COMPLETED, true).apply()
                    showStarPrompt = false
                    openUrl(activity, CURRENT_PROJECT_URL)
                }) {
                    Text(l(language, "现在就去", "現在就去", "Go now"))
                }
            },
        )
    }
}

@Composable
private fun SideNavigation(
    page: XPage,
    language: UiLanguage,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    navigate: (XPage) -> Unit,
) {
    val railWidth by animateDpAsState(
        targetValue = if (expanded) 180.dp else 76.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 430f),
        label = "NavigationRailWidth",
    )
    val itemSpacing by animateDpAsState(
        targetValue = if (expanded) 5.dp else 11.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 360f),
        label = "NavigationItemSpacing",
    )
    Surface(modifier = Modifier.width(railWidth).fillMaxHeight(), tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.padding(vertical = 12.dp).size(42.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ChevronGlyph(pointsLeft = expanded)
            }
        }
        topPages(language).forEach { (target, symbol, title) ->
            val selected = page == target ||
                (target == XPage.SCOPE && page == XPage.BLACKLIST) ||
                (target == XPage.GESTURES && page == XPage.APP_PICKER)
            SideNavigationItem(
                symbol = symbol,
                title = title,
                selected = selected,
                expanded = expanded,
                onClick = { navigate(target) },
            )
        }
        }
    }
}

@Composable
private fun ChevronGlyph(pointsLeft: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val pointX = if (pointsLeft) size.width * 0.34f else size.width * 0.66f
        val tailX = if (pointsLeft) size.width * 0.66f else size.width * 0.34f
        val centerY = size.height * 0.5f
        val arm = size.height * 0.27f
        val stroke = 2.2.dp.toPx()
        drawLine(color, Offset(tailX, centerY - arm), Offset(pointX, centerY), stroke, StrokeCap.Round)
        drawLine(color, Offset(pointX, centerY), Offset(tailX, centerY + arm), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SideNavigationItem(
    symbol: String,
    title: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val iconSize by animateDpAsState(
        targetValue = if (expanded) 40.dp else 42.dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 390f),
        label = "SideIconSize",
    )
    val iconStart by animateDpAsState(
        targetValue = if (expanded) 8.dp else 10.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 390f),
        label = "SideIconPosition",
    )
    val itemCorner by animateDpAsState(
        targetValue = if (expanded) 18.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 390f),
        label = "SideItemCorner",
    )
    val itemColor by animateColorAsState(
        targetValue = if (selected && expanded) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        animationSpec = tween(180),
        label = "SideItemColor",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected && !expanded) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180),
        label = "SideIconColor",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(itemCorner),
        color = itemColor,
    ) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = iconStart).size(iconSize),
                shape = CircleShape,
                color = iconColor,
            ) {
                Box(
                    modifier = if (symbol == "⌁") {
                        Modifier.graphicsLayer { translationY = -(28 * 0.05f).dp.toPx() }
                    } else Modifier,
                    contentAlignment = Alignment.Center,
                ) { NavSymbol(symbol, 28) }
            }
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 62.dp, end = 8.dp),
                enter = fadeIn(tween(190, delayMillis = 70)) + slideInVertically(tween(230)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(170)) { it },
            ) {
                Text(
                    title,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CompactNavigation(page: XPage, language: UiLanguage, navigate: (XPage) -> Unit) {
    NavigationBar(tonalElevation = 3.dp) {
        topPages(language).forEach { (target, symbol, title) ->
            NavigationBarItem(
                selected = page == target ||
                    (target == XPage.SCOPE && page == XPage.BLACKLIST) ||
                    (target == XPage.GESTURES && page == XPage.APP_PICKER),
                onClick = { navigate(target) },
                icon = { NavSymbol(symbol, 27) },
                label = { Text(title, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun NavSymbol(symbol: String, size: Int) {
    Box(Modifier.size((size + 8).dp), contentAlignment = Alignment.Center) {
        if (symbol == "⋯") {
            val color = LocalContentColor.current
            Canvas(Modifier.size(size.dp)) {
                val radius = this.size.minDimension * 0.085f
                val centerY = this.size.height * 0.5f
                listOf(0.27f, 0.5f, 0.73f).forEach { fraction ->
                    drawCircle(color, radius, Offset(this.size.width * fraction, centerY))
                }
            }
        } else {
            Text(
                symbol,
                modifier = if (symbol == "⌂") {
                    Modifier.graphicsLayer { translationY = -(size * 0.05f).dp.toPx() }
                } else Modifier,
                fontSize = size.sp,
                lineHeight = size.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PageWithTips(language: UiLanguage, tipIndex: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        TipsTicker(language, tipIndex)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TipsTicker(language: UiLanguage, tipIndex: Int) {
    val localizedTips = remember(language) { tips(language) }
    val message = localizedTips[tipIndex % localizedTips.size]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        AnimatedContent(
            targetState = message,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 3 }) togetherWith
                    (fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it / 3 })
            },
            label = "TipsRotation",
        ) { currentTip ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "${l(language, "小提示", "小提示", "TIP")}  ·  $currentTip",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                            repeatDelayMillis = 2_400,
                            initialDelayMillis = 1_600,
                            velocity = 18.dp,
                        ),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun tips(language: UiLanguage): List<String> = listOf(
    l(language, "真实音量键动作也能支持多数小说应用的音量键翻页。", "真實音量鍵動作也能支援多數小說應用程式的音量鍵翻頁。", "Real volume-key actions also support page turning in most reading apps."),
    l(language, "鼠标滚轮动作可用于支持滚轮翻页的 PPT／演示文稿应用。", "滑鼠滾輪動作可用於支援滾輪翻頁的 PowerPoint／簡報應用程式。", "Mouse-wheel actions work with PowerPoint and presentation apps that support wheel navigation."),
    l(language, "鼠标滚轮动作也能用来上下浏览抖音等支持滚轮操作的短视频应用。", "滑鼠滾輪動作也能用來上下瀏覽 TikTok 等支援滾輪操作的短影音應用程式。", "Mouse-wheel actions can also scroll through TikTok and other short-video apps that support wheel input."),
    l(language, "开启全局动作后，退出手写笔鼠标时，可直接触发设为“开启虚拟激光或手写笔鼠标”的手势，再次进入手写笔鼠标。", "啟用全域動作後，退出手寫筆滑鼠時，可直接觸發設為「開啟虛擬雷射或手寫筆滑鼠」的手勢，再次進入手寫筆滑鼠。", "With global actions enabled, after leaving the stylus mouse, trigger a gesture assigned to “Enable virtual laser or stylus mouse” to enter it again."),
    l(language, "轻触两次触控笔的识别区域，可快速关闭当前的虚拟激光或手写笔鼠标。", "輕觸兩次觸控筆的感應區域，可快速關閉目前的虛擬雷射或手寫筆滑鼠。", "Double-tap the pen’s sensing area to quickly close the active virtual laser or stylus mouse."),
    l(language, "启用增强功能后，为等待多次轻捏判定，快捷环的显示可能会略有延迟。", "啟用增強功能後，為等待多次輕捏判定，快捷環的顯示可能會略有延遲。", "When enhancements are enabled, the shortcut ring may appear slightly later while the module waits for a possible multi-pinch gesture."),
    l(language, "触摸屏幕即可直接关闭当前的虚拟激光或手写笔鼠标。", "輕觸螢幕即可直接關閉目前的虛擬雷射或手寫筆滑鼠。", "Touch the screen to immediately close the active virtual laser or stylus mouse."),
    l(language, "如果虚拟激光或手写笔鼠标无法唤出，请先用手指轻触一下屏幕，再重新尝试唤出。", "如果虛擬雷射或手寫筆滑鼠無法喚出，請先用手指輕觸一下螢幕，再重新嘗試喚出。", "If the virtual laser or stylus mouse does not activate, touch the screen once with your finger, then try again."),
    l(language, "建议将笔记、绘画和游戏应用加入黑名单，避免增强手势影响原版书写、绘画或游戏操作哦 😁", "建議將筆記、繪圖與遊戲應用程式加入黑名單，避免增強手勢影響原版書寫、繪圖或遊戲操作喔 😁", "Add note-taking, drawing, and game apps to the blacklist so enhanced gestures don’t interfere with their original controls. 😁"),
    l(language, "发现 Bug 或有功能建议？欢迎前往项目网页提交反馈哦。", "發現 Bug 或有功能建議？歡迎前往專案網頁提交意見喔。", "Found a bug or have a suggestion? You’re welcome to submit it on the project page."),
    l(language, "黑名单拥有最高优先级：名单内完全不 Hook，也不会触发任何增强操作。", "黑名單擁有最高優先級：名單內完全不 Hook，也不會觸發任何增強操作。", "The blacklist has the highest priority: blacklisted apps are never hooked and trigger no enhanced actions."),
    l(language, "FocusPen Pointer 不联网，也不会上传你的任何数据。", "FocusPen Pointer 不連網，也不會上傳你的任何資料。", "FocusPen Pointer never connects to the internet or uploads any of your data."),
    l(language, "偷偷问一句：可以帮这个项目点个 Star 吗？🥺", "偷偷問一句：可以幫這個專案點個 Star 嗎？🥺", "A tiny request: could you give this project a Star? 🥺"),
    l(language, "修改黑名单后，记得点击右下角绿色对勾保存。", "修改黑名單後，記得點擊右下角綠色勾號儲存。", "After editing the blacklist, tap the green check button to save."),
    l(language, "全局四次轻捏在所有应用中可用，但始终不会影响黑名单应用。", "全域四次輕捏在所有應用程式中可用，但始終不會影響黑名單應用程式。", "Global four-pinches work in every app but never affect blacklisted apps."),
    l(language, "关闭总开关、取消 LSPosed 作用域或卸载模块后，系统会恢复原始触控笔行为。", "關閉總開關、取消 LSPosed 作用域或解除安裝模組後，系統會恢復原始觸控筆行為。", "Turning off the master switch, removing the LSPosed scope, or uninstalling restores original pen behavior."),
    l(language, "若出现按键未释放或操作异常，请先关闭总开关，模块会执行安全释放。", "若出現按鍵未釋放或操作異常，請先關閉總開關，模組會執行安全釋放。", "If an input appears stuck, turn off the master switch first so the module can release it safely."),
)

private fun topPages(language: UiLanguage) = listOf(
    Triple(XPage.HOME, "⌂", l(language, "首页", "首頁", "Home")),
    Triple(XPage.SCOPE, "▦", l(language, "应用范围", "應用範圍", "Apps")),
    Triple(XPage.GESTURES, "⌁", l(language, "手势设置", "手勢設定", "Gestures")),
    Triple(XPage.OTHER, "⋯", l(language, "其他", "其他", "Other")),
)

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomePage(
    language: UiLanguage,
    config: ModuleConfig,
    runtime: XRuntime,
    updateConfig: ((ModuleConfig) -> ModuleConfig) -> Unit,
    onScope: () -> Unit,
    onGestures: () -> Unit,
    onOther: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            PageTitle(
                l(language, "FocusPen Pointer", "FocusPen Pointer", "FocusPen Pointer"),
                l(
                    language,
                    "小米焦点触控笔 Pro 手势增强模块",
                    "小米焦點觸控筆 Pro 手勢增強模組",
                    "Gesture enhancement module for Xiaomi Focus Pen Pro",
                ),
            )
        }
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            l(language, "增强功能", "增強功能", "Enhancement"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            l(
                                language,
                                "关闭后立即恢复小米原始触控笔行为",
                                "關閉後立即恢復小米原始觸控筆行為",
                                "Turn off to immediately restore Xiaomi's original behavior",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Switch(checked = config.enabled, onCheckedChange = { enabled ->
                        updateConfig { it.copy(enabled = enabled) }
                    })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard(
                    Modifier.weight(1f), "✓",
                    l(language, "应用范围", "應用範圍", "Apps"), onScope,
                )
                QuickCard(
                    Modifier.weight(1f), "⌁",
                    l(language, "手势设置", "手勢設定", "Gestures"), onGestures,
                )
                QuickCard(
                    Modifier.weight(1f), "•••",
                    l(language, "其他", "其他", "Other"), onOther,
                )
            }
        }
        item {
            StatusPanel(language, runtime)
        }
        item {
            Text(
                l(language, "最近事件", "最近事件", "Recent events"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
                Text(
                    if (runtime.events.isEmpty()) {
                        l(language, "暂无事件记录", "暫無事件記錄", "No events yet")
                    } else runtime.events.takeLast(10).reversed().joinToString("\n"),
                    Modifier.fillMaxWidth().padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun QuickCard(modifier: Modifier, symbol: String, title: String, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(15.dp)) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.CenterStart) {
                if (symbol == "•••") {
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(25.dp)) {
                        val radius = size.minDimension * 0.07f
                        val centerY = size.height * 0.5f
                        listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
                            drawCircle(color, radius, Offset(size.width * fraction, centerY))
                        }
                    }
                } else {
                    val symbolSize = when (symbol) {
                        "⌁" -> 35
                        "✓" -> 23
                        else -> 27
                    }
                    Text(
                        symbol,
                        modifier = if (symbol == "⌁") {
                            Modifier.graphicsLayer { translationY = -(symbolSize * 0.05f).dp.toPx() }
                        } else Modifier,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = symbolSize.sp,
                        lineHeight = symbolSize.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPanel(language: UiLanguage, runtime: XRuntime) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            XStatusLine(
                l(language, "模块状态", "模組狀態", "Module status"),
                if (runtime.active) l(language, "已激活", "已啟用", "Active")
                else l(language, "未激活", "未啟用", "Inactive"),
            )
            XStatusLine(
                l(language, "虚拟激光", "虛擬雷射", "Virtual laser"),
                if (runtime.laser) l(language, "已开启", "已開啟", "On")
                else l(language, "未开启", "未開啟", "Off"),
            )
            XStatusLine(
                l(language, "当前应用", "目前應用程式", "Foreground app"),
                runtime.foreground.ifBlank { l(language, "未知", "未知", "Unknown") },
            )
            XStatusLine(
                l(language, "系统版本", "系統版本", "System"),
                "Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}",
            )
        }
    }
}

@Composable
private fun XStatusLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScopePage(
    language: UiLanguage,
    config: ModuleConfig,
    onBlacklist: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageTitle(
                l(language, "应用范围", "應用範圍", "App scope"),
                l(
                    language,
                    "手写笔鼠标在所有应用中生效，黑名单应用保持小米原版",
                    "手寫筆滑鼠在所有應用程式中生效，黑名單應用程式保持小米原版",
                    "The stylus mouse works in every app; blacklisted apps keep Xiaomi's original behavior",
                ),
            )
        }
        item {
            ScopeCard(
                symbol = "◇",
                title = l(language, "黑名单", "黑名單", "Blacklist"),
                detail = l(
                    language,
                    "完全不 Hook，保留小米原版激光画笔 · ${config.blacklist.size} 个应用",
                    "完全不 Hook，保留小米原版雷射畫筆 · ${config.blacklist.size} 個應用程式",
                    "No hooks; preserve Xiaomi laser drawing · ${config.blacklist.size} apps",
                ),
                onClick = onBlacklist,
            )
        }
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    l(
                        language,
                        "黑名单优先级最高。即使开启全局动作，黑名单应用的小米原版激光功能也不受影响。",
                        "黑名單優先級最高。即使開啟全域動作，黑名單應用程式的小米原版雷射功能也不受影響。",
                        "Blacklist has the highest priority. Global actions never affect blacklisted apps.",
                    ),
                    Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ScopeCard(symbol: String, title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(symbol, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun BlacklistPage(
    language: UiLanguage,
    apps: List<XAppEntry>,
    config: ModuleConfig,
    updateConfig: ((ModuleConfig) -> ModuleConfig) -> Unit,
    onSaved: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val appContext = LocalContext.current
    val uiPreferences = remember(appContext) {
        appContext.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
    }
    var showAllApps by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean(KEY_SHOW_ALL_APPS, false))
    }
    val setShowAllApps: (Boolean) -> Unit = { show ->
        showAllApps = show
        uiPreferences.edit().putBoolean(KEY_SHOW_ALL_APPS, show).apply()
    }
    var selected by remember(config.blacklist) { mutableStateOf(config.blacklist) }
    var confirmBack by remember { mutableStateOf(false) }
    val hasUnsavedChanges = selected != config.blacklist
    val saveChanges: () -> Unit = {
        updateConfig { current -> current.copy(blacklist = selected) }
        onSaved()
    }
    PredictiveBackHandler(enabled = hasUnsavedChanges) { events ->
        try {
            events.collect { }
            confirmBack = true
        } catch (_: CancellationException) {
            // A cancelled predictive gesture keeps the editor open.
        }
    }
    val filtered = remember(apps, search, selected, showAllApps) {
        val visibleApps = if (showAllApps) apps else apps.filter(XAppEntry::hasLauncher)
        val matches = if (search.isBlank()) visibleApps else visibleApps.filter {
            it.label.contains(search, true) || it.packageName.contains(search, true)
        }
        matches.sortedWith(
            compareBy<XAppEntry> { if (it.packageName in selected) 0 else 1 }
                .thenBy { it.label.lowercase() },
        )
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                PageTitle(
                    l(language, "选择黑名单", "選擇黑名單", "Select blacklist"),
                    l(language, "已选择 ${selected.size} 个应用使用原版逻辑", "已選取 ${selected.size} 個應用程式使用原版邏輯", "${selected.size} apps keep original behavior"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        label = { Text(l(language, "搜索应用或包名", "搜尋應用程式或套件名稱", "Search apps or package")) },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { setShowAllApps(!showAllApps) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            if (showAllApps) {
                                l(language, "仅启动器", "僅啟動器", "Launchers")
                            } else {
                                l(language, "显示所有应用", "顯示所有應用程式", "All apps")
                            },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            items(filtered, key = XAppEntry::packageName) { app ->
                val isSelected = app.packageName in selected
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = spring(dampingRatio = 0.82f, stiffness = 390f),
                            fadeOutSpec = tween(120),
                        )
                        .clickable {
                            if (isSelected) {
                                selected = selected - app.packageName
                            } else {
                                selected = selected + app.packageName
                                if (app.autoExpandOnSelection) setShowAllApps(true)
                            }
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Checkbox(checked = isSelected, onCheckedChange = null)
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { saveChanges() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            shape = CircleShape,
            containerColor = Color(0xFF2E7D32),
            contentColor = Color.White,
        ) {
            Text("✓", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }

    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text(l(language, "保存名单更改？", "儲存名單變更？", "Save list changes?")) },
            text = {
                Text(l(language, "当前名单有未保存的更改，返回前是否保存？", "目前名單有未儲存的變更，返回前是否儲存？", "This list has unsaved changes. Save them before leaving?"))
            },
            confirmButton = {
                Button(onClick = {
                    confirmBack = false
                    saveChanges()
                }) { Text(l(language, "保存", "儲存", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmBack = false
                    onSaved()
                }) { Text(l(language, "不保存", "不儲存", "Don't save")) }
            },
        )
    }
}

@Composable
private fun AppPickerPage(
    language: UiLanguage,
    apps: List<XAppEntry>,
    selectedPackage: String,
    onSave: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember(selectedPackage) { mutableStateOf(selectedPackage) }
    val filtered = remember(apps, search, selected) {
        val matches = if (search.isBlank()) apps else apps.filter {
            it.label.contains(search, true) || it.packageName.contains(search, true)
        }
        matches.sortedWith(
            compareByDescending<XAppEntry> { it.packageName == selected }
                .thenBy { it.label.lowercase() },
        )
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                PageTitle(
                    l(language, "选择启动应用", "選擇啟動應用程式", "Select app to launch"),
                    l(language, "选择一个应用，点击右下角对勾保存", "選擇一個應用程式，點擊右下角勾號儲存", "Choose one app, then tap the check button to save"),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    label = { Text(l(language, "搜索应用或包名", "搜尋應用程式或套件名稱", "Search apps or package")) },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
            }
            items(filtered, key = XAppEntry::packageName) { app ->
                val checked = app.packageName == selected
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem().clickable {
                        selected = app.packageName
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(
                                if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                color = if (checked) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { if (selected.isNotBlank()) onSave(selected) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
                .alpha(if (selected.isBlank()) 0.45f else 1f),
            shape = CircleShape,
            containerColor = Color(0xFF2E7D32),
            contentColor = Color.White,
        ) {
            Text("✓", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GesturesPage(
    language: UiLanguage,
    config: ModuleConfig,
    apps: List<XAppEntry>,
    updateConfig: ((ModuleConfig) -> ModuleConfig) -> Unit,
    onSelectApplication: (AppPickerTarget) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle(
                l(language, "手势设置", "手勢設定", "Gesture settings"),
                l(language, "全局、普通模式与手写笔鼠标", "全域、普通模式與手寫筆滑鼠", "Global, normal mode, and stylus mouse"),
            )
        }
        item {
            SettingSurface {
                SwitchRow(
                    title = l(language, "启用全局动作", "啟用全域動作", "Enable global actions"),
                    detail = l(
                        language,
                        "控制轻捏四次与轻捏四次并按住",
                        "控制輕捏四次與輕捏四次並按住",
                        "Controls four pinches and four-pinches-and-hold",
                    ),
                    checked = config.globalActionsEnabled,
                    onChecked = { enabled -> updateConfig { it.copy(globalActionsEnabled = enabled) } },
                )
                Text(
                    if (config.globalActionsEnabled) {
                        l(
                            language,
                            "已开启：会接管全局轻捏序列，只保证一次性激光画笔。黑名单内的小米原版激光功能不受影响。",
                            "已開啟：會接管全域輕捏序列，只保證一次性雷射畫筆。黑名單內的小米原版雷射功能不受影響。",
                            "Enabled: intercepts global pinch sequences and guarantees one-shot laser drawing only. Blacklisted apps are unaffected.",
                        )
                    } else {
                        l(
                            language,
                            "已关闭：停止全局四捏识别，其他手势设置仍然有效。",
                            "已關閉：停止全域四捏辨識，其他手勢設定仍然有效。",
                            "Disabled: global four-pinch recognition stops; other gesture settings remain active.",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.globalActionsEnabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            PointerFeelSettings(language, config, updateConfig)
        }
        item {
            SettingSurface {
                Text(l(language, "鼠标滚轮幅度", "滑鼠滾輪幅度", "Mouse wheel distance"), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ConfigContract.SCROLL_SHORT to l(language, "短", "短", "Short"),
                        ConfigContract.SCROLL_MEDIUM to l(language, "中", "中", "Medium"),
                        ConfigContract.SCROLL_LONG to l(language, "长", "長", "Long"),
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = config.scrollAmount == value,
                            onClick = { updateConfig { it.copy(scrollAmount = value) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            AnimatedVisibility(
                visible = config.globalActionsEnabled,
                enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) + fadeIn(),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(l(language, "全局动作", "全域動作", "Global actions"))
                    SettingSurface {
                        GlobalActionEditor(
                            language, l(language, "轻捏四次", "輕捏四次", "Four pinches"),
                            config.tripleAction,
                            apps.firstOrNull { it.packageName == config.triplePackage }?.label,
                            onAction = { action -> updateConfig { it.copy(tripleAction = action) } },
                            onChooseApplication = { onSelectApplication(AppPickerTarget.FOUR_TAP) },
                        )
                        HorizontalDivider()
                        GlobalActionEditor(
                            language, l(language, "轻捏四次并按住", "輕捏四次並按住", "Four pinches and hold"),
                            config.fourHoldAction,
                            apps.firstOrNull { it.packageName == config.fourHoldPackage }?.label,
                            onAction = { action -> updateConfig { it.copy(fourHoldAction = action) } },
                            onChooseApplication = { onSelectApplication(AppPickerTarget.FOUR_HOLD) },
                        )
                    }
                }
            }
        }
        item { SectionTitle(l(language, "普通模式（激光关闭时）", "普通模式（雷射關閉時）", "Normal mode (laser off)")) }
        item {
            GestureGroup(
                language = language,
                rows = normalGestureIds(language),
                allowedActions = normalActionIds,
                config = config,
                updateConfig = updateConfig,
            )
        }
        item { SectionTitle(l(language, "手写笔鼠标", "手寫筆滑鼠", "Stylus mouse")) }
        item { PinchModelCard(language) }
        item {
            GestureGroup(
                language = language,
                rows = laserGestureIds(language),
                allowedActions = laserActionIds,
                config = config,
                updateConfig = updateConfig,
            )
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun PointerFeelSettings(
    language: UiLanguage,
    config: ModuleConfig,
    updateConfig: ((ModuleConfig) -> ModuleConfig) -> Unit,
) {
    var sensitivity by remember(config.pointerSensitivity) {
        mutableFloatStateOf(config.pointerSensitivity.toFloat())
    }
    var smoothing by remember(config.pointerSmoothing) {
        mutableFloatStateOf(config.pointerSmoothing.toFloat())
    }
    var lockMs by remember(config.pressLockMs) {
        mutableFloatStateOf(config.pressLockMs.toFloat())
    }
    SettingSurface {
        Text(l(language, "指针手感", "指標手感", "Pointer feel"), fontWeight = FontWeight.SemiBold)
        Text(
            l(
                language,
                "只作用于手写笔鼠标。灵敏度越低，指针越慢、越好对准；平滑越高，手抖越少，但会稍微跟手慢一点。",
                "只作用於手寫筆滑鼠。靈敏度越低，指標越慢、越好對準；平滑越高，手抖越少，但會稍微跟手慢一點。",
                "Stylus mouse only. Lower sensitivity makes the pointer slower and easier to aim; more smoothing removes tremor at the cost of slight lag.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PercentSlider(
            title = l(language, "灵敏度", "靈敏度", "Sensitivity"),
            value = sensitivity,
            range = ConfigContract.MIN_POINTER_SENSITIVITY.toFloat()..
                ConfigContract.MAX_POINTER_SENSITIVITY.toFloat(),
            stepSize = 5,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = {
                updateConfig { it.copy(pointerSensitivity = sensitivity.roundToInt()) }
            },
        )
        PercentSlider(
            title = l(language, "平滑", "平滑", "Smoothing"),
            value = smoothing,
            range = 0f..100f,
            stepSize = 5,
            onValueChange = { smoothing = it },
            onValueChangeFinished = {
                updateConfig { it.copy(pointerSmoothing = smoothing.roundToInt()) }
            },
            zeroLabel = l(language, "关闭", "關閉", "Off"),
        )
        PercentSlider(
            title = l(language, "防抖时长", "防抖時長", "Anti-jitter time"),
            value = lockMs,
            range = ConfigContract.MIN_PRESS_LOCK_MS.toFloat()..ConfigContract.MAX_PRESS_LOCK_MS.toFloat(),
            stepSize = 10,
            onValueChange = { lockMs = it },
            onValueChangeFinished = {
                updateConfig { it.copy(pressLockMs = lockMs.roundToInt()) }
            },
            zeroLabel = l(language, "关闭", "關閉", "Off"),
            unit = "ms",
        )
        Text(
            l(
                language,
                "捏笔时笔身会先往下一歪。捏下时指针先退回到捏之前的位置，之后这段时间内手指保持不动。点击偏移就调大；调得太大会影响侧边返回。",
                "捏筆時筆身會先往下一歪。捏下時指標先退回到捏之前的位置，之後這段時間內手指保持不動。點擊偏移就調大；調得太大會影響側邊返回。",
                "Squeezing tilts the pen. On a pinch the pointer first returns to where it was before the squeeze, then the finger stays put for this long. Increase it if taps drift; edge back gestures need it short.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            title = l(language, "指针可到达屏幕边缘", "指標可到達螢幕邊緣", "Pointer reaches screen edges"),
            detail = l(
                language,
                "解除小米激光离屏幕边缘约 8dp 的限制，从边缘捏住滑动即可返回、回桌面、呼出 Dock",
                "解除小米雷射離螢幕邊緣約 8dp 的限制，從邊緣捏住滑動即可返回、回桌面、叫出 Dock",
                "Lift Xiaomi's ~8dp edge margin so pinch-swipes from an edge trigger back, home and the dock",
            ),
            checked = config.reachScreenEdges,
            onChecked = { enabled -> updateConfig { it.copy(reachScreenEdges = enabled) } },
        )
        TextButton(
            onClick = {
                updateConfig {
                    it.copy(
                        reachScreenEdges = true,
                        pointerSensitivity = ConfigContract.DEFAULT_POINTER_SENSITIVITY,
                        pointerSmoothing = ConfigContract.DEFAULT_POINTER_SMOOTHING,
                        pressLockMs = ConfigContract.DEFAULT_PRESS_LOCK_MS,
                    )
                }
            },
        ) { Text(l(language, "恢复默认手感", "恢復預設手感", "Reset pointer feel")) }
    }
}

@Composable
private fun PinchModelCard(language: UiLanguage) {
    SettingSurface {
        Text(l(language, "捏笔就是一根手指", "捏筆就是一根手指", "The pinch is a finger"), fontWeight = FontWeight.SemiBold)
        Text(
            l(
                language,
                "捏下 = 手指在指针处按下，移动 = 手指滑动，松开 = 手指抬起。点击、长按、滚动都由应用自己识别，和手指一模一样。",
                "捏下 = 手指在指標處按下，移動 = 手指滑動，放開 = 手指抬起。點擊、長按、捲動都由應用程式自己辨識，和手指一模一樣。",
                "Pinch = finger down at the pointer, move = finger slides, release = finger up. Apps recognise taps, long presses and scrolling exactly as for a real finger.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            l(language, "轻捏一下", "輕捏一下", "Pinch") to l(language, "点击", "點擊", "Tap"),
            l(language, "快速捏两下", "快速捏兩下", "Pinch twice") to l(language, "双击", "雙擊", "Double tap"),
            l(language, "捏住不动", "捏住不動", "Pinch and hold") to l(language, "长按", "長按", "Long press"),
            l(language, "捏住并移动", "捏住並移動", "Pinch and move") to l(language, "滑动、滚动、拖动", "滑動、捲動、拖動", "Swipe, scroll, drag"),
            l(language, "在屏幕边缘捏住滑动", "在螢幕邊緣捏住滑動", "Pinch-swipe from an edge") to l(language, "返回、回桌面、呼出 Dock", "返回、回桌面、叫出 Dock", "Back, home, dock"),
            l(language, "手指触屏", "手指觸控螢幕", "Touch the screen") to l(language, "退出手写笔鼠标", "退出手寫筆滑鼠", "Leave the stylus pointer"),
        ).forEach { (gesture, effect) ->
            Row(Modifier.fillMaxWidth()) {
                Text(gesture, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    effect,
                    Modifier.weight(0.58f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PercentSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    stepSize: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    zeroLabel: String? = null,
    unit: String = "%",
) {
    val rounded = value.roundToInt()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(
            if (rounded == 0 && zeroLabel != null) zeroLabel else "$rounded$unit",
            fontWeight = FontWeight.SemiBold,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
        steps = ((range.endInclusive - range.start) / stepSize).roundToInt() - 1,
    )
}

@Composable
private fun SettingSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun GlobalActionEditor(
    language: UiLanguage,
    title: String,
    action: String,
    selectedApplication: String?,
    onAction: (String) -> Unit,
    onChooseApplication: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        DropdownSelector(
            currentLabel = actionLabel(language, action),
            values = globalActionIds,
            label = { actionLabel(language, it) },
            onSelect = onAction,
        )
        if (action == ConfigContract.TRIPLE_ACTION_LAUNCH_APP) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onChooseApplication),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedApplication != null) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                selectedApplication.firstOrNull()?.uppercase() ?: "?",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(selectedApplication, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(l(language, "更改软件", "更改應用程式", "Change app"), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            l(language, "选择应用", "選擇應用程式", "Select app"),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureGroup(
    language: UiLanguage,
    rows: List<Pair<String, String>>,
    allowedActions: List<String>,
    config: ModuleConfig,
    updateConfig: ((ModuleConfig) -> ModuleConfig) -> Unit,
) {
    SettingSurface {
        rows.forEachIndexed { index, (gesture, title) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                DropdownSelector(
                    currentLabel = actionLabel(
                        language,
                        config.gestureActions[gesture]
                            ?: ConfigContract.DEFAULT_GESTURE_ACTIONS.getValue(gesture),
                    ),
                    values = allowedActionsForGesture(gesture, allowedActions),
                    label = { actionLabel(language, it) },
                    onSelect = { action ->
                        updateConfig { current ->
                            current.copy(gestureActions = current.gestureActions + (gesture to action))
                        }
                    },
                )
            }
            if (index != rows.lastIndex) HorizontalDivider()
        }
    }
}

private fun allowedActionsForGesture(gesture: String, normal: List<String>): List<String> =
    when (gesture) {
        ConfigContract.GESTURE_OFF_DOUBLE_TAP,
        ConfigContract.GESTURE_OFF_DOUBLE_HOLD,
        -> (normal + ConfigContract.TRIPLE_ACTION_ENABLE_LASER).distinct()
        else -> normal
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> DropdownSelector(
    currentLabel: String,
    values: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.clickable { expanded = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                currentLabel,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) { DownChevronGlyph() }
            }
        }
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(values) { value ->
                        val optionLabel = label(value)
                        val checked = optionLabel == currentLabel
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expanded = false
                                onSelect(value)
                            },
                            shape = RoundedCornerShape(17.dp),
                            color = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(optionLabel, Modifier.weight(1f), fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
                                if (checked) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownChevronGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.2.dp.toPx()
        val centerX = size.width * 0.5f
        val pointY = size.height * 0.63f
        val armY = size.height * 0.36f
        val armOffset = size.width * 0.27f
        drawLine(
            color,
            Offset(centerX - armOffset, armY),
            Offset(centerX, pointY),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(centerX, pointY),
            Offset(centerX + armOffset, armY),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun OtherPage(
    language: UiLanguage,
    platformProfile: String,
    onPlatformProfile: (String) -> Unit,
    onLanguage: (UiLanguage) -> Unit,
    onCurrentProject: () -> Unit,
    onUpstreamProject: () -> Unit,
    onResetGestures: () -> Unit,
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            PageTitle(
                l(language, "其他", "其他", "Other"),
                l(language, "语言、项目地址和相关作品", "語言、專案地址和相關作品", "Language, projects, and related work"),
            )
        }
        item {
            SettingSurface {
                Text(l(language, "界面语言", "介面語言", "Interface language"), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiLanguage.entries.forEach { item ->
                        FilterChip(
                            selected = language == item,
                            onClick = { onLanguage(item) },
                            label = {
                                Text(
                                    when (item) {
                                        UiLanguage.SIMPLIFIED -> "简体中文"
                                        UiLanguage.TRADITIONAL -> "繁體中文"
                                        UiLanguage.ENGLISH -> "English"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingSurface {
                Text(
                    l(language, "系统版本适配", "系統版本適配", "System version profile"),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    l(
                        language,
                        "自动识别：${detectedPlatformLabel(language)}。手动选择对应适配路径；若入口不可用会尝试备用入口，全部不可用时交还系统处理。",
                        "自動識別：${detectedPlatformLabel(language)}。手動選擇對應適配路徑；若入口不可用會嘗試備用入口，全部不可用時交由系統處理。",
                        "Detected: ${detectedPlatformLabel(language)}. Manual selection chooses an adapter; it tries the fallback path and fails open if neither is available.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ConfigContract.PLATFORM_AUTO to l(language, "自动", "自動", "Auto"),
                        ConfigContract.PLATFORM_OS3 to "HyperOS 3",
                        ConfigContract.PLATFORM_OS4 to "HyperOS 4",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = platformProfile == value,
                            onClick = { onPlatformProfile(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item { SectionTitle(l(language, "项目", "專案", "Projects")) }
        item {
            ProjectCard(
                symbol = "P",
                title = "FocusPen Pointer",
                detail = l(
                    language,
                    "把小米焦点触控笔 Pro 变成隔空指针和一根手指 · SKI-NIGHT",
                    "把小米焦點觸控筆 Pro 變成隔空指標和一根手指 · SKI-NIGHT",
                    "Turns the Xiaomi Focus Pen Pro into an air pointer and a finger · SKI-NIGHT",
                ),
                action = l(language, "现在就去", "現在就去", "Go now"),
                onClick = onCurrentProject,
            )
        }
        item {
            ProjectCard(
                symbol = "♡",
                title = l(language, "基于 FocusPen Pro X", "基於 FocusPen Pro X", "Based on FocusPen Pro X"),
                detail = l(
                    language,
                    "本项目源自 HMQYHM 的 FocusPen Pro X，按 GPL-3.0 协议开源。",
                    "本專案源自 HMQYHM 的 FocusPen Pro X，依 GPL-3.0 授權開源。",
                    "Derived from FocusPen Pro X by HMQYHM, open source under GPL-3.0.",
                ),
                action = l(language, "查看原项目", "查看原專案", "View original"),
                onClick = onUpstreamProject,
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showResetConfirmation = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "↺",
                                modifier = Modifier.graphicsLayer { translationY = -3.5.dp.toPx() },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l(language, "恢复默认手势", "恢復預設手勢", "Restore default gestures"), fontWeight = FontWeight.SemiBold)
                        Text(
                            l(language, "不会清空黑名单；全局四次轻捏默认关闭", "不會清空黑名單；全域四次輕捏預設關閉", "Keeps the blacklist; global four-pinches will be disabled"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Text(
                "FocusPen Pointer ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(l(language, "恢复默认手势？", "恢復預設手勢？", "Restore default gestures?")) },
            text = {
                Text(l(language, "所有动作映射和滚轮幅度将恢复默认，全局四次轻捏将关闭。", "所有動作映射與滾輪幅度將恢復預設，全域四次輕捏將關閉。", "All gesture mappings and scroll distance will be reset. Global four-pinches will be disabled."))
            },
            confirmButton = {
                Button(onClick = {
                    showResetConfirmation = false
                    onResetGestures()
                }) { Text(l(language, "恢复", "恢復", "Restore")) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(l(language, "取消", "取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    symbol: String,
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(symbol, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private val normalActionIds = listOf(
    ConfigContract.ACTION_SYSTEM,
    ConfigContract.ACTION_CONSUME,
    ConfigContract.ACTION_VOLUME_UP,
    ConfigContract.ACTION_VOLUME_DOWN,
    ConfigContract.ACTION_BACK,
    ConfigContract.ACTION_HOME,
    ConfigContract.ACTION_RECENTS,
    ConfigContract.ACTION_SCROLL_UP,
    ConfigContract.ACTION_SCROLL_DOWN,
)

private val laserActionIds = listOf(
    ConfigContract.ACTION_SYSTEM,
    ConfigContract.ACTION_CONSUME,
    ConfigContract.ACTION_VOLUME_UP,
    ConfigContract.ACTION_VOLUME_DOWN,
    ConfigContract.ACTION_BACK,
    ConfigContract.ACTION_HOME,
    ConfigContract.ACTION_RECENTS,
    ConfigContract.ACTION_SCROLL_UP,
    ConfigContract.ACTION_SCROLL_DOWN,
)

private val globalActionIds = listOf(
    ConfigContract.TRIPLE_ACTION_NONE,
    ConfigContract.ACTION_CONSUME,
    ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
    ConfigContract.TRIPLE_ACTION_LAUNCH_APP,
    ConfigContract.ACTION_VOLUME_UP,
    ConfigContract.ACTION_VOLUME_DOWN,
    ConfigContract.ACTION_BACK,
    ConfigContract.ACTION_HOME,
    ConfigContract.ACTION_RECENTS,
)

private fun normalGestureIds(language: UiLanguage) = listOf(
    ConfigContract.GESTURE_OFF_DOUBLE_TAP to l(language, "轻捏两次", "輕捏兩次", "Two pinches"),
    ConfigContract.GESTURE_OFF_DOUBLE_HOLD to l(language, "轻捏两次并按住", "輕捏兩次並按住", "Two pinches and hold"),
    ConfigContract.GESTURE_OFF_SWIPE_UP to l(language, "从笔尖向上滑", "從筆尖向上滑", "Slide up from tip"),
    ConfigContract.GESTURE_OFF_SWIPE_DOWN to l(language, "从上向笔尖滑", "從上向筆尖滑", "Slide down toward tip"),
)

private fun laserGestureIds(language: UiLanguage) = listOf(
    ConfigContract.GESTURE_SWIPE_UP to l(language, "从笔尖向上滑", "從筆尖向上滑", "Slide up from tip"),
    ConfigContract.GESTURE_SWIPE_DOWN to l(language, "从上向笔尖滑", "從上向筆尖滑", "Slide down toward tip"),
)

private fun actionLabel(language: UiLanguage, action: String): String = when (action) {
    ConfigContract.ACTION_SYSTEM -> l(language, "不处理，交给系统", "不處理，交給系統", "Do not handle")
    ConfigContract.ACTION_CONSUME -> l(language, "仅消费", "僅攔截", "Consume only")
    ConfigContract.ACTION_VOLUME_UP -> l(language, "音量增加", "提高音量", "Volume up")
    ConfigContract.ACTION_VOLUME_DOWN -> l(language, "音量降低", "降低音量", "Volume down")
    ConfigContract.ACTION_BACK -> l(language, "返回", "返回", "Back")
    ConfigContract.ACTION_HOME -> l(language, "回到桌面", "回到桌面", "Home")
    ConfigContract.ACTION_RECENTS -> l(language, "最近任务", "最近任務", "Recents")
    ConfigContract.ACTION_SCROLL_UP -> l(language, "滚轮向上", "滾輪向上", "Scroll up")
    ConfigContract.ACTION_SCROLL_DOWN -> l(language, "滚轮向下", "滾輪向下", "Scroll down")
    ConfigContract.TRIPLE_ACTION_ENABLE_LASER -> l(language, "开启虚拟激光或手写笔鼠标", "開啟虛擬雷射或手寫筆滑鼠", "Enable virtual laser or stylus mouse")
    ConfigContract.TRIPLE_ACTION_LAUNCH_APP -> l(language, "启动应用", "啟動應用程式", "Launch app")
    ConfigContract.TRIPLE_ACTION_NONE -> l(language, "不启用", "不啟用", "Disabled")
    else -> action
}

private fun loadXApps(activity: MainActivity): List<XAppEntry> {
    val packageManager = activity.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherPackages = packageManager.queryIntentActivities(launcherIntent, 0)
        .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    return packageManager.getInstalledApplications(0)
        .mapNotNull { info ->
            runCatching {
                XAppEntry(
                    label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                    packageName = info.packageName,
                    hasLauncher = info.packageName in launcherPackages,
                    // Factory-preinstalled system apps do not auto-expand the list.
                    autoExpandOnSelection =
                        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                )
            }.getOrNull()
        }
        .distinctBy(XAppEntry::packageName)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, XAppEntry::label))
}

private fun readXRuntime(activity: MainActivity): XRuntime = runCatching {
    val bundle = activity.contentResolver.call(
        ConfigContract.URI,
        ConfigContract.METHOD_GET_RUNTIME,
        null,
        null,
    ) ?: return XRuntime()
    XRuntime(
        active = bundle.getBoolean(ConfigContract.KEY_RUNTIME_ACTIVE),
        compatible = bundle.getBoolean(ConfigContract.KEY_RUNTIME_COMPATIBLE),
        message = bundle.getString(ConfigContract.KEY_RUNTIME_MESSAGE).orEmpty(),
        laser = bundle.getBoolean(ConfigContract.KEY_RUNTIME_LASER),
        foreground = bundle.getString(ConfigContract.KEY_RUNTIME_FOREGROUND).orEmpty(),
        events = bundle.getStringArrayList(ConfigContract.KEY_RUNTIME_EVENTS).orEmpty(),
    )
}.getOrDefault(XRuntime())

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
