package com.example.xiangsugame.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// 游戏主题配色 —— "深夜画室"风
//
// 深靛夜幕做底（沉浸、聚焦），棋盘是一张被聚光照亮的画纸
// （浅色纸面格子形成强明暗对比，突出核心玩法区）；
// 品牌靛紫 + 湖水青 + 暖金三色点缀，卡片走玻璃质感。
// 游戏恒定深色观感，不随系统亮暗切换 —— 保持统一的品牌氛围。
// ============================================================

// —— 品牌三主色（深色底上直接可用的高亮度取值）——
val Coral = Color(0xFF8B7CFF)         // 品牌靛紫（亮）：主操作、强调（旧名沿用）
val CoralDark = Color(0xFF8B7CFF)     // 兼容旧引用：与 Coral 同值
val CoralContainer = Color(0xFF3C2FA0)    // 主色容器：深紫底
val CoralDeep = Color(0xFF5E4FD1)     // 品牌紫的渐变深端
val GlowPurple = Color(0xFFB06CF7)    // 品牌紫的渐变亮端 / 光晕色

val Cyan = Color(0xFF5EDDD8)          // 湖水青（亮）：次操作、正向反馈
val CyanDark = Color(0xFF5EDDD8)      // 兼容旧引用
val CyanContainer = Color(0xFF00514E)

val Amber = Color(0xFFFFC85C)         // 暖金：星级、管理员身份
val AmberDark = Color(0xFFFFC85C)     // 兼容旧引用
val AmberContainer = Color(0xFF574100)

// —— 通用语义色（深夜画室基底；名称沿用旧版，取值已切换为深色观感）——
val Cream = Color(0xFF100E1C)         // 页面背景：深靛夜幕
val SurfaceWhite = Color(0xFF1B1929)  // 卡片 / 面板：夜表面
val Sand = Color(0xFF2A2740)          // surfaceVariant / 进度轨道：深紫灰
val Cocoa = Color(0xFFB9B4D6)         // 次要文字：柔紫灰
val Ink = Color(0xFFF0EFFB)           // 主文字：夜光白

// —— 暗色主题别名（与上面基底同值，保留旧引用名）——
val NightBg = Cream
val NightSurface = SurfaceWhite
val NightSurfaceVariant = Sand
val NightText = Ink
val NightTextMuted = Cocoa
