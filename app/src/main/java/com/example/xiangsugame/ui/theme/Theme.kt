package com.example.xiangsugame.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用主题封装 —— "深夜画室"。
 *
 * 游戏需要沉浸与氛围感：深靛夜幕做底、被照亮的画纸棋盘、霓虹三色点缀。
 * 因此**恒定使用深色配色**，不再跟随系统亮暗模式（动态取色也默认关闭），
 * 无论在什么设备上，游戏界面都是同一套品牌观感。
 */
private val GameColorScheme = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF1A1240),
    primaryContainer = CoralContainer,
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Cyan,
    onSecondary = Color(0xFF003634),
    secondaryContainer = CyanContainer,
    onSecondaryContainer = Color(0xFFC9F3F0),
    tertiary = Amber,
    onTertiary = Color(0xFF3D2B00),
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFFFFE8BE),
    background = NightBg,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextMuted,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A4666),
)

@Composable
fun XiangsuGameTheme(
    // 游戏配色是刻意设计的，不随系统动态取色变化，保证界面观感统一
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GameColorScheme,
        typography = Typography,
        content = content,
    )
}
