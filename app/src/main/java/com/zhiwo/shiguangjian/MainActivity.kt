package com.zhiwo.shiguangjian

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zhiwo.shiguangjian.alarm.AlarmScheduler
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import com.zhiwo.shiguangjian.ui.components.BottomNavBar
import com.zhiwo.shiguangjian.ui.components.navItems
import com.zhiwo.shiguangjian.ui.screens.*
import com.zhiwo.shiguangjian.ui.theme.ZhiwoTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.entries.forEach { (permission, granted) ->
            if (!granted) {
                when (permission) {
                    Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> {
                        Log.w("MainActivity", "日历权限被拒绝，日历同步功能将不可用")
                    }
                    Manifest.permission.POST_NOTIFICATIONS -> {
                        Log.w("MainActivity", "通知权限被拒绝，提醒功能将不可用")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        // 设置闹钟
        AlarmScheduler.scheduleAllAlarms(this)

        setContent {
            val app = application as ZhiwoApplication
            val settingsRepo = remember { SettingsRepository(app.database.settingDao()) }
            val darkMode by settingsRepo.getAllSettings()
                .map { settings -> settings.find { it.key == "darkMode" }?.value == "true" }
                .collectAsState(initial = false)

            ZhiwoTheme(darkTheme = darkMode) {
                MainApp()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 日历权限（用于日历同步功能）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_CALENDAR)
            permissions.add(Manifest.permission.WRITE_CALENDAR)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "records"

    // 不显示底部导航栏的路由
    val hideBottomBarRoutes = listOf("record_detail/{id}", "input", "organize")

    Scaffold(
        bottomBar = {
            if (currentRoute !in hideBottomBarRoutes && !currentRoute.startsWith("record_detail")) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo("records") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "input",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("input") {
                InputScreen(
                    onSaveSuccess = { recordId ->
                        navController.navigate("record_detail/$recordId") {
                            popUpTo("input") { inclusive = true }
                        }
                    },
                    onCancel = {
                        navController.navigate("records") {
                            popUpTo("input") { inclusive = true }
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    }
                )
            }

            composable("records") {
                RecordListScreen(
                    onRecordClick = { recordId ->
                        navController.navigate("record_detail/$recordId") {
                            launchSingleTop = true
                        }
                    },
                    onFabClick = {
                        navController.navigate("input") {
                            popUpTo("records") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(
                "record_detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getLong("id") ?: 0L
                RecordDetailScreen(
                    recordId = recordId,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate("records") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable("calendar") {
                CalendarScreen(
                    onRecordClick = { recordId ->
                        navController.navigate("record_detail/$recordId") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable("review") {
                ReviewScreen(
                    onNavigateToMemories = {
                        navController.navigate("memories") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable("memories") {
                MemoryScreen(
                    onNavigateToReview = {
                        navController.navigate("review") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable("organize") {
                OrganizeScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings") {
                SettingsScreen(
                    onNavigateToOrganize = {
                        navController.navigate("organize") {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
