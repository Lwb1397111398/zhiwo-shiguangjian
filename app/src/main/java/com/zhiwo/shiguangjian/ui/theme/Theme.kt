package com.zhiwo.shiguangjian.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Surface,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Warning,
    onSecondary = TextPrimary,
    secondaryContainer = SurfaceWarm,
    tertiary = Lavender,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceSoft,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Error,
    outline = Divider,
    outlineVariant = Divider
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = DarkBackground,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = DarkTextPrimary,
    secondary = Warning,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceSoft,
    tertiary = Lavender,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceSoft,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    error = Error,
    outline = DarkDivider,
    outlineVariant = DarkDivider
)

/**
 * 暗色模式设置：
 *  - "auto"  : 跟随系统
 *  - "on"    : 强制暗色
 *  - "off"   : 强制亮色
 */
@Composable
fun ZhiwoTheme(
    darkMode: String = "auto",
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkMode) {
        "on" -> true
        "off" -> false
        else -> systemDark
    }

    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography.copy(
            headlineLarge = Typography.headlineLarge.copy(color = colorScheme.onBackground),
            headlineMedium = Typography.headlineMedium.copy(color = colorScheme.onBackground),
            titleLarge = Typography.titleLarge.copy(color = colorScheme.onSurface),
            titleMedium = Typography.titleMedium.copy(color = colorScheme.onSurface),
            bodyLarge = Typography.bodyLarge.copy(color = colorScheme.onSurface),
            bodyMedium = Typography.bodyMedium.copy(color = colorScheme.onSurfaceVariant),
            bodySmall = Typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
            labelSmall = Typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)
        ),
        content = content
    )
}
