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
import com.zhiwo.shiguangjian.data.db.DbGate
import com.zhiwo.shiguangjian.data.db.DbState
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.components.BottomNavBar
import com.zhiwo.shiguangjian.ui.components.DailyGreetingOverlay
import com.zhiwo.shiguangjian.ui.screens.*
import com.zhiwo.shiguangjian.ui.theme.ZhiwoTheme
import com.zhiwo.shiguangjian.ui.viewmodel.SpecialDateViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

        setContent {
            val app = application as ZhiwoApplication
            val dbState by app.dbState.collectAsState()

            // 门没开到 Ready 之前不组 NavHost：各 ViewModel 构造期就会打开数据库，
            // 抢在门前面开库会让失败页赶不上第一次真失败
            when (val state = dbState) {
                DbState.Ready -> {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        app.appScope.launch { AlarmScheduler.syncFixedAlarms(this@MainActivity) }
                    }
                    ZhiwoRoot(app)
                }
                DbState.Checking -> DbCheckingScreen()
                is DbState.UpgradeFailed -> DbUpgradeFailedScreen(
                    info = state.info,
                    attempts = state.attempts,
                    onRetry = { coldRestart() },
                    onRebuildConfirmed = {
                        // 全应用只有这一条路会清库，而且要先打字确认
                        DbGate.requestRebuild(app)
                        coldRestart()
                    }
                )
            }
        }
    }

    /** 冷启动重来：迁移与清库都在下一个进程里做，避免继续用已被作废的懒加载单例 */
    private fun coldRestart() {
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(12)
    }

    @Composable
    private fun ZhiwoRoot(app: ZhiwoApplication) {
        val settingsRepo = remember { SettingsRepository(app.database.settingDao()) }
        val darkMode by settingsRepo.getAllSettings()
            .map { settings -> settings.find { it.key == "darkMode" }?.value ?: "auto" }
            .collectAsState(initial = "auto")
        val useDynamicColor by settingsRepo.getAllSettings()
            .map { settings -> settings.find { it.key == "dynamicColor" }?.value == "true" }
            .collectAsState(initial = false)

        ZhiwoTheme(darkMode = darkMode, useDynamicColor = useDynamicColor) {
            MainApp()
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

    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as ZhiwoApplication

    // 每日揭历：每天第一次打开应用时展示
    val specialDateViewModel: SpecialDateViewModel = viewModel()
    val showGreeting by specialDateViewModel.showGreeting.collectAsState()
    val greetingInfo by specialDateViewModel.greetingInfo.collectAsState()

    // 不显示底部导航栏的路由前缀：带参数的路由拿 destination.route 比对不上，先掐掉参数与占位符再判前缀
    val hideBottomBarPrefixes = listOf(
        "record_detail", "input", "organize", "diary", "special_dates", "user_profile",
        "goal_detail", "plan_detail", "goal_edit", "plan_edit", "superseded_memories"
    )
    val routeBase = currentRoute.substringBefore("?").substringBefore("/{")
    val hideBottomBar = hideBottomBarPrefixes.any { routeBase == it || routeBase.startsWith("$it/") }

    // 待确认记忆更正数：评价 Tab 角标（记忆入口在评价页，保证 under_review 可见性）
    val pendingCorrections by application.memoryReconciler.observePending()
        .collectAsState(initial = emptyList())

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (!hideBottomBar) {
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
                    },
                    pendingReviewBadge = pendingCorrections.size
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
                    },
                    onPlanClick = { planId -> navController.navigate("plan_detail/$planId") },
                    onGoalClick = { goalId -> navController.navigate("goal_detail/$goalId") }
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
                    },
                    onNavigateToDiary = {
                        navController.navigate("diary") {
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
                    },
                    onNavigateToSuperseded = {
                        navController.navigate("superseded_memories") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            // 已更正的记忆：恢复 / 彻底删除 / 清空（记忆页只留一行入口，不平铺列表）
            composable("superseded_memories") {
                SupersededMemoriesScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("organize") {
                OrganizeScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("diary") {
                DiaryScreen(
                    onNavigateToReview = {
                        navController.navigate("review") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable("settings") {
                SettingsScreen(
                    onNavigateToOrganize = {
                        navController.navigate("organize") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSpecialDates = {
                        navController.navigate("special_dates") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToUserProfile = {
                        navController.navigate("user_profile") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable("user_profile") {
                UserProfileScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("special_dates") {
                SpecialDatesScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "goal_detail/{goalId}",
                arguments = listOf(navArgument("goalId") { type = NavType.LongType })
            ) { backStackEntry ->
                GoalDetailScreen(
                    goalId = backStackEntry.arguments?.getLong("goalId") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onPlanClick = { planId -> navController.navigate("plan_detail/$planId") },
                    onEditGoal = { goalId -> navController.navigate("goal_edit?goalId=$goalId") },
                    onAddPlan = { goalId -> navController.navigate("plan_edit?planId=0&goalId=$goalId") }
                )
            }

            composable(
                "plan_detail/{planId}",
                arguments = listOf(navArgument("planId") { type = NavType.LongType })
            ) { backStackEntry ->
                PlanDetailScreen(
                    planId = backStackEntry.arguments?.getLong("planId") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onEditPlan = { planId, goalId ->
                        navController.navigate("plan_edit?planId=$planId&goalId=$goalId")
                    }
                )
            }

            composable(
                "goal_edit?goalId={goalId}",
                arguments = listOf(
                    navArgument("goalId") { type = NavType.LongType; defaultValue = 0L }
                )
            ) { backStackEntry ->
                GoalEditScreen(
                    goalId = backStackEntry.arguments?.getLong("goalId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "plan_edit?planId={planId}&goalId={goalId}",
                arguments = listOf(
                    navArgument("planId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("goalId") { type = NavType.LongType; defaultValue = 0L }
                )
            ) { backStackEntry ->
                PlanEditScreen(
                    planId = backStackEntry.arguments?.getLong("planId") ?: 0L,
                    goalId = backStackEntry.arguments?.getLong("goalId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    // 每日揭历覆盖层，盖在整个应用（含底部导航栏）之上
    val info = greetingInfo
    if (showGreeting && info != null) {
        DailyGreetingOverlay(
            info = info,
            onDismiss = { specialDateViewModel.dismissGreeting() },
            onManageDates = {
                specialDateViewModel.dismissGreeting()
                navController.navigate("special_dates") {
                    launchSingleTop = true
                }
            }
        )
    }
    }
}
