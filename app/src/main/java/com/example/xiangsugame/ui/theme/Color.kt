package com.example.xiangsugame.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// 游戏主题配色 —— "现代像素解谜"风
// 整体走清爽、高级、有游戏感的路线：
//  主色 = 活力靛紫（操作、强调），次色 = 湖水青（次级操作），
//  点缀 = 暖金（星级、徽章）；背景是淡薰衣草白，文字用深墨蓝。
// 相比暖米色+珊瑚粉的旧版，对比更强、更现代、更"游戏"。
// ============================================================

// —— 品牌三主色 ——
val Coral = Color(0xFF6C5CE7)        // 靛紫主色：主操作、强调（旧名沿用，实为品牌主色）
val CoralDark = Color(0xFF9B8CFF)    // 暗色主题下的主色（提亮一档保证对比度）
val CoralContainer = Color(0xFFE4DFFF)   // 主色容器（浅薰衣草底）

val Cyan = Color(0xFF00A3A3)         // 湖水青：次操作色
val CyanDark = Color(0xFF66E0DC)
val CyanContainer = Color(0xFFC9F3F0)

val Amber = Color(0xFFFF9F0A)        // 暖金：点缀、星级
val AmberDark = Color(0xFFFFC85C)
val AmberContainer = Color(0xFFFFE8BE)

// —— 亮色主题 ——
val Cream = Color(0xFFF7F5FF)        // 页面背景：淡薰衣草白
val SurfaceWhite = Color(0xFFFFFFFF) // 卡片 / 面板
val Sand = Color(0xFFEFEBFA)         // surfaceVariant：浅薰衣草
val Cocoa = Color(0xFF6B6680)        // 次要文字：柔紫灰
val Ink = Color(0xFF241F38)          // 主文字：深墨蓝

// —— 暗色主题 ——
val NightBg = Color(0xFF191728)          // 深靛夜背景
val NightSurface = Color(0xFF232038)     // 卡片
val NightSurfaceVariant = Color(0xFF312D4A)
val NightText = Color(0xFFF0EFFB)        // 主文字
val NightTextMuted = Color(0xFFB9B4D6)   // 次要文字
