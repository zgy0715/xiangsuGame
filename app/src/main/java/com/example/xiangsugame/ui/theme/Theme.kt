package com.example.xiangsugame.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 暗色主题配色：深靛夜底 + 提亮后的品牌色
private val DarkColorScheme = darkColorScheme(
    primary = CoralDark,
    onPrimary = Color(0xFF241A63),
    primaryContainer = Color(0xFF3C2FA0),
    onPrimaryContainer = CoralContainer,
    secondary = CyanDark,
    onSecondary = Color(0xFF003634),
    secondaryContainer = Color(0xFF00514E),
    onSecondaryContainer = CyanContainer,
    tertiary = AmberDark,
    onTertiary = Color(0xFF3D2B00),
    tertiaryContainer = Color(0xFF574100),
    onTertiaryContainer = AmberContainer,
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
)

// 亮色主题配色：淡薰衣草底 + 品牌主色
private val LightColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = CoralContainer,
    onPrimaryContainer = Color(0xFF3B2A86),
    secondary = Cyan,
    onSecondary = Color.White,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = Color(0xFF004E4C),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF4A3300),
    background = Cream,
    onBackground = Ink,
    surface = SurfaceWhite,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Cocoa,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * 应用主题封装。
 *
 * 与默认模板的区别：关闭了动态取色（dynamicColor=false），
 * 保证无论系统壁纸如何，游戏界面都是同一套品牌撞色 —— 游戏要有统一的"品牌观感"。
 * 仍跟随系统的亮 / 暗模式。
 */
@Composable
fun XiangsuGameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 游戏配色是刻意设计的，不随系统动态取色变化，保证界面观感统一
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // dynamicColor 保留为参数但默认关闭；本游戏固定使用自定义品牌配色
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
