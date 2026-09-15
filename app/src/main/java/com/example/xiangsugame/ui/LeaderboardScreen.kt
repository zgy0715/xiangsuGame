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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.BoardEntry
import com.example.xiangsugame.api.dto.difficultyFromName
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.Dates
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/** 排行榜来源筛选:综合 / 对战(race,时长由服务器计时)。 */
private data class SourceFilter(val value: String, val label: String)

/**
 * 排行榜目标题目。
 * 排行榜此前写死"今日每日一题",而网络对战的题目由房主自选(内置关/随机在线关),
 * 于是"对战榜"几乎总是空的 —— 现在改为可选题:今日每日一题 + 我打过分的题。
 */
private data class BoardTarget(
    val puzzleId: Int,
    val label: String,
    /** 副标题:难度/尺寸等说明。 */
    val detail: String = "",
    val isDaily: Boolean = false,
)

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
    val myUserId = AuthManager.session?.userId
    val scope = rememberCoroutineScope()

    // —— 可选题目(今日每日一题 + 我打过分的题)—— 
    var targets by remember { mutableStateOf<List<BoardTarget>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = targets.firstOrNull { it.puzzleId == selectedId }

    /**
     * 载入可选题目:先拿"我打过分的题"(含网络对战里的内置关),
     * 再补上今日每日一题(即使我还没打过,也能看别人成绩)。
     * 默认选中我最近打过的那道 —— 打完对战进来就能直接看到自己的成绩。
     */
    suspend fun loadTargets() {
        val daily = PuzzleRepository.fetchDaily(Dates.todayIso()).getOrNull()?.level
        val mine = ApiClient.safe { ApiClient.api().myPuzzles(limit = 20) }
            .getOrNull()?.items.orEmpty()

        // 在线关的名称:库列表里能查到就用服务端题名;内置关用本地关卡表补名
        val onlineIds = mine.filter { it.puzzleId < 0 }.map { it.puzzleId }
        val bankNames: Map<Int, String> = if (onlineIds.isEmpty()) emptyMap() else
            ApiClient.safe { ApiClient.api().bank(limit = 100) }
                .getOrNull()?.items.orEmpty()
                .filter { it.id in onlineIds }
                .associate { it.id to "${it.name}(${it.rows}×${it.cols})" }

        val built = mutableListOf<BoardTarget>()
        daily?.let { lv ->
            built += BoardTarget(
                lv.id,
                "今日每日一题 · ${lv.name}",
                "${difficultyFromName(lv.difficulty).label} ${lv.rows}×${lv.cols}",
                isDaily = true,
            )
        }
        mine.forEach { p ->
            if (p.puzzleId == daily?.id) return@forEach
            val label = when {
                p.puzzleId > 0 -> "${Levels.byId(p.puzzleId)?.name ?: "内置关"} · 第 ${p.puzzleId} 关"
                bankNames[p.puzzleId] != null -> "在线关 · ${bankNames[p.puzzleId]}"
                p.name != null -> "在线关 · ${p.name}"
                else -> "在线关 #${-p.puzzleId}"
            }
            built += BoardTarget(p.puzzleId, label, "我打过 ${p.plays} 次")
        }
        targets = built
        if (selectedId == null || built.none { it.puzzleId == selectedId }) {
            selectedId = built.firstOrNull()?.puzzleId
        }
    }

    suspend fun load() {
        val target = targets.firstOrNull { it.puzzleId == selectedId } ?: return
        loading = true
        offline = false
        val board = ApiClient.safe {
            ApiClient.api().leaderboard(
                puzzleId = target.puzzleId,
                source = source.value.ifEmpty { null },
                limit = 30,
            )
        }
        board.onSuccess {
            entries = it.entries
            myRank = it.myBest?.rank
        }.onFailure { offline = true; entries = emptyList(); myRank = null }
        loading = false
    }

    LaunchedEffect(Unit) { loadTargets() }
    LaunchedEffect(source.value, selectedId) { if (selectedId != null) load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenTopBar(
            title = "排行榜",
            subtitle = "成绩来自服务器 · 每人取最优",
            onBack = onBack,
        )

        // —— 选题目(点击展开)——
        Surface(
            onClick = { pickerOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Coral.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🎯", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        selected?.label ?: "选择要查看的题目",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink,
                        maxLines = 1,
                    )
                    Text(
                        selected?.detail?.ifBlank { null } ?: "今日每日一题 / 我打过分的题",
                        fontSize = 11.sp, color = Cocoa,
                        maxLines = 1,
                    )
                }
                Text("切换 ▾", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Coral)
            }
        }

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
            entries.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (source.value == "race") "⚔️" else "🏆", fontSize = 38.sp)
                Text(
                    if (source.value == "race") "这道题还没有对战成绩" else "这道题还没有人上榜",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (source.value == "race") {
                        "对战成绩在「对战」里打完自动入榜。\n若你在别的题目上打过对战,用上方「切换」换一道题看看。"
                    } else {
                        "去做第一个上榜的人吧。"
                    },
                    fontSize = 12.sp, color = Cocoa, textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
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
                        selected?.let { "${it.label}${if (it.detail.isBlank()) "" else " · " + it.detail}" }.orEmpty(),
                        fontSize = 12.sp,
                        color = Cocoa,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(entries) { _, entry ->
                    LeaderboardRow(
                        entry = entry,
                        highlight = entry.userId == myUserId,
                        showMe = entry.rank == myRank && entry.userId == myUserId,
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        TargetPickerDialog(
            targets = targets,
            selectedId = selectedId,
            onPick = { selectedId = it.puzzleId; pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** 选题目弹窗:今日每日一题 + 我打过分的题(对战榜往往要看后者才有数据)。 */
@Composable
private fun TargetPickerDialog(
    targets: List<BoardTarget>,
    selectedId: Int?,
    onPick: (BoardTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择题目", fontWeight = FontWeight.Bold) },
        text = {
            if (targets.isEmpty()) {
                Text("暂时没有可查看的题目 —— 先玩一局每日一题或打一场对战吧", fontSize = 13.sp, color = Cocoa)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(targets) { _, t ->
                        val active = t.puzzleId == selectedId
                        Surface(
                            onClick = { onPick(t) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) Coral.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (active) Coral.copy(alpha = 0.5f) else Color(0x14FFFFFF)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (t.isDaily) "🌅" else if (t.puzzleId > 0) "🎮" else "🎲",
                                    fontSize = 15.sp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = Ink, maxLines = 1)
                                    if (t.detail.isNotBlank()) {
                                        Text(t.detail, fontSize = 10.sp, color = Cocoa, maxLines = 1)
                                    }
                                }
                                if (active) Text("✓", fontSize = 14.sp, color = Coral, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
