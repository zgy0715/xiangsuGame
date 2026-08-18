package com.example.xiangsugame.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.GameProgress
import com.example.xiangsugame.model.Level

/**
 * 首页（关卡选择）：
 * 顶部是游戏标题与副标题，下方是"选择关卡"的九宫格关卡列表。
 *
 * 关卡制：每张卡片显示"第 N 关"与难度/尺寸，
 * 已通关的显示 ✅ 与图案名（通关才揭晓），未解锁的显示 🔒 且置灰不可点。
 * 解锁与通关状态来自 [GameProgress]（SharedPreferences 持久化）。
 */
@Composable
fun HomeScreen(
    levels: List<Level>,
    progress: GameProgress,
    onPlayLevel: (Level) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 游戏标题
        Text(
            "像素填空",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF263238),
            modifier = Modifier.padding(top = 40.dp),
        )
        Text(
            "Fill-a-Pix · 逻辑与像素的碰撞",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        // 关卡区块标题
        Text(
            "选择关卡",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        // 关卡网格：两列自适应，卡片显示第 N 关 + 锁定/通关状态
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(levels, key = { it.id }) { level ->
                StageCard(
                    level = level,
                    unlocked = progress.isUnlocked(level.id),
                    completed = level.id in progress.completedLevels,
                    onClick = { onPlayLevel(level) },
                )
            }
        }
    }
}

/**
 * 单个关卡卡片。未解锁置灰 + 🔒；已通关显示 ✅ 与图案名（通关奖励）；
 * 可玩但未通关的显示 ▶。
 */
@Composable
private fun StageCard(
    level: Level,
    unlocked: Boolean,
    completed: Boolean,
    onClick: () -> Unit,
) {
    val cardColor = if (unlocked) {
        Brush.horizontalGradient(listOf(Color(0xFFEDE7F6), Color(0xFFB3E5FC)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFFEEEEEE), Color(0xFFE0E0E0)))
    }
    val textColor = if (unlocked) Color(0xFF263238) else Color(0xFF9E9E9E)
    val subColor = if (unlocked) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFBDBDBD)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unlocked, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (unlocked) 2.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardColor)
                .padding(16.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "第 ${level.id} 关",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        when {
                            !unlocked -> "🔒"
                            completed -> "✅"
                            else -> "▶"
                        },
                        fontSize = 16.sp,
                    )
                }
                Text(
                    "${level.difficulty.label} · ${level.rows}×${level.cols}",
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // 图案名通关后才揭晓，作为完成奖励
                if (completed) {
                    Text(
                        "图案：${level.name}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
