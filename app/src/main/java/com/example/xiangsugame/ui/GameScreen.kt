package com.example.xiangsugame.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.xiangsugame.GameProgress
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.GameBoard
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Validator
import kotlinx.coroutines.delay

/**
 * 游戏界面（概要设计 3.6 节）。
 *
 * 布局自上而下：
 *  - 顶部：返回按钮 + 关卡信息（名称 / 难度·尺寸）+ 计时器；
 *  - 中部：棋盘（BoardView，占据剩余空间）；
 *  - 底部：撤销 / 重做 / 重置三个操作按钮 + "错误提示"开关。
 * 通关后（isSolved 为 true）弹出结果弹窗，可选择"再来一局"或返回关卡列表。
 *
 * 状态管理要点 —— "就地修改 + 版本号驱动重组"：
 * 棋盘的二维数组（board）是就地修改的（GameBoard 直接改数组元素），
 * 而 Compose 只知道"我读过的状态变了就重组"。为了让棋盘变化驱动 UI 刷新，
 * 这里维护一个 boardVersion 计数器：每次棋盘被修改就 boardVersion++，
 * 并让所有依赖棋盘渲染的 remember 都把 boardVersion 作为 key，
 * 这样 Compose 就能感知到变化并触发重组。这是一种避免深拷贝的常见做法
 * （拷贝 10×10 的数组代价不高，但对更大棋盘来说就地修改更高效）。
 */
@Composable
fun GameScreen(
    level: Level,
    progress: GameProgress,
    onBack: () -> Unit,
    onNextLevel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // remember(level.id)：切换关卡时用新关卡重建状态；同一关卡重组时保持状态
    val gameBoard = remember(level.id) { GameBoard(level.rows, level.cols) }
    val validator = remember(level.id) { Validator(level) }

    // 棋盘版本号：每次格子变化 +1，驱动依赖棋盘的状态/渲染重组
    var boardVersion by remember(level.id) { mutableIntStateOf(0) }
    // 错误提示开关状态
    var errorHintEnabled by remember { mutableStateOf(true) }
    // 本关已用秒数（计时器累计）
    var elapsedSeconds by remember(level.id) { mutableIntStateOf(0) }
    // 参考答案弹窗开关；peekedAnswer 记录本关是否查看过答案（结算时仅提示、不惩罚）
    var showAnswer by remember(level.id) { mutableStateOf(false) }
    var peekedAnswer by remember(level.id) { mutableStateOf(false) }

    val board = gameBoard.board
    // 胜利判定：棋盘每次变化后重算
    val isSolved = remember(level.id, boardVersion) { validator.isSolved(board) }
    // 矛盾格集合：仅当开启错误提示时才计算（省去无谓的扫描开销）
    val contradictionCells = remember(level.id, boardVersion, errorHintEnabled) {
        if (errorHintEnabled) validator.contradictionCells(board) else emptySet()
    }

    // 计时器：协程循环，未通关时每秒 +1。
    // LaunchedEffect(level.id, isSolved)：通关瞬间 isSolved 变为 true，协程被取消并重启，
    // 由于 while 条件为 false，循环立即结束，计时停止。
    LaunchedEffect(level.id, isSolved) {
        while (!isSolved) {
            delay(1000)
            elapsedSeconds += 1
        }
    }

    // 通关后把本关标记为已完成并持久化（顺序解锁下一关）。
    // 用 LaunchedEffect 挂在 isSolved 上，通关瞬间触发一次副作用。
    LaunchedEffect(level.id, isSolved) {
        if (isSolved) progress.markCompleted(level.id)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 顶部栏：返回 + 关卡信息 + 计时 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(level.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${level.difficulty.label} · ${level.rows}×${level.cols}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatTime(elapsedSeconds), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                peekedAnswer = true
                showAnswer = true
            }) { Text("参考答案") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // —— 中部：棋盘 ——
        BoardView(
            level = level,
            // 通关瞬间直接用答案矩阵渲染，避免"标记空白"圆点残留在空白格上，保证画面干净
            board = if (isSolved) level.answerGrid else board,
            contradictionCells = contradictionCells,
            errorHintEnabled = errorHintEnabled,
            onCycle = { r, c ->
                // 单击循环切换三态；只有真的发生变化才让版本号 +1（避免无效重组）
                if (gameBoard.cycleCell(r, c)) boardVersion++
            },
            onMarkEmpty = { r, c ->
                // 长按直接标记为空白（幂等：已是空白标记则跳过）
                if (gameBoard.board[r][c] != CellState.MARKED_EMPTY.value) {
                    gameBoard.setCell(r, c, CellState.MARKED_EMPTY)
                    boardVersion++
                }
            },
            // 通关后进入"图片模式"：隐藏提示数字、禁止点击，揭晓完整像素画
            showClues = !isSolved,
            interactive = !isSolved,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // —— 底部操作栏：撤销 / 重做 / 重置 ——
        // 每个按钮的 enabled 由 GameBoard 的 canUndo/canRedo 决定，栈空时置灰
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (gameBoard.undo()) boardVersion++ },
                enabled = gameBoard.canUndo,
                modifier = Modifier.size(width = 96.dp, height = 44.dp),
            ) { Text("撤销") }

            OutlinedButton(
                onClick = { if (gameBoard.redo()) boardVersion++ },
                enabled = gameBoard.canRedo,
                modifier = Modifier.size(width = 96.dp, height = 44.dp),
            ) { Text("重做") }

            OutlinedButton(
                onClick = {
                    gameBoard.reset()
                    elapsedSeconds = 0 // 重置也归零计时，视为重新开始
                    boardVersion++
                },
                modifier = Modifier.size(width = 96.dp, height = 44.dp),
            ) { Text("重置") }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // —— 错误提示开关 ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "错误提示",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(
                checked = errorHintEnabled,
                onCheckedChange = { errorHintEnabled = it },
            )
        }
    }

    // —— 通关弹窗 ——
    // isSolved 为 true 时弹出。onDismissRequest 留空表示点弹窗外不能关闭，
    // 防止误触跳过结算画面。
    if (isSolved) {
        AlertDialog(
            onDismissRequest = { /* 通关后不关闭弹窗，防止误触跳过 */ },
            title = { Text("🎉 通关啦！", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("关卡：${level.name}")
                    Text("用时：${formatTime(elapsedSeconds)}")
                    if (peekedAnswer) {
                        Text(
                            "（本关查看过参考答案）",
                            modifier = Modifier.padding(top = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // 结算弹窗里展示完整像素画，让"还原出图画"的成就感可见
                    BoardView(
                        level = level,
                        board = level.answerGrid,
                        contradictionCells = emptySet(),
                        errorHintEnabled = false,
                        onCycle = { _, _ -> },
                        onMarkEmpty = { _, _ -> },
                        showClues = false,
                        interactive = false,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "像素画已完整还原！",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 再来一局：重置棋盘、归零计时，并清除"看过答案"标记
                    Button(onClick = {
                        gameBoard.reset()
                        elapsedSeconds = 0
                        peekedAnswer = false
                        boardVersion++
                    }) { Text("再来一局") }
                    // 下一关：本关已通关，由 MainActivity 按关卡顺序传入跳转回调
                    if (onNextLevel != null) {
                        Button(onClick = onNextLevel) { Text("下一关") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("返回关卡列表") }
            },
        )
    }

    // —— 参考答案弹窗 ——
    // 展示完整像素画（答案矩阵、图片模式）。点弹窗外或"关闭"均可关闭，不惩罚。
    if (showAnswer) {
        AlertDialog(
            onDismissRequest = { showAnswer = false },
            title = { Text("参考答案", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "完整像素画预览",
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BoardView(
                        level = level,
                        board = level.answerGrid,
                        contradictionCells = emptySet(),
                        errorHintEnabled = false,
                        onCycle = { _, _ -> },
                        onMarkEmpty = { _, _ -> },
                        showClues = false,
                        interactive = false,
                        // 弹窗内宽度有界：fillMaxWidth(0.85f) 给有限宽度，内部 aspectRatio 自动定高
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAnswer = false }) { Text("关闭") }
            },
        )
    }
}

/** 秒数格式化为 mm:ss，如 65 秒 → "01:05"。 */
private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
