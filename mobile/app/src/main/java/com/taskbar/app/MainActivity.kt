package com.taskbar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry
import com.taskbar.app.server.SyncService
import com.taskbar.app.ui.*
import com.taskbar.app.ui.TGColors

/**
 * v5.15.22 M4 + v5.15.23 M1/M3/M4（boss：「页面切换的 UI 还是没加上」「只做了主页和追踪和我的，
 * 其他的呢」「现在太快了慢一点点」「方向应该按页面关系设计，比如"我的"是左边，追踪和主页按键都在下面」）
 *
 * 页面带的物理顺序（与入口位置对应）：
 *   我的(0, 左上角头像入口) ← 主页(1, 底部中央) → 追踪(2, 底部凸起键)
 * 转场规则：
 *   · 主页面带内互切：按左右方向滑动（往右翻 → 新页从右侧进；往左翻 → 从左侧进）
 *   · 进下级页面（所有任务/历史/详情/新建/设置…）：新页从**右侧推入**，当前页向左退出
 *   · 从下级页面返回：下级页向右退出，主页面从**左侧**推回
 *   · 时长 320ms（v5.15.22 的 220ms 被 boss 判为"太快"）
 */
private val MAIN_PAGE_ORDER = listOf("profile", "home", "track")
// v5.15.25 N2（boss：「点了按钮过了一下才能切换过去 再改快一点点」）—— 320 → 240ms
private const val PAGE_ANIM_MS = 240

private fun pageIndex(route: String?): Int = MAIN_PAGE_ORDER.indexOf(route)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.appEnter(): EnterTransition {
    val from = pageIndex(initialState.destination.route)
    val to = pageIndex(targetState.destination.route)
    if (from >= 0 && to >= 0) {
        return slideIntoContainer(
            if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right,
            tween(PAGE_ANIM_MS)
        )
    }
    // 进下级页面 → 从右侧推入；回主页面带 → 从左侧推回
    return if (to < 0) slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(PAGE_ANIM_MS))
    else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(PAGE_ANIM_MS))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.appExit(): ExitTransition {
    val from = pageIndex(initialState.destination.route)
    val to = pageIndex(targetState.destination.route)
    if (from >= 0 && to >= 0) {
        return slideOutOfContainer(
            if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right,
            tween(PAGE_ANIM_MS)
        )
    }
    // 去下级页面 → 当前页向左退出；回主页面带 → 当前页向右退出
    return if (to < 0) slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(PAGE_ANIM_MS))
    else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(PAGE_ANIM_MS))
}

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            TaskGuideTheme {
                // 延迟启动同步服务：等 UI 起来 1.5s 后再启动，
                // 这样 SyncService 启动期异常不会连带把 application 干掉，
                // UI 至少能展示给用户 + crash.log 能记录现场
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    runCatching { SyncService.start(this@MainActivity) }
                        .onFailure { Log.e("MainActivity", "SyncService 延迟启动失败", it) }
                    // v5.15.23 C-006：一次性修掉"今天已打卡、却还挂在追踪中"的习惯
                    //   （这类行会让完成键点了没反应 —— boss 的「完成不掉」）
                    runCatching { com.taskbar.app.TaskBarApp.instance.repo.repairStuckTrackedHabits() }
                        .onFailure { Log.e("MainActivity", "修复卡住的习惯失败", it) }
                }
                MainApp()
            }
        }
    }
}

/** icon 传的是矢量 drawable 资源 id（不是 emoji / Unicode 符号） */
private data class TabItem(val route: String, val label: String, val icon: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val vm: TaskViewModel = viewModel()

        // 顶层 tab：home（主页）、profile（我的，用 AppTopBar 里的 AvatarFrame）。
        // 追踪从底部导航栏的追踪 tab 进入，不放在顶部 tab 里。
        val topTabs = listOf("home", "profile")

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 顶部 tab 栏 + 标题栏：home/profile 显示完整顶栏，track 显示简单标题
            if (currentRoute in topTabs || currentRoute == "track") {
                AppTopBar(currentRoute, vm, navController)
            }
        },
        bottomBar = {
            // 底部导航栏：矮条（58dp）+ 中央凸起圆形追踪钮（boss 要"凸出来+面积小+设计感"）
            // 追踪钮向上凸出 18dp 形成"悬浮凸起"效果；home/profile 显示 ic_track 靶心，track 路由变 ic_home 房子
            if (currentRoute in listOf("home", "profile", "track")) {
                val trackingCount by vm.tracking.collectAsState()
                val isOnTrack = currentRoute == "track"
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .graphicsLayer { clip = false }  // 关键：v5.15.5 让凸起圆 + badge 30dp 完全突出 58dp 边界外
                        .background(TGColors.PanelSolid)
                ) {
                    // 顶部分隔细线（设计感细节）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(TGColors.BorderSoft)
                            .align(Alignment.TopCenter)
                    )
                    // 凸起圆形按钮（v5.15.5：clip(CircleShape) 仅裁 background；badge 在外层 Box）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-22).dp)  // 向上凸出 22dp
                            .size(56.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = androidx.compose.foundation.shape.CircleShape,
                                ambientColor = TGColors.Gold.copy(alpha = 0.4f),
                                spotColor = TGColors.Gold.copy(alpha = 0.5f)
                            )
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        TGColors.GoldLight,
                                        TGColors.Gold
                                    )
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                color = Color.White.copy(alpha = 0.6f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .clickable {
                                if (isOnTrack) {
                                    if (!navController.popBackStack("home", inclusive = false)) {
                                        navController.navigate("home") { launchSingleTop = true }
                                    }
                                } else {
                                    navController.navigate("track") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isOnTrack) {
                            TGIcon(R.drawable.ic_home, contentDescription = "主页", tint = Color.White, size = 26.dp)
                        } else {
                            TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = Color.White, size = 24.dp)
                        }
                    }
                    // v5.15.5：追踪数 pill 角标 — 移到凸起圆外层 Box（避开 56dp 凸起圆 clip 裁切）
                    if (!isOnTrack && trackingCount.size > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(x = 24.dp, y = 0.dp)  // 凸起圆中心 540 → badge 居中靠右 24dp（30dp 圆露出 4dp 在圆右上）
                                .size(30.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(TGColors.Crimson)
                                .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (trackingCount.size > 9) "9+" else trackingCount.size.toString(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // v5.15.22 M4：主轴三页左右滑动切换（72dp 触发阈值；纵向滚动不受影响）
                .pointerInput(currentRoute) {
                    val idx = pageIndex(currentRoute)
                    if (idx < 0) return@pointerInput
                    val threshold = 72.dp.toPx()
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                        onDragCancel = { total = 0f },
                        onDragEnd = {
                            val target = when {
                                total <= -threshold -> idx + 1
                                total >= threshold -> idx - 1
                                else -> -1
                            }
                            if (target in MAIN_PAGE_ORDER.indices) {
                                navController.navigate(MAIN_PAGE_ORDER[target]) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            total = 0f
                        }
                    )
                }
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                // 显式转场 + 全局底部 padding 80dp 让出追踪键（FAB 位置）+ nav bar
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .consumeWindowInsets(WindowInsets.navigationBars),
                // v5.15.22 M4 + v5.15.23 M1/M4：主页面带按方向滑动；下级页面右侧推入/退出。
                //   其余页面**不再**保持无动画（boss：「你只做了主页和追踪和我的，其他的呢」）。
                enterTransition = { appEnter() },
                exitTransition = { appExit() },
                popEnterTransition = { appEnter() },
                popExitTransition = { appExit() }
        ) {
            // v5.15.10：每个页面自带**不透明**背景（与原根背景同一个 brush）。
            // 起因：NavHost 切页时新旧两个 destination 会同时绘制一两帧，而各页面 Scaffold 都是
            //   containerColor = Transparent → 上一页（任务列表等）透出来 = boss 说的"切换有卡顿/页面残留"。
            composable("home") { ScreenSurface { TaskListScreen(vm, navController) } }
            composable("track") { ScreenSurface { TrackScreen(vm) } }
            composable("habit") { ScreenSurface { HabitScreen(vm) } }
            composable("profile") { ScreenSurface { ProfileScreen(vm, navController) } }
            composable("settings") { ScreenSurface { SettingsScreen(vm, navController) } }
            composable("all") { ScreenSurface { AllTasksScreen(vm, navController) } }
            composable("history") { ScreenSurface { HistoryScreen(vm, navController) } }
            composable(
                "detail/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType })
            ) { entry ->
                ScreenSurface { TaskDetailScreen(vm, navController, entry.arguments?.getString("uuid") ?: "") }
            }
            // v5.15.22 M9：日历里点进来的**只读**详情（没有任何操作键，防刷分）
            composable(
                "ro/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType })
            ) { entry ->
                ScreenSurface { TaskDetailScreen(vm, navController, entry.arguments?.getString("uuid") ?: "", readOnly = true) }
            }
            composable("add") { ScreenSurface { AddEditTaskScreen(vm, navController, null) } }
            composable(
                "edit/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType })
            ) { entry ->
                ScreenSurface { AddEditTaskScreen(vm, navController, entry.arguments?.getString("uuid")) }
            }
        }
    }
    }

    // 完成庆祝弹层（游戏化正反馈：完成任务弹道具式积分/升级）
    val completion by vm.completion.collectAsState()
    completion?.let { ev ->
        // v5.15.21 P1：次数任务 → 轻量自动消失提示；其余任务 → 原来的整屏庆祝
        if (ev.lightweight) {
            CompletionToast(
                title = ev.title,
                points = ev.points,
                onDismiss = { vm.consumeCompletion() }
            )
        } else {
            CompletionCelebration(
                title = ev.title,
                points = ev.points,
                newLevel = ev.newLevel,
                newLevelName = ev.newLevelName,
                onDismiss = { vm.consumeCompletion() }
            )
        }
    }
}

/**
 * v5.15.10：页面外壳 —— 给每个 NavHost destination 铺一层**不透明**背景（与原根背景同一个 brush）。
 *
 * 背景：各页面 Scaffold 都是 containerColor = Transparent（沿用根背景的暖米色渐变），
 * 但 NavHost 切页时新旧两个 destination 会同时绘制 1~2 帧 —— 透明背景会让上一页（任务列表等）
 * 透出来，观感就是"切换有一点点卡顿 + 页面残留"。铺一层同款不透明底即可彻底遮住。
 */
@Composable
fun ScreenSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(com.taskbar.app.ui.TGBackgroundBrush)
    ) { content() }
}
