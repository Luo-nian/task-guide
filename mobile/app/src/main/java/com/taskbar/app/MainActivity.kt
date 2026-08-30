package com.taskbar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

    val tabs = listOf(
        TabItem("today", "今天", R.drawable.ic_today),
        TabItem("track", "追踪", R.drawable.ic_track),
        TabItem("habit", "习惯", R.drawable.ic_habit),
        TabItem("archive", "归档", R.drawable.ic_archive),
        TabItem("settings", "设置", R.drawable.ic_settings)
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = Color.Transparent,   // 透出主题里的暖光渐变
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar(
                    containerColor = TGColors.PanelSolid,
                    tonalElevation = 3.dp
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo("today") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                TGIcon(
                                    drawable = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (currentRoute == tab.route) TGColors.GoldDeep else TGColors.InkMute,
                                    size = 20.dp
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TGColors.GoldDeep,
                                selectedTextColor = TGColors.GoldDeep,
                                unselectedIconColor = TGColors.InkMute,
                                unselectedTextColor = TGColors.InkMute,
                                indicatorColor = TGColors.Selected
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(padding)
        ) {
            composable("today") { TaskListScreen(vm, navController) }
            composable("track") { TrackScreen(vm, navController) }
            composable("habit") { HabitScreen(vm) }
            composable("archive") { ArchiveScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
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
