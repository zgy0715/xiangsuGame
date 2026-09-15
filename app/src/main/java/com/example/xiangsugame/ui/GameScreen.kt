package com.example.xiangsugame.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.widget.VideoView
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.GameBoard
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.HintStep
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Validator
import com.example.xiangsugame.model.nextHint
import com.example.xiangsugame.sensor.ShakeDetector
import com.example.xiangsugame.service.SoundEffectManager
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Cyan
import com.example.xiangsugame.ui.theme.GlowPurple
import com.example.xiangsugame.ui.theme.Ink
import com.example.xiangsugame.ui.theme.Sand
import kotlinx.coroutines.delay

/** 宽屏阈值：屏幕宽度 ≥ 此值（横屏手机/平板/桌面窗口）时启用「棋盘 + 侧栏」布局。 */
private val WideLayoutThreshold = 600.dp

/** 棋盘尺寸上限（竖屏 / 宽屏）：防止棋盘被拉伸得过大，超出部分居中留白。 */
private val BoardMaxSizePortrait = 340.dp
private val BoardMaxSizeWide = 480.dp

/** 结算弹窗宽度上限：紧凑卡片，不铺满屏幕。 */
private val OverlayMaxWidth = 300.dp

/**
 * 游戏界面 —— 响应式布局 + 工具笔 + 显式检查。
 *
 * 布局按可用宽度自适应，保证棋盘尽量大：
 *  - 竖屏（窄）：HUD / 进度 / 棋盘(weight) / 紧凑控件三行，压缩底部空间让给棋盘；
 *  - 横屏、平板、桌面窗口（宽）：左侧 HUD+进度+棋盘占满剩余空间，右侧固定侧栏
 *    竖排工具/操作/辅助按钮，大棋盘也能获得接近整屏的显示面积。
 *
 * 控件自上而下（竖屏为三行紧凑胶囊，宽屏侧栏为竖排分组）：
 *  - 工具：✏ 涂黑笔（点未定格涂黑、点已黑格清除，一支笔双向）/ ✕ 留白标记；
 *  - 操作：撤销 / 重做 / 重置；
 *  - 辅助：🔍 检查错误 / 参考答案 / ⚡ 一键补齐（额度制）。
 *
 * 填色：按住棋盘拖动即沿轨迹连续涂当前工具，一条笔划 = 一步撤销；
 * 通关后全屏"🎉 撒花结算"（内容自适应 + 可滚动，任何窗口尺寸都居中完整）。
 */
@Composable
fun GameScreen(
    level: Level,
    account: AccountStore,
    mode: GameMode,
    onBack: () -> Unit,
    onNextLevel: (() -> Unit)? = null,
    /** 首通回调(仅触发一次,携带最终棋盘快照):对局 finish / 成绩上传都接这里。 */
    onSolved: ((board: Array<IntArray>, elapsedSeconds: Int) -> Unit)? = null,
    /** 竞速进度上报(涂格数变化 / 每秒一次;对局页用,单人页不传)。 */
    onProgress: ((filled: Int, elapsedSeconds: Int) -> Unit)? = null,
    /** 外部冻结(对战:对方先完成时暂停本机计时与输入,由对局页覆盖提示)。 */
    externalPaused: Boolean = false,
    /** 外部接管结算(对战用自己的名次页)时关闭内置通关/超时弹窗。 */
    showOverlays: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // remember(level.id)：切换关卡时用新关卡重建状态；同一关卡重组时保持状态
    val gameBoard = remember(level.id) { GameBoard(level.rows, level.cols) }
    val validator = remember(level.id) { Validator(level) }

    // 棋盘版本号：每次格子变化 +1，驱动依赖棋盘的状态/渲染重组
    var boardVersion by remember(level.id) { mutableIntStateOf(0) }
    // 当前选中的填色工具
    var tool by remember(level.id) { mutableStateOf(PaintTool.FILL) }
    // 涂黑笔本笔方向：起笔第一格决定（已黑→清除，其余→涂黑），整笔保持一致
    var strokeTarget by remember(level.id) { mutableStateOf<CellState?>(null) }
    // 显式"检查"结果：非 null 表示检查中（该集合是当时快照，编辑后自动清除）
    var checkedWrong by remember(level.id) { mutableStateOf<Set<Pair<Int, Int>>?>(null) }
    // 「提示下一步」：命中格高亮 + 顶部提示条；无可提示时走 hintNotice 弹窗
    var hintStep by remember(level.id) { mutableStateOf<HintStep?>(null) }
    var hintNotice by remember(level.id) { mutableStateOf<String?>(null) }
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
    // 已满足的提示格集合：窗口已确定且数量达标 → 数字变绿（经典正向反馈，不构成"报错"）
    val satisfiedClues = remember(level.id, boardVersion) { validator.satisfiedClues(board) }
    // 推进进度：已涂黑格数 / 答案需要涂黑的总数
    val filledCount = remember(level.id, boardVersion) { board.sumOf { row -> row.count { it == CellState.FILLED.value } } }
    val totalFilled = remember(level.id) { level.answerGrid.sumOf { row -> row.count { it == 1 } } }
    val progressFraction = if (totalFilled == 0) 0f else filledCount.toFloat() / totalFilled

    // 是否可交互：通关/超时/对局被外部冻结时都停手
    val canInteract = !isSolved && !timedOut && !externalPaused
    // 首通上报只发一次(再来一局后重解会再次解锁,但对局/成绩上传只认首次)
    var solvedReported by remember(level.id) { mutableStateOf(false) }

    // 计时器：协程循环，未通关/未超时/未被对局冻结时每秒 +1；
    // 限时模式倒计时归零即判失败。
    // key 带上 timedOut / externalPaused：状态复位后若不重启本协程，计时就永远停了。
    LaunchedEffect(level.id, isSolved, timedOut, externalPaused) {
        while (!isSolved && !timedOut && !externalPaused) {
            delay(1000)
            elapsedSeconds += 1
            if (mode == GameMode.TIMED && elapsedSeconds >= limitSeconds && !isSolved) {
                timedOut = true
            }
        }
    }

    // 通关落库：内置关(正 id)写入解锁集并顺序解锁;在线关(负 id)只记录"玩过";
    // 两种情况的首次通关都触发 onSolved 钩子(上传成绩 / 对局 finish 由调用方处理)
    LaunchedEffect(level.id, isSolved) {
        if (isSolved && !solvedReported) {
            solvedReported = true
            SoundEffectManager.win()
            if (level.id > 0) account.markCompleted(level.id) else account.recordOnlineSolved(level.id)
            val snapshot = gameBoard.board.map(IntArray::clone).toTypedArray()
            onSolved?.invoke(snapshot, elapsedSeconds)
        }
    }

    // 竞速进度上报:格子数或秒表变化时通知外层(传输层自带节流)
    LaunchedEffect(level.id, boardVersion, isSolved, timedOut, externalPaused, elapsedSeconds) {
        if (!isSolved && !timedOut && !externalPaused) {
            onProgress?.invoke(filledCount, elapsedSeconds)
        }
    }

    // —— 公共控件回调（两种布局复用）——
    val onStrokeBegin: () -> Unit = {
        // 玩家一动手，旧"检查"结果即失效（避免过期标红误导）
        checkedWrong = null
        hintStep = null // 提示格已按新盘面重算
        strokeTarget = null // 新一笔重新判定涂/擦方向
        gameBoard.startStroke()
        SoundEffectManager.click() // 起笔音效(一笔响一次,避免连涂太吵)
    }
    val onStrokeCell: (Int, Int) -> Unit = { r, c ->
        val target = when (tool) {
            // 留白笔：标记"确定留白"
            PaintTool.EMPTY -> CellState.MARKED_EMPTY
            // 涂黑笔：起笔第一格决定本笔方向 —— 落在黑格上=清除，其余=涂黑
            PaintTool.FILL -> strokeTarget ?: run {
                val t = if (board[r][c] == CellState.FILLED.value) CellState.UNDECIDED else CellState.FILLED
                strokeTarget = t
                t
            }
        }
        if (gameBoard.strokeCell(r, c, target)) boardVersion++
    }
    val onStrokeEnd: () -> Unit = {
        if (gameBoard.commitStroke()) boardVersion++
    }
    val onStrokeCancel: () -> Unit = {
        gameBoard.cancelStroke()
        boardVersion++
    }
    val onToolSelect: (PaintTool) -> Unit = {
        SoundEffectManager.click()
        tool = it
        checkedWrong = null
    }
    val onUndo: () -> Unit = {
        SoundEffectManager.click()
        checkedWrong = null
        hintStep = null
        if (gameBoard.undo()) boardVersion++
    }
    val onRedo: () -> Unit = {
        SoundEffectManager.click()
        checkedWrong = null
        hintStep = null
        if (gameBoard.redo()) boardVersion++
    }
    val onReset: () -> Unit = {
        SoundEffectManager.click()
        checkedWrong = null
        hintStep = null
        gameBoard.reset()
        elapsedSeconds = 0 // 重置也归零计时，视为重新开始
        boardVersion++
    }

    // 摇一摇重置棋盘:加速度传感器,设置页开关控制;带"已摇"提示状态
    val context = LocalContext.current
    var shakeHint by remember(level.id) { mutableStateOf(false) }
    val shakeEnabled = LocalSettings.shakeToResetEnabled
    DisposableEffect(level.id, shakeEnabled) {
        if (!shakeEnabled) return@DisposableEffect onDispose {}
        val detector = ShakeDetector(context) {
            onReset()
            shakeHint = true
        }
        detector.start()
        onDispose { detector.stop() }
    }
    // 摇一摇提示 1.2 秒后自动消失
    LaunchedEffect(shakeHint) {
        if (shakeHint) {
            delay(1200)
            shakeHint = false
        }
    }
    val onFill: () -> Unit = {
        SoundEffectManager.click()
        if (account.useFill()) {
            gameBoard.applySolution(level.answerGrid)
            checkedWrong = null
            hintStep = null
            boardVersion++
        }
    }
    val onCheck: () -> Unit = {
        val wrongs = validator.wrongCells(board)
        checkedWrong = wrongs
        if (wrongs.isEmpty()) SoundEffectManager.click() else SoundEffectManager.error()
    }
    /** 「提示下一步」：以当前盘面为约束推导确定格（矛盾/完成时给文字说明）。 */
    val onHint: () -> Unit = {
        SoundEffectManager.click()
        checkedWrong = null
        val step = nextHint(level.clueGrid, board)
        if (step.hasCell) hintStep = step else {
            hintStep = null
            hintNotice = step.message
        }
    }
    val onShowAnswer: () -> Unit = {
        SoundEffectManager.click()
        peekedAnswer = true
        showAnswer = true
    }
    val onClearWrong: () -> Unit = {
        SoundEffectManager.click()
        // 把检查出的所有错误格一键清回"未决定"（一条笔划 = 一步可撤销）
        gameBoard.startStroke()
        for ((r, c) in checkedWrong.orEmpty()) {
            gameBoard.strokeCell(r, c, CellState.UNDECIDED)
        }
        gameBoard.commitStroke()
        checkedWrong = null
        boardVersion++
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        val isWide = maxWidth >= WideLayoutThreshold

        if (isWide) {
            // ———— 宽屏：左棋盘 + 右侧栏（横屏手机/平板/桌面窗口）————
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 16.dp),
                ) {
                    HudRow(
                        level = level,
                        mode = mode,
                        elapsedSeconds = elapsedSeconds,
                        limitSeconds = limitSeconds,
                        onBack = onBack,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressRow(
                        filled = filledCount,
                        total = totalFilled,
                        fraction = progressFraction,
                    )
                    HintBar(hintStep = hintStep)
                    if (shakeHint) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF7E57C2).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFF7E57C2).copy(alpha = 0.5f)),
                        ) {
                            Text(
                                "📱 已摇一摇,棋盘已重置",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFB39DDB),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                    // 棋盘在剩余空间内居中，尺寸封顶 BoardMaxSizeWide，不再无限拉伸
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoardView(
                            level = level,
                            // 通关瞬间直接用答案矩阵渲染，避免"标记空白"圆点残留在空白格上
                            board = if (isSolved) level.answerGrid else board,
                            satisfiedClues = satisfiedClues,
                            revealedWrong = checkedWrong.orEmpty(),
                            hintCell = hintStep?.let { it.row to it.col },
                            tool = tool,
                            onStrokeBegin = onStrokeBegin,
                            onStrokeCell = onStrokeCell,
                            onStrokeEnd = onStrokeEnd,
                            onStrokeCancel = onStrokeCancel,
                            // 通关后进入"图片模式"：隐藏提示数字、禁止点击，揭晓完整像素画；
                            // 限时超时失败同样冻结棋盘
                            showClues = !isSolved,
                            interactive = canInteract,
                            modifier = Modifier
                                .widthIn(max = BoardMaxSizeWide),
                        )
                    }
                }

                // 右侧控制栏：竖排分组，小窗口时可滚动
                Column(
                    modifier = Modifier
                        .width(230.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SectionLabel("填色工具")
                    ToolGroup(
                        selected = tool,
                        enabled = canInteract,
                        onSelect = onToolSelect,
                        stacked = true,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionLabel("操作")
                    ActionGroup(
                        canUndo = gameBoard.canUndo,
                        canRedo = gameBoard.canRedo,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onReset = onReset,
                        stacked = true,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionLabel("辅助")
                    AssistGroup(
                        stacked = true,
                        checkEnabled = canInteract,
                        fillEnabled = account.fillQuotaRemaining > 0 && canInteract,
                        fillQuota = account.fillQuotaRemaining,
                        checkedWrong = checkedWrong,
                        onCheck = onCheck,
                        onHint = onHint,
                        onShowAnswer = onShowAnswer,
                        onFill = onFill,
                        onClearWrong = onClearWrong,
                        onDismissCheck = { checkedWrong = null },
                    )
                }
            }
        } else {
            // ———— 竖屏：紧凑纵向堆叠，底部控件压到三行，把最大空间让给棋盘 ————
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HudRow(
                    level = level,
                    mode = mode,
                    elapsedSeconds = elapsedSeconds,
                    limitSeconds = limitSeconds,
                    onBack = onBack,
                )
                Spacer(modifier = Modifier.height(6.dp))
                ProgressRow(
                    filled = filledCount,
                    total = totalFilled,
                    fraction = progressFraction,
                )
                HintBar(hintStep = hintStep)
                if (shakeHint) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF7E57C2).copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color(0xFF7E57C2).copy(alpha = 0.5f)),
                    ) {
                        Text(
                            "📱 已摇一摇,棋盘已重置",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFB39DDB),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BoardView(
                        level = level,
                        board = if (isSolved) level.answerGrid else board,
                        satisfiedClues = satisfiedClues,
                        revealedWrong = checkedWrong.orEmpty(),
                        hintCell = hintStep?.let { it.row to it.col },
                        tool = tool,
                        onStrokeBegin = onStrokeBegin,
                        onStrokeCell = onStrokeCell,
                        onStrokeEnd = onStrokeEnd,
                        onStrokeCancel = onStrokeCancel,
                        showClues = !isSolved,
                        interactive = canInteract,
                        modifier = Modifier
                            .widthIn(max = BoardMaxSizePortrait),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // —— 紧凑底部:工具行 + 一行6个操作小胶囊(撤销/重做/重置/提示/检查/补齐)——
                ToolGroup(
                    selected = tool,
                    enabled = canInteract,
                    onSelect = onToolSelect,
                    stacked = false,
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 检查出错误时切换到清错条;否则一行6个紧凑按钮
                if (checkedWrong != null) {
                    CheckResultBar(
                        wrongCount = checkedWrong!!.size,
                        onClearWrong = onClearWrong,
                        onDismiss = { checkedWrong = null },
                    )
                } else {
                    CompactActionsRow(
                        canUndo = gameBoard.canUndo,
                        canRedo = gameBoard.canRedo,
                        canInteract = canInteract,
                        fillEnabled = account.fillQuotaRemaining > 0 && canInteract,
                        fillQuota = account.fillQuotaRemaining,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onReset = onReset,
                        onHint = onHint,
                        onCheck = onCheck,
                        onFill = onFill,
                        onShowAnswer = onShowAnswer,
                    )
                }
            }
        }
    }

    // —— 通关撒花结算（全屏遮罩;对局模式由外部结算接管）——
    if (isSolved && showOverlays) {
        WinOverlay(
            level = level,
            elapsedSeconds = elapsedSeconds,
            peekedAnswer = peekedAnswer,
            onReplay = {
                gameBoard.reset()
                elapsedSeconds = 0
                peekedAnswer = false
                checkedWrong = null
                solvedReported = false
                boardVersion++
            },
            onNextLevel = onNextLevel,
            onBack = onBack,
        )
    }

    // —— 限时挑战超时失败（全屏遮罩，优先于成功结算）——
    if (!isSolved && timedOut && showOverlays) {
        TimeoutOverlay(
            level = level,
            onReplay = {
                gameBoard.reset()
                elapsedSeconds = 0
                timedOut = false
                checkedWrong = null
                solvedReported = false
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
                        satisfiedClues = emptySet(),
                        showClues = false,
                        interactive = false,
                        zoomable = true,
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAnswer = false }) { Text("关闭") }
            },
        )
    }

    // —— 「提示下一步」无可提示格(盘面矛盾/已填满)时的说明 ——
    hintNotice?.let {
        AlertDialog(
            onDismissRequest = { hintNotice = null },
            title = { Text("💡 提示", fontWeight = FontWeight.Bold) },
            text = { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { hintNotice = null }) { Text("知道了") }
            },
        )
    }
}

// ============================================================
// 通用小控件（HUD / 进度 / 工具 / 操作 / 辅助）
// ============================================================

/** 顶部 HUD：返回 + 关卡名（难度·尺寸·模式·身份）+ 计时胶囊。 */
@Composable
private fun HudRow(
    level: Level,
    mode: GameMode,
    elapsedSeconds: Int,
    limitSeconds: Int,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回按钮（圆形，单层可点击）
        Surface(
            onClick = onBack,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                "${level.difficulty.label} ★ · ${level.rows}×${level.cols} · " +
                    if (mode == GameMode.TIMED) "⏱限时" else "🎮自由",
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
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 「提示下一步」提示条：高亮格配一句落笔建议（有提示格时展示）。 */
@Composable
private fun HintBar(hintStep: HintStep?) {
    val hint = hintStep ?: return
    if (!hint.hasCell) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = Amber.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.45f)),
    ) {
        Text(
            "💡 ${hint.message}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Amber,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 进度条行：品牌渐变填充 + 高亮数字，已涂 X / 应涂 N。 */
@Composable
private fun ProgressRow(filled: Int, total: Int, fraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Coral, GlowPurple, Cyan))),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "已涂 $filled / $total",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
    }
}

/** 宽屏侧栏的小节标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

/**
 * 填色工具组：✏ 涂黑笔（点黑格即清除）/ ✕ 留白标记。
 * 统一用分段控件（SegmentedControl）：选中段品牌渐变高亮，游戏机按键感。
 */
@Composable
private fun ToolGroup(
    selected: PaintTool,
    enabled: Boolean,
    onSelect: (PaintTool) -> Unit,
    stacked: Boolean,
) {
    SegmentedControl(
        items = listOf(
            PaintTool.FILL to "✏️ 涂黑 / 清除",
            PaintTool.EMPTY to "✕ 留白",
        ),
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
    )
}

/**
 * 操作组：撤销 / 重做 / 重置。
 * stacked=true 竖排（宽屏侧栏）；false 横排三等分（竖屏）。
 */
@Composable
private fun ActionGroup(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    stacked: Boolean,
) {
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionPill("↩ 撤销", canUndo, Modifier.fillMaxWidth(), onUndo)
            ActionPill("↪ 重做", canRedo, Modifier.fillMaxWidth(), onRedo)
            ActionPill("↺ 重置", true, Modifier.fillMaxWidth(), onReset)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionPill("↩ 撤销", canUndo, Modifier.weight(1f), onUndo)
            ActionPill("↪ 重做", canRedo, Modifier.weight(1f), onRedo)
            ActionPill("↺ 重置", true, Modifier.weight(1f), onReset)
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * 紧凑操作行:撤销 / 重做 / 重置 / 提示 / 检查 / 补齐 一行6个等分小胶囊。
 * 竖屏底部把原"操作组 + 辅助组"两行压成一行,腾出空间给棋盘。
 * 参考答案以一个小号文字链接形式放在该行下方(居中、占位极小)。
 * 按钮高度固定 36dp,字号 11sp,图标+短文字,触摸目标靠宽度保证(6等分仍 ≥44dp)。
 */
@Composable
private fun CompactActionsRow(
    canUndo: Boolean,
    canRedo: Boolean,
    canInteract: Boolean,
    fillEnabled: Boolean,
    fillQuota: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onHint: () -> Unit,
    onCheck: () -> Unit,
    onFill: () -> Unit,
    onShowAnswer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CompactPill("↩ 撤销", canUndo, Modifier.weight(1f), onUndo)
            CompactPill("↪ 重做", canRedo, Modifier.weight(1f), onRedo)
            CompactPill("↺ 重置", true, Modifier.weight(1f), onReset)
            CompactPill("💡 提示", canInteract, Modifier.weight(1f), onHint)
            CompactPill("🔍 检查", canInteract, Modifier.weight(1f), onCheck)
            // 补齐按钮为品牌强调色(显眼),配额为 0 时禁用
            CompactPill(
                text = "⚡ 补齐",
                enabled = fillEnabled,
                modifier = Modifier.weight(1f),
                onClick = onFill,
                highlight = true,
            )
        }
        // 参考答案:小号文字链接,占位极小,不抢棋盘空间
        TextButton(
            onClick = onShowAnswer,
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                "👁 参考答案",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 紧凑小胶囊按钮:固定 36dp 高、11sp 字号、圆角 14dp、半透明背景。
 * 默认(primaryContainer);highlight=true 用品牌珊瑚色强调(补齐按钮)。
 * 触摸目标由 width 等分保证(6 个一行在常见手机宽度下仍 ≥44dp)。
 */
@Composable
private fun CompactPill(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val bg = if (!enabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    } else if (highlight) {
        Coral.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    }
    val fg = if (!enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else if (highlight) {
        Coral
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val border = if (highlight && enabled) {
        BorderStroke(1.dp, Coral.copy(alpha = 0.5f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        contentColor = fg,
        border = border,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 辅助组：检查错误 / 参考答案 / 一键补齐（额度制）。
 * 检查中时整行替换为"检查结果条"（清除错误格 / 收起）。
 */
@Composable
private fun AssistGroup(
    stacked: Boolean,
    checkEnabled: Boolean,
    fillEnabled: Boolean,
    fillQuota: Int,
    checkedWrong: Set<Pair<Int, Int>>?,
    onCheck: () -> Unit,
    onHint: () -> Unit,
    onShowAnswer: () -> Unit,
    onFill: () -> Unit,
    onClearWrong: () -> Unit,
    onDismissCheck: () -> Unit,
) {
    if (checkedWrong != null) {
        CheckResultBar(
            wrongCount = checkedWrong.size,
            onClearWrong = onClearWrong,
            onDismiss = onDismissCheck,
        )
        return
    }
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onFill,
                enabled = fillEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Coral,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (fillQuota > 0) "⚡ 一键补齐（剩 $fillQuota 次）" else "⚡ 一键补齐（次数已用完）",
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = onHint,
                enabled = checkEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("💡 提示下一步") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = checkEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("🔍 检查错误") }
                OutlinedButton(
                    onClick = onShowAnswer,
                    modifier = Modifier.weight(1f),
                ) { Text("参考答案") }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onHint,
                    enabled = checkEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("💡 提示", maxLines = 1) }
                OutlinedButton(
                    onClick = onCheck,
                    enabled = checkEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("🔍 检查", maxLines = 1) }
                Button(
                    onClick = onFill,
                    enabled = fillEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Coral,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1.2f),
                ) {
                    Text(
                        if (fillQuota > 0) "⚡ 补齐 $fillQuota" else "⚡ 补齐",
                        maxLines = 1,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            OutlinedButton(
                onClick = onShowAnswer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("参考答案", maxLines = 1) }
        }
    }
}

/** 检查结果条：显示错误数 + "清除错误格" + "收起"。 */
@Composable
private fun CheckResultBar(
    wrongCount: Int,
    onClearWrong: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (wrongCount > 0) "✗ 检查到 $wrongCount 处错误"
                else "✓ 检查：目前全对",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (wrongCount > 0) {
                TextButton(
                    onClick = onClearWrong,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("清除错误格") }
            }
            TextButton(onClick = onDismiss) { Text("收起") }
        }
    }
}

// ============================================================
// 全屏结算遮罩（居中 + 自适应 + 可滚动，任何窗口尺寸都完整显示）
// ============================================================

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
    var showZoomed by remember { mutableStateOf(false) }

    // heightIn(max) 限制卡片不超过屏幕：小窗口下内容改为内部滚动，保证卡片始终水平垂直居中
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
    ) {
        // 飘落彩纸（背景层）
        Confetti(modifier = Modifier.fillMaxSize())

        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .heightIn(max = maxHeight - 24.dp)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Coral.copy(alpha = 0.55f),
                            GlowPurple.copy(alpha = 0.55f),
                            Cyan.copy(alpha = 0.55f),
                        ),
                    ),
                    shape = RoundedCornerShape(24.dp),
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎉 太棒了！", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
                Text(
                    "成功还原「${level.name}」",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

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

                // 揭晓的大号像素画：宽度自适应卡片，封顶 180dp（小屏不溢出、大屏不虚大）
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .widthIn(max = 180.dp)
                        .aspectRatio(1f)
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

                Spacer(modifier = Modifier.height(8.dp))

                // 通关庆祝视频:res/raw/celebrate.mp4(不存在时不显示,不影响通关)
                val ctx = LocalContext.current
                val videoResId = remember { ctx.resources.getIdentifier("celebrate", "raw", ctx.packageName) }
                if (videoResId != 0) {
                    Text(
                        "🎬 通关庆祝",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    AndroidView(
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$videoResId"))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    mp.setVolume(0.4f, 0.4f)
                                    start()
                                }
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 棋盘预览偏小时提供"放大查看"全屏模式（可缩放/平移），大棋盘也能看清图案
                TextButton(onClick = { showZoomed = true }) {
                    Text("🔍 放大查看图案", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onReplay,
                        modifier = Modifier.weight(1f),
                    ) { Text("再来一局") }
                    if (onNextLevel != null) {
                        GradientButton(
                            text = "下一关 ▶",
                            onClick = onNextLevel,
                            modifier = Modifier.weight(1.3f),
                        )
                    }
                }
                TextButton(onClick = onBack) { Text("返回关卡列表") }
            }
        }

        // —— 全屏放大查看像素画：双指缩放 + 拖动平移 + 右上角关闭 ——
        if (showZoomed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF2111118)),
                contentAlignment = Alignment.Center,
            ) {
                BoardView(
                    level = level,
                    board = level.answerGrid,
                    satisfiedClues = emptySet(),
                    showClues = false,
                    interactive = false,
                    zoomable = true,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 20.dp),
                )
                Surface(
                    onClick = { showZoomed = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    shape = CircleShape,
                    color = Color(0xD9262338),
                    contentColor = Color(0xFFF0EFFB),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    shadowElevation = 4.dp,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.86f)
                .heightIn(max = maxHeight - 24.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(24.dp),
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⏰", fontSize = 44.sp)
                Text(
                    "挑战失败！",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error,
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
