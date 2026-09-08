package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.data.Dates
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.GlowPurple
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/**
 * 首页中枢 —— 深夜画室风,单张 LazyVerticalGrid 纵向滚动:
 *  - 渐变 Hero:标题 + 账号昵称 + 补齐额度 + 切换账号;
 *  - 登录用户:在线入口四宫格(每日一题/在线题库/排行榜/对战)+ 每日一题卡;
 *  - 游客:仅内置关卡,提示可去登录;
 *  - 模式分段(自由/限时)+ 内置关卡网格(顺序解锁)。
 */
@Composable
fun HomeScreen(
    levels: List<Level>,
    account: AccountStore,
    onPlayLevel: (Level, GameMode) -> Unit,
    onPlayDaily: (Level) -> Unit,
    onOpenHall: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nickname = AuthManager.session?.nickname ?: "玩家${account.userId}"
    val isGuest = AuthManager.isGuest
    val demoUnlocked = LocalSettings.demoAllUnlocked
    val completedCount = account.completedLevels.size
    var gameMode by remember { mutableStateOf(GameMode.FREE) }
    val scope = rememberCoroutineScope()

    // —— 每日一题(在线拉取;失败仅提示可重试,不阻塞离线玩法)——
    var daily by remember { mutableStateOf<Level?>(null) }
    var dailyDone by remember { mutableStateOf(false) }
    var dailyLoading by remember { mutableStateOf(true) }
    var dailyError by remember { mutableStateOf(false) }

    suspend fun loadDaily() {
        dailyLoading = true
        dailyError = false
        val today = Dates.todayIso()
        val result = PuzzleRepository.fetchDaily(today)
        result.onSuccess {
            daily = it.level.toModel()
            // 本地没解过的缓存关再点会重玩;已解则提示去排行榜
            dailyDone = false
        }.onFailure { daily = null; dailyError = true }
        dailyLoading = false
    }

    LaunchedEffect(Unit) { if (!isGuest) loadDaily() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 30.dp),
    ) {
        // —— 渐变 Hero(占满一行)——
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroHeader(
                nickname = nickname,
                completedCount = completedCount,
                totalLevels = levels.size,
                quotaRemaining = account.fillQuotaRemaining,
                onLogout = onLogout,
            )
        }

        if (isGuest) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GuestBanner(onLogin = onLogout)
            }
        } else {
            // —— 在线入口 ——
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "在线玩法",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) {
                        Text("⚙ 设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile("每日一题", "🌅", Modifier.weight(1f)) {
                        when {
                            daily != null -> onPlayDaily(daily!!)
                            dailyLoading -> Unit
                            else -> scope.launch { loadDaily() }
                        }
                    }
                    QuickTile("在线题库", "🎲", Modifier.weight(1f), onOpenHall)
                    QuickTile("排行榜", "🏆", Modifier.weight(1f), onOpenLeaderboard)
                    QuickTile("对战", "⚔️", Modifier.weight(1f), onOpenBattle)
                }
            }

            // —— 每日一题卡 ——
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    dailyLoading -> DailyCard(
                        emoji = "🌅", title = "每日一题", subtitle = "连线服务器中…",
                        trailing = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
                    )
                    daily != null -> DailyCard(
                        emoji = "🌅",
                        title = "今日谜题 · ${daily!!.difficulty.label} ${daily!!.rows}×${daily!!.cols}",
                        subtitle = "${Dates.todayIso()} · 全服同题 · 谁最快谁上榜",
                        onClick = { onPlayDaily(daily!!) },
                    )
                    else -> DailyCard(
                        emoji = "🌅",
                        title = "每日一题",
                        subtitle = "离线中,点此重试连接服务器",
                        onClick = { scope.launch { loadDaily() } },
                    )
                }
            }
        }

        // —— 模式分段 + 内置关卡 ——
        item(span = { GridItemSpan(maxLineSpan) }) {
            SegmentedControl(
                items = listOf(
                    GameMode.FREE to "🎮 自由模式",
                    GameMode.TIMED to "⏱ 限时模式",
                ),
                selected = gameMode,
                onSelect = { gameMode = it },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "内置闯关",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(levels, key = { it.id }) { level ->
            StageCard(
                level = level,
                unlocked = demoUnlocked || account.isUnlocked(level.id),
                completed = level.id in account.completedLevels,
                onClick = { onPlayLevel(level, gameMode) },
            )
        }
    }
}

/** Hero 头卡:标题 + 账号信息胶囊 + 进度条。 */
@Composable
private fun HeroHeader(
    nickname: String,
    completedCount: Int,
    totalLevels: Int,
    quotaRemaining: Int,
    onLogout: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF7A68F2), Color(0xFF5647C9), Color(0xFF3B2E8F)),
                ),
            ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧩", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "像素填空",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onLogout,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color(0xE6FFFFFF),
                    ),
                ) { Text("切换账号", fontWeight = FontWeight.SemiBold) }
            }
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassChip("👤 $nickname")
                GlassChip("⚡ 补齐 $quotaRemaining 次")
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "内置关卡通关",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "$completedCount / $totalLevels",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val fraction = if (totalLevels == 0) 0f else completedCount.toFloat() / totalLevels
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFFE29A), Color(0xFFFFFFFF)),
                            ),
                        ),
                )
            }
            Text(
                if (completedCount == totalLevels) "🎉 内置关全通,去在线题库与每日一题继续挑战!"
                else "每解开一关,就能看到一幅像素画",
                fontSize = 11.sp,
                color = Color(0xCCFFFFFF),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 功能入口小卡。 */
@Composable
private fun QuickTile(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 24.sp)
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 每日一题展示卡(loading / 就绪 / 失败三种)。 */
@Composable
private fun DailyCard(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Coral.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Cocoa,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Text("▶", fontSize = 18.sp, color = Coral)
            }
        }
    }
}

/** 单个关卡卡片:未解锁暗灰 🔒 / 已解锁未通关"?"谜底 / 已通关点亮描边揭晓图案。 */
@Composable
private fun StageCard(
    level: Level,
    unlocked: Boolean,
    completed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unlocked, onClick = onClick)
            .border(
                width = 1.dp,
                color = if (completed) Coral.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            ),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (unlocked) 2.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unlocked) MaterialTheme.colorScheme.surface
            else Color(0xFF15131F),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            if (unlocked) Brush.linearGradient(listOf(Coral, GlowPurple))
                            else Brush.linearGradient(
                                listOf(Color(0xFF3A3654), Color(0xFF3A3654)),
                            ),
                            CircleShape,
                        ),
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
                    color = if (unlocked) Ink else Color(0xFF7A7599),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "★".repeat(level.difficulty.stars),
                    fontSize = 13.sp,
                    color = if (unlocked) Amber else Color(0xFF565279),
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

            val previewModifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                            PicturePreview(
                                answer = level.answerGrid,
                                modifier = Modifier.matchParentSize(),
                                filledColor = Color(0xFF2E2B45),
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color(0x59100E1C)),
                            )
                            Text(
                                "？",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF8F89BC),
                                modifier = Modifier.align(Alignment.Center),
                            )
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "${level.difficulty.label} · ${level.rows}×${level.cols}",
                style = MaterialTheme.typography.bodySmall,
                color = if (unlocked) Cocoa else Color(0xFF7A7599),
            )
            if (completed) {
                Text(
                    "图案:${level.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Coral,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 游客模式提示条:说明仅内置关卡,引导登录联网玩法。 */
@Composable
private fun GuestBanner(onLogin: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("👤", fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "游客模式",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    "仅内置关卡 · 登录后可玩每日一题、排行榜、对战",
                    fontSize = 11.sp,
                    color = Cocoa,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(onClick = onLogin) {
                Text("去登录", fontWeight = FontWeight.Bold)
            }
        }
    }
}
