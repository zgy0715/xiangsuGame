package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.GameProgress
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.UserRole
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import com.example.xiangsugame.ui.theme.Sand

/**
 * 首页（关卡选择）—— 游戏化改版 + 双身份权限 + 模式选择。
 *
 * 布局自上而下：
 *  - 标题区：暖色渐变背景 + 大标题 + 身份徽章（👤玩家 / 👑管理员）与"切换身份"；
 *  - 模式选择：🎮 自由模式 / ⏱ 限时模式 两个可切换胶囊；
 *  - 通关进度卡：进度条 + X/N，有"收集感"；
 *  - 关卡网格：每张卡片带难度星级、锁定/通关状态；管理员全局解锁（含隐藏关），
 *    隐藏关用金色描边 + 🌟隐藏 标注；通关的关卡揭晓像素画，未完成的只给"？"。
 */
@Composable
fun HomeScreen(
    levels: List<Level>,
    progress: GameProgress,
    role: UserRole,
    onPlayLevel: (Level, GameMode) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completedCount = progress.completedLevels.size
    val isAdmin = role == UserRole.ADMIN
    // 当前选中的模式：进关时把模式和关卡一起交给游戏页
    var gameMode by remember { mutableStateOf(GameMode.FREE) }
    val bg = Brush.verticalGradient(listOf(Color(0xFFF3F0FF), Color(0xFFF8F6FF)))

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 标题 ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧩", fontSize = 30.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "像素填空",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = Ink,
            )
        }
        Text(
            "Fill-a-Pix · 用数字线索还原像素画",
            fontSize = 13.sp,
            color = Cocoa,
            modifier = Modifier.padding(top = 2.dp),
        )

        // —— 身份徽章 + 切换身份 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isAdmin) Amber.copy(alpha = 0.28f) else Color.White,
                contentColor = Ink,
            ) {
                Text(
                    if (isAdmin) "👑 管理员" else "👤 玩家",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onLogout) { Text("切换身份") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // —— 模式选择 ——
        ModeSelector(selected = gameMode, onSelect = { gameMode = it })

        Spacer(modifier = Modifier.height(16.dp))

        // —— 通关进度卡 ——
        ProgressCard(completed = completedCount, total = levels.size)

        Spacer(modifier = Modifier.height(20.dp))

        // —— 关卡区块 ——
        Text(
            "选择关卡",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            items(levels, key = { it.id }) { level ->
                StageCard(
                    level = level,
                    unlocked = isAdmin || progress.isUnlocked(level.id),
                    completed = level.id in progress.completedLevels,
                    onClick = { onPlayLevel(level, gameMode) },
                )
            }
        }
    }
}

/** 顶部模式选择：两个胶囊，选中高亮，切关时带入选中的模式。 */
@Composable
private fun ModeSelector(selected: GameMode, onSelect: (GameMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModePill(
            text = "🎮 自由模式",
            selected = selected == GameMode.FREE,
            onClick = { onSelect(GameMode.FREE) },
        )
        ModePill(
            text = "⏱ 限时模式",
            selected = selected == GameMode.TIMED,
            onClick = { onSelect(GameMode.TIMED) },
        )
    }
}

@Composable
private fun ModePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Coral else Color.White,
        contentColor = if (selected) Color.White else Cocoa,
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 顶部通关进度卡：进度条 + X / N，通关全部有庆祝文案。 */
@Composable
private fun ProgressCard(completed: Int, total: Int) {
    val fraction = if (total == 0) 0f else completed.toFloat() / total
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "通关进度",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "$completed / $total",
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Coral,
                trackColor = Sand,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (completed == total) "🎉 全部通关，你已经是个像素大师了！"
                else "每解开一关，就能看到一幅像素画",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单个关卡卡片。
 *  - 未解锁：置灰 + 🔒，不可点；隐藏关未解锁时提示"需通关全部 10 关"；
 *  - 已解锁未通关：浅色"？"谜底 + ▶，点进去玩；
 *  - 已通关：清晰揭晓像素画缩略图 + ✅ + 图案名；
 *  - 隐藏关：金色描边 + 🌟隐藏 标注，管理员可直接解锁进入。
 */
@Composable
private fun StageCard(
    level: Level,
    unlocked: Boolean,
    completed: Boolean,
    onClick: () -> Unit,
) {
    val cardBg = if (unlocked) {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF0EDFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF2F1F6), Color(0xFFE6E4EE)))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unlocked, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (unlocked) 2.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = if (level.isHidden) BorderStroke(1.5.dp, Amber) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg)
                .padding(14.dp),
        ) {
            // —— 头部：序号徽章 + 星级 + 状态 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(if (unlocked) Coral else Color(0xFFB8B4C9), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${level.id}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "第 ${level.id} 关",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "★".repeat(level.difficulty.stars),
                    fontSize = 13.sp,
                    color = if (unlocked) Amber else Color(0xFFCCC8DC),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    when {
                        !unlocked -> "🔒"
                        completed -> "✅"
                        else -> "▶️"
                    },
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // —— 预览区：通关揭晓像素画，未通关给"？"谜底 ——
            val previewModifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (completed) {
                    PicturePreview(
                        answer = level.answerGrid,
                        modifier = previewModifier,
                    )
                } else {
                    Box(modifier = previewModifier) {
                        if (unlocked) {
                            // 已解锁未通关：半透明白幕下透出一丝轮廓 + "？"，勾起好奇心
                            PicturePreview(
                                answer = level.answerGrid,
                                modifier = Modifier.matchParentSize(),
                                filledColor = Color(0xFFC4BFD9),
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color(0x80FFFFFF)),
                            )
                            Text(
                                "？",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFB4AECE),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color(0xFFE9E7F0)),
                            )
                            if (level.isHidden) {
                                // 隐藏关：明确告知解锁条件
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🔒", fontSize = 18.sp)
                                    Text(
                                        "需通关全部 10 关",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF8F8BA4),
                                    )
                                }
                            } else {
                                Text(
                                    "🔒",
                                    fontSize = 22.sp,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // —— 底部信息 ——
            Text(
                buildString {
                    append("${level.difficulty.label} · ${level.rows}×${level.cols}")
                    if (level.isHidden) append(" · 🌟隐藏")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (unlocked) Cocoa else Color(0xFFA8A4BD),
            )
            if (completed) {
                Text(
                    "图案：${level.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
