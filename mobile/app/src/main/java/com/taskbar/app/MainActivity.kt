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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.taskbar.app.server.SyncService
import com.taskbar.app.ui.*
import com.taskbar.app.ui.TGColors

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
            // 底部导航栏：只放"追踪"tab（居中）；我的/主页走顶部（跟以前一样）
            if (currentRoute in listOf("home", "profile", "track")) {
                val trackingCount by vm.tracking.collectAsState()
                BottomAppBar(
                    containerColor = TGColors.PanelSolid,
                    tonalElevation = 2.dp
                ) {
                    // 差分化：home/profile 时显示追踪（与任务行追踪键同款靶心 ic_track），
                    // 进入 track 路由后变成"主页"（房子 ic_home），点它 popBackStack 回主页
                    val isOnTrack = currentRoute == "track"
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationBarItem(
                        selected = isOnTrack,
                        onClick = {
                            if (isOnTrack) {
                                // 在追踪页：点主页图标 → 返回主页（popBackStack）
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
                        icon = {
                            Box {
                                if (isOnTrack) {
                                    TGIcon(R.drawable.ic_home, contentDescription = "主页", tint = TGColors.GoldDeep, size = 24.dp)
                                } else {
                                    TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = TGColors.InkSoft, size = 24.dp)
                                }
                                // 追踪数角标（仅非 track 路由显示，差分化颜色：profile 紫 / home 金深）
                                if (!isOnTrack && trackingCount.size > 0) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (currentRoute == "profile") TGColors.Violet else TGColors.GoldDeep)
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (trackingCount.size > 9) "9+" else trackingCount.size.toString(),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        },
                        label = { Text(if (isOnTrack) "主页" else "追踪", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TGColors.GoldDeep,
                            selectedTextColor = TGColors.GoldDeep,
                            indicatorColor = TGColors.Selected.copy(alpha = 0.6f),
                            unselectedIconColor = TGColors.InkSoft,
                            unselectedTextColor = TGColors.InkSoft
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = "home",
                // 显式转场 + 全局底部 padding 80dp 让出追踪键（FAB 位置）+ nav bar
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .consumeWindowInsets(WindowInsets.navigationBars),
                // 全部页面切换无动画（boss 嫌默认"右上角移下来"的动画影响体验）
                enterTransition = { androidx.compose.animation.EnterTransition.None },
                exitTransition = { androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                popExitTransition = { androidx.compose.animation.ExitTransition.None }
        ) {
            composable("home") { TaskListScreen(vm, navController) }
            composable("track") { TrackScreen(vm) }
            composable("habit") { HabitScreen(vm) }
            composable("profile") { ProfileScreen(vm, navController) }
            composable("settings") { SettingsScreen(vm, navController) }
            composable("all") { AllTasksScreen(vm, navController) }
            composable("history") { HistoryScreen(vm, navController) }
            composable(
                "detail/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType })
            ) { entry ->
                TaskDetailScreen(vm, navController, entry.arguments?.getString("uuid") ?: "")
            }
            composable("add") { AddEditTaskScreen(vm, navController, null) }
            composable(
                "edit/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType })
            ) { entry ->
                AddEditTaskScreen(vm, navController, entry.arguments?.getString("uuid"))
            }
        }
    }
    }

    // 完成庆祝弹层（游戏化正反馈：完成任务弹道具式积分/升级）
    val completion by vm.completion.collectAsState()
    completion?.let { ev ->
        CompletionCelebration(
            title = ev.title,
            points = ev.points,
            newLevel = ev.newLevel,
            newLevelName = ev.newLevelName,
            onDismiss = { vm.consumeCompletion() }
        )
    }
}
