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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    // 追踪从底部中央"追踪"大按钮进入，不放在 tab 里。
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
            // 底部中央"追踪"大按钮（home 和 profile 可见）
            if (currentRoute in topTabs) {
                CenterTrackingButton(navController)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
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
