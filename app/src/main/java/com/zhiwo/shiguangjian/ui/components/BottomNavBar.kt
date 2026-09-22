package com.zhiwo.shiguangjian.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zhiwo.shiguangjian.ui.theme.*

data class NavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val navItems = listOf(
    NavItem("records", "记录", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List),
    NavItem("calendar", "日历", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    NavItem("input", "写下", Icons.Filled.AddCircle, Icons.Outlined.AddCircle),
    NavItem("review", "评价", Icons.Filled.Star, Icons.Outlined.Star),
    NavItem("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun BottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    pendingReviewBadge: Int = 0
) {
    NavigationBar(
        modifier = modifier.height(72.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        navItems.forEach { item ->
            val selected = currentRoute == item.route
            // 评价 Tab 角标：有待确认的记忆更正（记忆入口在评价页）
            val badge = if (item.route == "review" && pendingReviewBadge > 0) pendingReviewBadge else null
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    if (badge != null) {
                        BadgedBox(badge = { Badge { Text("$badge") } }) {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            )
        }
    }
}
