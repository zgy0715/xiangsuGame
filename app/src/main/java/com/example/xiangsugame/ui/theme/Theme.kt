package com.example.xiangsugame.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// 暗色主题配色：Material 3 的 darkColorScheme 只覆盖 primary/secondary/tertiary，
// 其余颜色（背景、表面、文字等）使用 Material 3 的暗色默认值
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

// 亮色主题配色：同理，只覆盖三个主色
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* 如需定制更多颜色，可以在这里覆盖（模板预留了默认值注释）
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * 应用主题封装：决定使用哪套 ColorScheme，再包裹一个 MaterialTheme。
 *
 * 配色选择优先级：
 *  1. Android 12+ 且开启 dynamicColor：使用系统"动态取色"（壁纸衍生配色）；
 *  2. 否则跟随系统暗色模式：暗 → DarkColorScheme，亮 → LightColorScheme。
 * 项目里自定义像素配色（BoardView）不走 ColorScheme，因此不受主题切换影响。
 */
@Composable
fun XiangsuGameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
