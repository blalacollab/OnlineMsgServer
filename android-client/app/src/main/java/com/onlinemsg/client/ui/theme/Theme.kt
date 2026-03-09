package com.onlinemsg.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    error = Error
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.Black,
    secondary = DarkSecondary,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    error = Error
)

/**
 * 应用主题可组合函数。
 * 支持浅色/深色模式以及 Android 12+ 的动态颜色。
 * @param darkTheme 是否强制深色模式（默认跟随系统）
 * @param themeId 当前选中的主题 ID (默认为 "blue")
 * @param useDynamicColor 是否启用动态颜色（Android 12+ 支持）
 */
@Composable
fun OnlineMsgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String = "blue",             // 默认预设设为 blue
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        // 1. 优先使用动态颜色（如果启用且系统支持）
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 2. 根据 themeId 选择预设
        else -> {
            val option = themeOptions.find { it.id == themeId } ?: themeOptions.first()
            if (darkTheme) {
                darkColorScheme(
                    primary = option.primaryDark ?: option.primary.copy(alpha = 0.8f),
                    secondary = option.secondary.copy(alpha = 0.8f),
                    surface = option.surfaceDark ?: DarkSurface,
                    onSurface = OnDarkSurface,
                    error = option.error ?: Error
                )
            } else {
                lightColorScheme(
                    primary = option.primary,
                    secondary = option.secondary,
                    surface = option.surface ?: Surface,
                    error = option.error ?: Error
                )
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

// 主题选项数据类
data class ThemeOption(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val surface: Color? = null,
    val primaryDark: Color? = null,    // 显式深色主色
    val surfaceDark: Color? = null,    // 显式深色背景
    val error: Color? = null
)

// 预设主题列表
val themeOptions = listOf(
    // 默认列表首位即为默认主题
    ThemeOption(
        id = "blue", 
        name = "蔚蓝", 
        primary = Color(0xFF1E88E5), 
        secondary = Color(0xFF6A8DAA)
    ),
    ThemeOption(
        id = "gray", 
        name = "商务灰", 
        primary = Color(0xFF607D8B), 
        secondary = Color(0xFF90A4AE),
        primaryDark = Color(0xFFCFD8DC),
        surfaceDark = Color(0xFF263238)
    ),
    ThemeOption(
        id = "green", 
        name = "翠绿", 
        primary = Color(0xFF2E7D32), 
        secondary = Color(0xFF4A635F)
    ),
    ThemeOption(
        id = "red",
        name = "绯红",
        primary = Color(0xFFC62828),
        secondary = Color(0xFF8D6E63)
    )
)
