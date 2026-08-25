package com.example.xiangsugame.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.GameProgress
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.GameBoard
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.UserRole
import com.example.xiangsugame.model.Validator
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Cyan
import com.example.xiangsugame.ui.theme.Ink
import com.example.xiangsugame.ui.theme.Sand
import kotlinx.coroutines.delay

/**
 * 游戏界面 —— 游戏化改版。
 *
 * 布局自上而下：
 *  - 顶部 HUD：返回按钮 + 关卡名/难度 + 计时胶囊；
 *  - 进度条：实时显示"已涂 X / 应涂 N 格"，有推进感；
 *  - 中部：棋盘（BoardView，占据剩余空间）；
 *  - 底部操作栏：撤销 / 重做 / 重置三个圆形按钮 + 错误提示开关 + 参考答案。
 *
 * 通关后不再用普通弹窗，而是全屏"🎉 撒花结算"：飘落的彩纸 + 大号像素画揭晓，
 * 给足成就感。状态管理沿用"就地修改 + boardVersion 版本号驱动重组"的方案。
 */
@Composable
fun GameScreen(
    level: Level,
    progress: GameProgress,
    mode: GameMode,
    role: UserRole,
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

    // 限时挑战：本关时限（未配置时按难度给默认值）与是否已超时失败
    val limitSeconds = remember(level.id) {
        level.timedLimitSeconds ?: when (level.difficulty) {
            Difficulty.EASY -> 180
            Difficulty.MEDIUM -> 360
            Difficulty.HARD -> 600
        }
    }
    var timedOut by remember(level.id) { mutableStateOf(false) }

    val board = gameBoard.board
    // 胜利判定：棋盘每次变化后重算
    val isSolved = remember(level.id, boardVersion) { validator.isSolved(board) }
    // 错误格集合：仅当开启错误提示时才计算（省去无谓的扫描开销）
    val wrongCells = remember(level.id, boardVersion, errorHintEnabled) {
        if (errorHintEnabled) validator.wrongCells(board) else emptySet()
    }
    // 已满足的提示格集合：窗口已确定且数量达标 → 数字变绿（经典反馈）
    val satisfiedClues = remember(level.id, boardVersion) { validator.satisfiedClues(board) }
    // 推进进度：已涂黑格数 / 答案需要涂黑的总数
    val filledCount = remember(level.id, boardVersion) { board.sumOf { row -> row.count { it == CellState.FILLED.value } } }
    val totalFilled = remember(level.id) { level.answerGrid.sumOf { row -> row.count { it == 1 } } }
    val progressFraction = if (totalFilled == 0) 0f else filledCount.toFloat() / totalFilled

    // 计时器：协程循环，未通关且未超时时每秒 +1；限时模式倒计时归零即判失败
    LaunchedEffect(level.id, isSolved) {
        while (!isSolved && !timedOut) {
            delay(1000)
            elapsedSeconds += 1
            if (mode == GameMode.TIMED && elapsedSeconds >= limitSeconds && !isSolved) {
                timedOut = true
            }
        }
    }

    // 通关后把本关标记为已完成并持久化（顺序解锁下一关）
    LaunchedEffect(level.id, isSolved) {
        if (isSolved) progress.markCompleted(level.id)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 顶部 HUD：返回 + 关卡信息 + 计时 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮（圆形，单层可点击）
            Surface(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    level.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
                Text(
                    buildString {
                        append("${level.difficulty.label} ★ · ${level.rows}×${level.cols}")
                        append(if (mode == GameMode.TIMED) " · ⏱限时" else " · 🎮自由")
                        append(if (role == UserRole.ADMIN) " · 👑管理员" else " · 👤玩家")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 计时胶囊：自由模式显示用时；限时模式显示倒计时，剩余不足 1 分钟变红
            val remainingSeconds = (limitSeconds - elapsedSeconds).coerceAtLeast(0)
            val isLow = mode == GameMode.TIMED && remainingSeconds < 60
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isLow) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isLow) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    "⏱ ${formatTime(if (mode == GameMode.TIMED) remainingSeconds else elapsedSeconds)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // —— 进度条 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp),
                color = Coral,
                trackColor = Sand,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "已涂 $filledCount / $totalFilled",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // —— 中部：棋盘 ——
        BoardView(
            level = level,
            // 通关瞬间直接用答案矩阵渲染，避免"标记空白"圆点残留在空白格上
            board = if (isSolved) level.answerGrid else board,
            wrongCells = wrongCells,
            satisfiedClues = satisfiedClues,
            errorHintEnabled = errorHintEnabled,
            onCycle = { r, c ->
                if (gameBoard.cycleCell(r, c)) boardVersion++
            },
            onMarkEmpty = { r, c ->
                if (gameBoard.board[r][c] != CellState.MARKED_EMPTY.value) {
                    gameBoard.setCell(r, c, CellState.MARKED_EMPTY)
                    boardVersion++
                }
            },
            // 通关后进入"图片模式"：隐藏提示数字、禁止点击，揭晓完整像素画；
            // 限时超时失败同样冻结棋盘
            showClues = !isSolved,
            interactive = !isSolved && !timedOut,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // —— 底部操作栏：撤销 / 重做 / 重置 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundActionButton(
                icon = "↩",
                label = "撤销",
                enabled = gameBoard.canUndo,
                onClick = { if (gameBoard.undo()) boardVersion++ },
            )
            RoundActionButton(
                icon = "↪",
                label = "重做",
                enabled = gameBoard.canRedo,
                onClick = { if (gameBoard.redo()) boardVersion++ },
            )
            RoundActionButton(
                icon = "↺",
                label = "重置",
                enabled = true,
                onClick = {
                    gameBoard.reset()
                    elapsedSeconds = 0 // 重置也归零计时，视为重新开始
                    boardVersion++
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // —— 一键补齐：消耗全局额度（全游戏仅 2 次），自动补全全部正确像素 ——
        Button(
            onClick = {
                if (progress.useFill()) {
                    gameBoard.applySolution(level.answerGrid)
                    boardVersion++
                }
            },
            enabled = progress.fillQuotaRemaining > 0 && !isSolved && !timedOut,
            colors = ButtonDefaults.buttonColors(
                containerColor = Coral,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (progress.fillQuotaRemaining > 0) {
                    "⚡ 一键补齐（剩 ${progress.fillQuotaRemaining} 次）"
                } else {
                    "⚡ 一键补齐（次数已用完）"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // —— 错误提示开关 + 参考答案 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "错误提示",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 4.dp),
            )
            Switch(
                checked = errorHintEnabled,
                onCheckedChange = { errorHintEnabled = it },
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                peekedAnswer = true
                showAnswer = true
            }) { Text("参考答案") }
        }
    }

    // —— 通关撒花结算（全屏遮罩）——
    if (isSolved) {
        WinOverlay(
            level = level,
            elapsedSeconds = elapsedSeconds,
            peekedAnswer = peekedAnswer,
            onReplay = {
                gameBoard.reset()
                elapsedSeconds = 0
                peekedAnswer = false
                boardVersion++
            },
            onNextLevel = onNextLevel,
            onBack = onBack,
        )
    }

    // —— 限时挑战超时失败（全屏遮罩，优先于成功结算）——
    if (!isSolved && timedOut) {
        TimeoutOverlay(
            level = level,
            onReplay = {
                gameBoard.reset()
                elapsedSeconds = 0
                timedOut = false
                boardVersion++
            },
            onBack = onBack,
        )
    }

    // —— 参考答案弹窗 ——
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
                        wrongCells = emptySet(),
                        satisfiedClues = emptySet(),
                        errorHintEnabled = false,
                        onCycle = { _, _ -> },
                        onMarkEmpty = { _, _ -> },
                        showClues = false,
                        interactive = false,
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

/** 圆形操作按钮：圆底 + 符号 + 下方小字标签（撤销/重做/重置）。 */
@Composable
private fun RoundActionButton(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = if (enabled) 2.dp else 0.dp,
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 24.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 通关结算：全屏遮罩 + 飘落彩纸 + 大号像素画揭晓。 */
@Composable
private fun WinOverlay(
    level: Level,
    elapsedSeconds: Int,
    peekedAnswer: Boolean,
    onReplay: () -> Unit,
    onNextLevel: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        // 飘落彩纸（背景层）
        Confetti(modifier = Modifier.fillMaxSize())

        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎉 太棒了！", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
                Text(
                    "成功还原「${level.name}」",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "用时 ${formatTime(elapsedSeconds)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                if (peekedAnswer) {
                    Text(
                        "（本关查看过参考答案）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 揭晓的大号像素画
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    PicturePreview(
                        answer = level.answerGrid,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onReplay) { Text("再来一局") }
                    if (onNextLevel != null) {
                        Button(onClick = onNextLevel) { Text("下一关") }
                    }
                }
                TextButton(onClick = onBack) { Text("返回关卡列表") }
            }
        }
    }
}

/** 限时挑战超时失败结算：全屏遮罩 + 重试/返回。 */
@Composable
private fun TimeoutOverlay(
    level: Level,
    onReplay: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⏰", fontSize = 44.sp)
                Text(
                    "挑战失败！",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFB71C1C),
                )
                Text(
                    "「${level.name}」超时未完成\n再来一局，或者返回列表换一关",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onReplay) { Text("再来一局") }
                    OutlinedButton(onClick = onBack) { Text("返回关卡列表") }
                }
            }
        }
    }
}

/** 飘落彩纸动画：40 片彩色小纸片沿对角线下落 + 旋转。 */
@Composable
private fun Confetti(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "confetti")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2600, easing = LinearEasing)),
        label = "confettiFall",
    )
    val colors = listOf(Coral, Cyan, Amber, Color(0xFF66BB6A), Color(0xFF7E57C2))
    Canvas(modifier = modifier) {
        for (i in 0 until 40) {
            // 每片的横纵位置由 index 和相位 t 计算，避免为每片单独开动画
            val x = ((i * 67 + 13) % 100) / 100f * size.width
            val y = ((t * 120 + i * 37) % 130 - 15) / 100f * size.height
            val s = 6.dp.toPx() + (i % 3) * 2.dp.toPx()
            rotate(degrees = t * 360 + i * 47, pivot = Offset(x, y)) {
                drawRect(
                    color = colors[i % colors.size],
                    topLeft = Offset(x, y),
                    size = Size(s, s * 0.6f),
                )
            }
        }
    }
}

/** 秒数格式化为 mm:ss，如 65 秒 → "01:05"。 */
private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
