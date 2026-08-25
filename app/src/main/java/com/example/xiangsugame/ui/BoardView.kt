package com.example.xiangsugame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.Level
import kotlin.math.max

// ============================================================
// 棋盘配色 —— 清爽"米色纸 + 深墨"风，解谜时看着舒服，揭晓时画面干净
// ============================================================
private val GridLine = Color(0xFFD9D3EC)         // 网格线：淡薰衣草
private val CellBlank = Color(0xFFFCFBFE)        // 空白格：近白
private val CellMarkedBg = Color(0xFFEFECF9)     // 标记空白格底色：浅薰衣草，一眼可辨
private val CellFilled = Color(0xFF33304F)       // 解谜涂黑：深墨蓝
private val MarkDot = Color(0xFFAFA9C9)          // 标记空白的小圆点
private val WrongBg = Color(0xFFFFD6D6)          // 错误格红底（浅红，一眼可辨）
private val WrongBorder = Color(0xFFD32F2F)      // 错误格红框
private val WrongMark = Color(0xFFE53935)        // 错误格红叉
private val SatisfiedClue = Color(0xFF43A57F)    // 已满足提示的数字色：柔绿
private val TextOnDark = Color.White             // 数字在涂黑格上的颜色
private val FrameColor = Color(0xFFC9C2E2)       // 棋盘外框（装裱）色
private val PictureFilled = Color(0xFF2B2B4E)    // 图片模式下的"墨色"像素（更纯的墨蓝）

/** 提示数字按大小分色：0 灰、小数字偏蓝、大数字偏暖，增强可读性 + 游戏感。 */
private fun clueColor(value: Int): Color = when (value) {
    0 -> Color(0xFFA89A82)
    in 1..2 -> Color(0xFF1E88E5)
    in 3..4 -> Color(0xFF00838F)
    in 5..6 -> Color(0xFF6A1B9A)
    else -> Color(0xFFD32F2F)
}

/**
 * 棋盘渲染。
 *
 * 用单个 Canvas 把整个棋盘画出来：规则网格统一绘制性能好、代码简单。
 * 这次重写主要在做"游戏感"：
 *  - 棋盘圆角 + 装裱外框，像一块真正的游戏板；
 *  - 空白 / 涂黑 / 标记空白三种状态底色区分明显；
 *  - 提示数字按大小分色，高对比度、可读性好；
 *  - 图片模式（showClues=false）揭晓纯墨色像素画，配上外框像一幅装裱好的画。
 *
 * 交互：
 *  - 单击（onTap）：循环切换三态（空白 → 涂黑 → 标记空白 → 空白）；
 *  - 长按（onLongPress）：直接标记为空白。
 */
@Composable
fun BoardView(
    level: Level,
    board: Array<IntArray>,
    wrongCells: Set<Pair<Int, Int>>,
    errorHintEnabled: Boolean,
    onCycle: (row: Int, col: Int) -> Unit,
    onMarkEmpty: (row: Int, col: Int) -> Unit,
    satisfiedClues: Set<Pair<Int, Int>> = emptySet(),
    showClues: Boolean = true,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rows = level.rows
    val cols = level.cols
    val clueGrid = level.clueGrid
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current

    // 手势处理：把点击坐标换算成格子下标；interactive=false（预览/结算）时不挂手势
    val boardModifier = if (interactive) {
        modifier
            .aspectRatio(cols.toFloat() / rows.toFloat())
            .pointerInput(rows, cols) {
                detectTapGestures(
                    onTap = { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val cellW = size.width / cols
                        val cellH = size.height / rows
                        val c = (offset.x / cellW).toInt().coerceIn(0, cols - 1)
                        val r = (offset.y / cellH).toInt().coerceIn(0, rows - 1)
                        onCycle(r, c)
                    },
                    onLongPress = { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val cellW = size.width / cols
                        val cellH = size.height / rows
                        val c = (offset.x / cellW).toInt().coerceIn(0, cols - 1)
                        val r = (offset.y / cellH).toInt().coerceIn(0, rows - 1)
                        onMarkEmpty(r, c)
                    },
                )
            }
    } else {
        modifier.aspectRatio(cols.toFloat() / rows.toFloat())
    }

    Canvas(modifier = boardModifier) {
        val cellW = size.width / cols
        val cellH = size.height / rows
        val corner = 14.dp.toPx()

        // 先裁出圆角区域，让整个棋盘呈现圆角"游戏板"造型
        clipPath(Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = size.height,
                    radiusX = corner, radiusY = corner,
                )
            )
        }) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val left = c * cellW
                    val top = r * cellH

                    val state = CellState.fromValue(board[r][c])
                    val isWrong = showClues && errorHintEnabled && (r to c) in wrongCells

                    // —— 1. 背景 ——
                    // 优先级：图片模式墨黑 > 错误红底 > 涂黑深墨 > 标记空白浅沙 > 空白米白
                    val bg = when {
                        !showClues && state == CellState.FILLED -> PictureFilled
                        isWrong -> WrongBg
                        state == CellState.FILLED -> CellFilled
                        state == CellState.MARKED_EMPTY -> CellMarkedBg
                        else -> CellBlank
                    }
                    drawRect(color = bg, topLeft = Offset(left, top), size = Size(cellW, cellH))

                    // —— 2. "标记空白"标记：小圆点 ——
                    if (state == CellState.MARKED_EMPTY && showClues) {
                        drawCircle(
                            color = MarkDot,
                            radius = cellW * 0.15f,
                            center = Offset(left + cellW / 2, top + cellH / 2),
                        )
                    }

                    // —— 3. 提示数字（图片模式不画，让墨色像素合并成完整剪影）——
                    if (showClues) {
                        val clue = clueGrid[r][c]
                        if (clue >= 0) {
                            val isSatisfied = (r to c) in satisfiedClues
                            val fontSize = with(density) { max(cellW, cellH) * 0.46f }.toSp()
                            val layout = textMeasurer.measure(
                                text = AnnotatedString(clue.toString()),
                                style = TextStyle(
                                    // 已满足的提示数字变绿（经典反馈）；错误格用线索色保证可读；正常涂黑格用白字
                                    color = when {
                                        isWrong -> clueColor(clue)
                                        isSatisfied && state == CellState.FILLED -> Color(0xFF9BE8C0)
                                        isSatisfied -> SatisfiedClue
                                        state == CellState.FILLED -> TextOnDark
                                        else -> clueColor(clue)
                                    },
                                    fontSize = fontSize,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    left + (cellW - layout.size.width) / 2,
                                    top + (cellH - layout.size.height) / 2,
                                ),
                            )
                        }
                    }

                    // —— 4. 错误标记：红色 ✗ 精确指出"画错的那一格" ——
                    if (isWrong) {
                        val inset = cellW * 0.24f
                        val stroke = cellW * 0.12f
                        drawLine(
                            color = WrongMark,
                            start = Offset(left + inset, top + inset),
                            end = Offset(left + cellW - inset, top + cellH - inset),
                            strokeWidth = stroke,
                        )
                        drawLine(
                            color = WrongMark,
                            start = Offset(left + cellW - inset, top + inset),
                            end = Offset(left + inset, top + cellH - inset),
                            strokeWidth = stroke,
                        )
                    }

                    // —— 5. 格子边框 ——
                    if (showClues) {
                        drawRect(
                            color = if (isWrong) WrongBorder else GridLine,
                            topLeft = Offset(left, top),
                            size = Size(cellW, cellH),
                            style = Stroke(width = if (isWrong) 2.5f else 1f),
                        )
                    }
                }
            }
        }

        // —— 外框：装裱游戏板 / 揭晓的像素画 ——
        drawRoundRect(
            color = FrameColor,
            topLeft = Offset(0f, 0f),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = if (showClues) 1.5.dp.toPx() else 2.5.dp.toPx()),
        )
    }
}

/**
 * 像素画迷你预览：把答案矩阵画成一小块像素图（用于首页卡片、结算展示）。
 * 透明的背景，只有涂黑格画出来，方便放在任意底色上。
 */
@Composable
fun PicturePreview(
    answer: Array<IntArray>,
    modifier: Modifier = Modifier,
    filledColor: Color = PictureFilled,
) {
    val rows = answer.size
    val cols = answer[0].size
    Canvas(modifier = modifier.aspectRatio(cols.toFloat() / rows.toFloat())) {
        val cellW = size.width / cols
        val cellH = size.height / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (answer[r][c] == 1) {
                    drawRect(
                        color = filledColor,
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW, cellH),
                    )
                }
            }
        }
    }
}
