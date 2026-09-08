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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.PuzzleMeta
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/** 难度筛选:null = 全部。 */
private data class DifficultyFilter(val value: String?, val label: String)

/**
 * 在线题库 —— 服务端生成的谜题库(每关服务端保证唯一解)。
 * 点选关卡 → 拉取完整关卡 → 进入游戏页;断网/失败有错误态与重试。
 */
@Composable
fun HallScreen(
    onPlay: (Level) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf(
        DifficultyFilter(null, "全部"),
        DifficultyFilter(Difficulty.EASY.name, "简单"),
        DifficultyFilter(Difficulty.MEDIUM.name, "中等"),
        DifficultyFilter(Difficulty.HARD.name, "困难"),
    )
    var filter by remember { mutableStateOf<DifficultyFilter>(filters[0]) }
    var items by remember { mutableStateOf<List<PuzzleMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    // 正在展开的关卡 id(该行转圈,防连点)
    var openingId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = false
        ApiClient.safe {
            ApiClient.api().bank(difficulty = filter.value, limit = 100)
        }.onSuccess { items = it.items; error = false }
            .onFailure { error = true }
        loading = false
    }

    LaunchedEffect(filter.value) { load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenTopBar(title = "在线题库", subtitle = "服务器实时生成的谜题 · 每题唯一解", onBack = onBack)

        // —— 难度筛选 chips ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.forEach { f ->
                val selected = f == filter
                Surface(
                    onClick = { filter = f },
                    shape = RoundedCornerShape(50),
                    color = if (selected) Coral else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) Color(0xFF1A1240) else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        f.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🌐", fontSize = 40.sp)
                Text("无法连接服务器", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "检查网络或到「设置」确认服务器地址\n(当前:${ApiClient.serverHost()})",
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
            items.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该难度题库还在生成中,稍后再来看看", color = Cocoa)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { meta ->
                    PuzzleRow(
                        meta = meta,
                        opening = openingId == meta.id,
                        onClick = {
                            if (openingId == null) {
                                openingId = meta.id
                                scope.launch {
                                    PuzzleRepository.fetchPuzzle(meta.id, forceRefresh = false)
                                        .onSuccess { level -> onPlay(level) }
                                        .onFailure { openingId = null }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PuzzleRow(meta: PuzzleMeta, opening: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Coral, Color(0xFF8B7CFF)))),
                contentAlignment = Alignment.Center,
            ) {
                Text("🧩", fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    meta.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    "${meta.difficulty} · ${meta.rows}×${meta.cols}" +
                        (meta.solves?.let { " · 已解 $it 次" } ?: ""),
                    fontSize = 12.sp,
                    color = Cocoa,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (opening) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            } else {
                Text("▶", color = Coral, fontSize = 16.sp)
            }
        }
    }
}
