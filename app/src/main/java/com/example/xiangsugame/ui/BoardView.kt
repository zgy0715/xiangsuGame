package com.example.xiangsugame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.Level
import kotlin.math.max

/**
 * 棋盘配色：柔和护眼像素风。
 * GridLine / CellBlank / CellFilled / CellMarked 对应格子边框、空白、涂黑、标记空白；
 * MarkDot 是"标记空白"格子里的小圆点；Contradiction* 是错误提示的红底/红框；
 * TextOnLight / TextOnDark 是提示数字在浅色/深色底上的文字颜色。
 */
private val GridLine = Color(0xFFCFCFCF)
private val CellBlank = Color(0xFFFBFBFB)
private val CellFilled = Color(0xFF37474F)
private val CellMarked = Color(0xFFFFFFFF)
private val MarkDot = Color(0xFF90A4AE)
private val ContradictionBg = Color(0xFFEF9A9A)
private val ContradictionBorder = Color(0xFFD32F2F)
private val TextOnLight = Color(0xFF455A64)
private val TextOnDark = Color.White
// 图片模式（showClues=false）下的"涂黑像素"：用纯黑，与解谜时的蓝灰形成"揭晓"对比
private val PictureFilled = Color(0xFF1A1A1A)

/**
 * 棋盘渲染（概要设计 3.2.2 节"棋盘渲染子模块"）。
 *
 * 用单个 Canvas 把整个棋盘画出来（不用 Compose 的 Button/Box 组合），
 * 因为棋盘是规则网格，统一绘制性能好、代码简单、像素对齐也更容易控制。
 *
 * 交互：
 *  - 单击（onTap）：把点击坐标换算成格子下标，调用 onCycle 循环切换三态（F2.2）；
 *  - 长按（onLongPress）：换算成下标后调用 onMarkEmpty 直接标记为空白。
 *
 * 显示内容（逐格绘制）：
 *  - 背景色（涂黑/空白/矛盾红底）；
 *  - 若为"标记空白"，中心画小圆点；
 *  - 若该格有提示数字，居中绘制数字（数字格自身也可能被涂黑，需保证可读）；
 *  - 格子边框；矛盾格用更粗的红框（errorHintEnabled 开启时，F2.3）。
 *
 * 图片模式（showClues = false）：
 *  - 不画提示数字与内格线，涂黑格用纯黑像素，仅画一个外边框 —— 用来"揭晓"完整像素画
 *    （参考答案预览、通关后的干净画面）。
 *
 * @param level 关卡数据（提供行数、列数、提示矩阵）
 * @param board 当前棋盘状态（由 GameBoard.board 直接传入，就地修改）
 * @param contradictionCells 确定性矛盾格集合（来自 Validator.contradictionCells）
 * @param errorHintEnabled 是否开启错误高亮
 * @param onCycle 单击回调：通知上层在 (row,col) 循环切换状态
 * @param onMarkEmpty 长按回调：通知上层把 (row,col) 标记为空白
 * @param showClues 是否绘制提示数字（false 进入"图片模式"，揭晓像素画）
 * @param interactive 是否响应点击/长按（false 时不挂手势，用于预览/结算画面）
 */
@Composable
fun BoardView(
    level: Level,
    board: Array<IntArray>,
    contradictionCells: Set<Pair<Int, Int>>,
    errorHintEnabled: Boolean,
    onCycle: (row: Int, col: Int) -> Unit,
    onMarkEmpty: (row: Int, col: Int) -> Unit,
    showClues: Boolean = true,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rows = level.rows
    val cols = level.cols
    val clueGrid = level.clueGrid
    // 文本测量器：Canvas 里没有 Compose 的 Text，需要用 measure + drawText 自己绘制
    val textMeasurer = rememberTextMeasurer()

    // 手势处理：detectTapGestures 需要坐标换算，
    // 用 pointerInput(rows, cols) 让手势块在这些值变化时重建。
    // interactive=false（参考答案预览 / 通关结算）时不挂手势，避免误触。
    val boardModifier = if (interactive) {
        modifier
            // 让画布宽高比严格等于 列数:行数，保证格子是正方形、不拉伸变形
            .aspectRatio(cols.toFloat() / rows.toFloat())
            .pointerInput(rows, cols) {
                detectTapGestures(
                    onTap = { offset ->
                        // 把像素坐标换算成格子下标：offset 除以单格宽/高并取整
                        val cellW = size.width / cols
                        val cellH = size.height / rows
                        val c = (offset.x / cellW).toInt().coerceIn(0, cols - 1)
                        val r = (offset.y / cellH).toInt().coerceIn(0, rows - 1)
                        onCycle(r, c)
                    },
                    onLongPress = { offset ->
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
        // 单格宽高：画布总尺寸除以行列数
        val cellW = size.width / cols
        val cellH = size.height / rows

        // 逐格绘制
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 该格在画布上的矩形区域
                val left = c * cellW
                val top = r * cellH
                val cellRect = Rect(left, top, left + cellW, top + cellH)

                // 当前格子状态、是否处于矛盾高亮中
                val state = CellState.fromValue(board[r][c])
                // 图片模式下不可能出现矛盾红底（答案画本身无矛盾）
                val isContradiction = showClues && errorHintEnabled && (r to c) in contradictionCells

                // —— 1. 背景 ——
                // 优先级：图片模式纯黑 > 矛盾红底 > 涂黑深灰 > 空白浅色
                val bg = when {
                    !showClues && state == CellState.FILLED -> PictureFilled
                    isContradiction -> ContradictionBg
                    state == CellState.FILLED -> CellFilled
                    else -> CellBlank
                }
                drawRect(color = bg, topLeft = Offset(left, top), size = Size(cellW, cellH))

                // —— 2. "标记空白"标记：画一个小圆点 ——
                if (state == CellState.MARKED_EMPTY) {
                    drawCircle(
                        color = MarkDot,
                        radius = cellW * 0.14f,
                        center = Offset(left + cellW / 2, top + cellH / 2),
                    )
                }

                // —— 3. 提示数字 ——
                // 注意：数字格本身也可能被玩家涂黑，所以文字颜色要随底色切换，
                // 保证涂黑后数字仍然清晰可读；图片模式（showClues=false）不画数字，
                // 让相邻同色像素合并成完整剪影
                if (showClues) {
                    val clue = clueGrid[r][c]
                    if (clue >= 0) {
                        // 字体大小取格子宽高的较大者，保证数字尽量大且不溢出
                        val fontSize = with(density) { max(cellW, cellH) * 0.42f }.toSp()
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(clue.toString()),
                            style = TextStyle(
                                color = if (state == CellState.FILLED) TextOnDark else TextOnLight,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                        )
                        // 水平垂直居中绘制
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                left + (cellW - layout.size.width) / 2,
                                top + (cellH - layout.size.height) / 2,
                            ),
                        )
                    }
                }

                // —— 4. 边框 ——
                // 矛盾格用更粗的红色边框，正常格用细灰线；
                // 图片模式不画内格线（避免切开剪影），只保留外框
                if (showClues) {
                    drawRect(
                        color = if (isContradiction) ContradictionBorder else GridLine,
                        topLeft = Offset(left, top),
                        size = Size(cellW, cellH),
                        style = Stroke(width = if (isContradiction) 2f else 1f),
                    )
                }
            }
        }

        // 图片模式：画一个外边框当作"装裱"，让整幅像素画有完成感
        if (!showClues) {
            drawRect(
                color = GridLine,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                style = Stroke(width = 1f),
            )
        }
    }
}
