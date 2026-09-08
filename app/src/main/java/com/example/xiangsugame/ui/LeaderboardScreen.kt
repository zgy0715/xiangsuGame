package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.BoardEntry
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.Dates
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/** 排行榜来源筛选:综合 / 对战(race,时长由服务器计时)。 */
private data class SourceFilter(val value: String, val label: String)

/**
 * 排行榜 —— 当前每日一题榜(全服最快 & 对战最快)。
 * 数据来源服务器 solve_records:每人取最优成绩;myBest 高亮"你"。
 */
@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources = listOf(
        SourceFilter("", "综合最快"),
        SourceFilter("race", "对战榜"),
    )
    var source by remember { mutableStateOf(sources[0]) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<BoardEntry>>(emptyList()) }
    var myRank by remember { mutableStateOf<Int?>(null) }
    var puzzleLabel by remember { mutableStateOf("每日一题") }
    val myUserId = AuthManager.session?.userId
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        offline = false
        val daily = PuzzleRepository.fetchDaily(Dates.todayIso())
        if (daily.isFailure) {
            offline = true
            loading = false
            return
        }
        val level = daily.getOrThrow().level
        puzzleLabel = "${level.name}(${level.difficulty} ${level.rows}×${level.cols})"
        val board = ApiClient.safe {
            ApiClient.api().leaderboard(
                puzzleId = level.id,
                source = source.value.ifEmpty { null },
                limit = 30,
            )
        }
        board.onSuccess {
            entries = it.entries
            myRank = it.myBest?.rank
        }.onFailure { offline = true }
        loading = false
    }

    LaunchedEffect(source.value) { load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenTopBar(
            title = "排行榜",
            subtitle = "每日一题全服竞速 · 成绩来自服务器",
            onBack = onBack,
        )

        SegmentedControl(
            items = sources.map { it to it.label },
            selected = source,
            onSelect = { source = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            offline -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("📡", fontSize = 40.sp)
                Text("排行榜需要连接服务器", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "当前服务器:${ApiClient.serverHost()}\n确认「设置」地址正确后重试",
                    fontSize = 12.sp,
                    color = Cocoa,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = { scope.launch { load() } },
                    modifier = Modifier.padding(top = 14.dp),
                ) { Text("重试") }
            }
            entries.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (source.value == "race") "暂无对战记录 —— 去「对战」开一局吧"
                    else "还没有人上榜 —— 来做第一个",
                    color = Cocoa,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        puzzleLabel,
                        fontSize = 12.sp,
                        color = Cocoa,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(entries) { i, entry ->
                    LeaderboardRow(
                        entry = entry,
                        highlight = entry.userId == myUserId,
                        showMe = entry.rank == myRank && entry.userId == myUserId,
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: BoardEntry, highlight: Boolean, showMe: Boolean) {
    val medal = when (entry.rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) Coral.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (highlight) Coral.copy(alpha = 0.55f) else Color(0x1FFFFFFF),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (medal != null) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(medal ?: "${entry.rank}", fontWeight = FontWeight.Black, fontSize = 15.sp,
                    color = if (medal != null) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                entry.nickname,
                fontSize = 15.sp,
                fontWeight = if (highlight) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            if (showMe) {
                Text("你", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(Coral, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(
                formatDuration(entry.durationMs),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = if (entry.rank == 1) Amber else Ink,
            )
        }
    }
}

/** 毫秒 → m:ss.t 计时风格,如 83211ms → "1:23.2"。 */
internal fun formatDuration(durationMs: Int): String {
    val totalTenths = durationMs / 100
    val m = totalTenths / 600
    val s = (totalTenths % 600) / 10
    val t = totalTenths % 10
    return "$m:${"%02d".format(s)}.$t"
}
