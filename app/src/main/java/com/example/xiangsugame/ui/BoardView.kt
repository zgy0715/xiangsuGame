package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.example.xiangsugame.model.CellState
import com.example.xiangsugame.model.Level
import kotlin.math.max

// ============================================================
// 填色工具 —— 玩家的"笔"
// ============================================================

/** 当前选中的填色工具：涂黑笔（智能切换）/ 留白标记。 */
enum class PaintTool {
    /** ✏ 涂黑笔：点未定/留白格 → 涂黑；点已涂黑格 → 清除。一笔之内方向保持一致（起笔第一格决定）。 */
    FILL,
    /** ✕ 标记为"确定留白"。 */
    EMPTY,
}

// ============================================================
// 棋盘配色 —— 深夜画室里的"画纸"：夜幕界面上一张被照亮的浅色纸
// 纸面保持高明度（与深色界面形成聚光对比），墨色像素更纯
// ============================================================
private val GridLine = Color(0xFFE2DDF1)         // 网格线：淡薰衣草
private val CellBlank = Color(0xFFFAF9FF)        // 空白格：暖白纸面
private val CellMarkedBg = Color(0xFFE9E5F7)     // 标记空白格底色：浅薰衣草，一眼可辨
private val CellFilled = Color(0xFF37324F)       // 解谜涂黑：深墨紫
private val MarkDot = Color(0xFFA5A0C4)          // 标记空白的小圆点
private val WrongBg = Color(0xFFFFD6D6)          // 检查出的错误格：浅红底
private val WrongBorder = Color(0xFFD32F2F)      // 错误格红框
private val WrongMark = Color(0xFFE53935)        // 错误格红叉
private val SatisfiedClue = Color(0xFF2FA57F)    // 已满足提示的数字色：纸上柔绿
private val HintGlow = Color(0x46FFC94D)         // 「提示下一步」格：琥珀光晕
private val HintBorder = Color(0xFFFFB300)       // 「提示下一步」格：琥珀描边
private val TextOnDark = Color.White             // 数字在涂黑格上的颜色
private val FrameColor = Color(0xFF8F89BC)       // 棋盘外框（装裱）色：夜幕上可见的裱框
private val PictureFilled = Color(0xFF241F3D)    // 图片模式下的"墨色"像素（更纯的墨蓝）

/** 深色卡片上像素画预览的默认墨色：亮品牌紫，保证在夜表面上清晰。 */
private val PreviewInk = Color(0xFFB3A8FF)

/** 提示数字按大小分色：0 灰、小数字偏蓝、大数字偏暖，增强可读性 + 游戏感。 */
private fun clueColor(value: Int): Color = when (value) {
    0 -> Color(0xFFA89A82)
    in 1..2 -> Color(0xFF1E88E5)
    in 3..4 -> Color(0xFF00838F)
    in 5..6 -> Color(0xFF6A1B9A)
    else -> Color(0xFFD32F2F)
}

/** 缩放系数范围：最小 1x（完整显示），最大 12x（可看清单个格子/细节）。 */
private const val MinZoom = 1f
private const val MaxZoom = 12f

/**
 * 棋盘渲染 + 填色交互。
 *
 * 用单个 Canvas 把整个棋盘画出来：规则网格统一绘制性能好、代码简单。
 *  - 棋盘圆角 + 装裱外框，像一块真正的游戏板；
 *  - 空白 / 涂黑 / 标记空白三种状态底色区分明显；
 *  - 提示数字按大小分色；某提示窗口已确定且数量达标 → 数字变绿；
 *  - 显式"检查"返回的错误格 → 浅红底 + 红 ✗（只在玩家主动检查后出现）。
 *
 * 填色交互（interactive=true）——**工具笔 + 拖动连涂**：
 *  - 手指落下即按当前工具写一格；按住拖动沿轨迹连续涂（一条笔划 = 一步撤销）；
 *  - 单指用于填色；**双指**用于缩放/平移视口（缩放按钮 +/− 亦可）；
 *  - 放大后右上角出现"复位"按钮，一键回到 1x 全貌。
 *  - 填色通过 [onStrokeBegin] / [onStrokeCell] / [onStrokeEnd] 三段回调交给上层，
 *    上层用 GameBoard.startStroke / strokeCell / commitStroke 实现整笔一步撤销。
 *
 * 非交互预览（interactive=false 但 zoomable=true，如参考答案弹窗）：
 *  保留双指/单指缩放平移查看，不触发任何填色。
 */
@Composable
fun BoardView(
    level: Level,
    board: Array<IntArray>,
    satisfiedClues: Set<Pair<Int, Int>> = emptySet(),
    /** 显式"检查"返回的错误格集合：命中时该格画浅红底 + 红 ✗（平时传空集）。 */
    revealedWrong: Set<Pair<Int, Int>> = emptySet(),
    /** 「提示下一步」高亮格：命中格叠琥珀光晕 + 琥珀描边，引导玩家落笔。 */
    hintCell: Pair<Int, Int>? = null,
    /** 当前填色工具（只在 interactive=true 时生效）。 */
    tool: PaintTool = PaintTool.FILL,
    /** 开始一笔：手指落下、涂第一格之前调用（上层 startStroke）。 */
    onStrokeBegin: () -> Unit = {},
    /** 在 (r,c) 用当前工具涂一格（上层 strokeCell；可被拖动高频调用）。 */
    onStrokeCell: (row: Int, col: Int) -> Unit = { _, _ -> },
    /** 结束一笔：手指抬起后调用（上层 commitStroke）。 */
    onStrokeEnd: () -> Unit = {},
    /** 取消本次已画的部分（例如落下后改成双指缩放，避免误涂），上层 cancelStroke。 */
    onStrokeCancel: () -> Unit = {},
    showClues: Boolean = true,
    interactive: Boolean = true,
    /** 是否允许缩放/平移/缩放按钮：即使 interactive=false（纯展示/揭晓）也可放大查看细节。 */
    zoomable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rows = level.rows
    val cols = level.cols
    val clueGrid = level.clueGrid
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current

    // —— 缩放视口 ——
    var viewScale by remember(rows, cols) { mutableFloatStateOf(MinZoom) }
    var viewOffset by remember(rows, cols) { mutableStateOf(Offset.Zero) }
    var viewSize by remember(rows, cols) { mutableStateOf(IntSize.Zero) }

    val canGesture = interactive || zoomable

    Box(
        modifier = modifier
            .aspectRatio(cols.toFloat() / rows.toFloat())
            .clipToBounds()
            .onSizeChanged { viewSize = it },
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .then(
                    if (canGesture) {
                        Modifier.pointerInput(rows, cols, interactive, tool) {
                            if (interactive) {
                                // ———— 可交互：工具笔拖动连涂 + 双指缩放/平移 ————
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)

                                    // 只接受触摸；鼠标仅左键起笔（中键/右键/滚轮一律不触发涂色）
                                    if (down.type != PointerType.Touch) {
                                        if (down.type != PointerType.Mouse ||
                                            !currentEvent.buttons.isPrimaryPressed
                                        ) {
                                            return@awaitEachGesture
                                        }
                                    }

                                    // 落下 = 起笔，先涂第一格（单击也走这里）
                                    onStrokeBegin()
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                    // 屏幕坐标内插的采样步长（约半格），保证快速拖过不"断点"
                                    val stepPx = max(size.width.toFloat() / cols, size.height.toFloat() / rows) * 0.5f
                                    var lastScreen: Offset? = null

                                    fun paintTo(pos: Offset) {
                                        val from = lastScreen
                                        lastScreen = pos
                                        if (from == null) {
                                            val (r, c) = boardIndexAt(pos, viewScale, viewOffset, size, rows, cols)
                                            onStrokeCell(r, c)
                                            return
                                        }
                                        val dist = (pos - from).getDistance()
                                        val steps = (dist / stepPx).toInt().coerceIn(1, 96)
                                        for (i in 1..steps) {
                                            val t = i.toFloat() / steps
                                            val p = Offset(
                                                from.x + (pos.x - from.x) * t,
                                                from.y + (pos.y - from.y) * t,
                                            )
                                            val (r, c) = boardIndexAt(p, viewScale, viewOffset, size, rows, cols)
                                            onStrokeCell(r, c)
                                        }
                                    }

                                    // 先涂下落的这一格
                                    paintTo(down.position)

                                    var transforming = false
                                    var centroid = Offset.Zero
                                    var span = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()

                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (pressed.size >= 2) {
                                            if (!transforming) {
                                                // 第二根手指落下 = 玩家其实是来缩放/平移的，
                                                // 取消刚才落下误涂的那几格
                                                onStrokeCancel()
                                            }
                                            transforming = true
                                            lastScreen = null // 双指期间不画
                                            val a = pressed[0].position
                                            val b = pressed[1].position
                                            val newCentroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                            val newSpan = (a - b).getDistance().coerceAtLeast(1f)
                                            if (span > 0f) {
                                                val curScale = viewScale
                                                val newScale =
                                                    (curScale * newSpan / span).coerceIn(MinZoom, MaxZoom)
                                                // 视口先跟手平移，再围绕双指中心缩放并锚住其下的棋盘点
                                                var off = viewOffset + (newCentroid - centroid)
                                                val anchor = (newCentroid - off) / curScale
                                                viewOffset = clampViewOffset(
                                                    offset = newCentroid - anchor * newScale,
                                                    scale = newScale,
                                                    viewSize = viewSize,
                                                )
                                                viewScale = newScale
                                            }
                                            centroid = newCentroid
                                            span = newSpan
                                            pressed.forEach { it.consume() }
                                        } else if (transforming) {
                                            // 缩放/平移后只剩一指：等待抬起，不再误画
                                            pressed.forEach { it.consume() }
                                        } else {
                                            val ch = pressed[0]
                                            // 鼠标拖动仅响应左键按住（中键/右键按住不涂）
                                            if (ch.type == PointerType.Mouse &&
                                                !event.buttons.isPrimaryPressed
                                            ) {
                                                ch.consume()
                                            } else {
                                                paintTo(ch.position)
                                                ch.consume()
                                            }
                                        }
                                    }
                                    onStrokeEnd()
                                }
                            } else {
                                // ———— 纯展示：双指缩放 / 单指拖动平移（不填色、不响应滚轮）————
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var lastPos = down.position
                                    var transforming = false
                                    var centroid = Offset.Zero
                                    var span = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()

                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (pressed.size >= 2) {
                                            transforming = true
                                            val a = pressed[0].position
                                            val b = pressed[1].position
                                            val newCentroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                            val newSpan = (a - b).getDistance().coerceAtLeast(1f)
                                            if (span > 0f) {
                                                val curScale = viewScale
                                                val newScale = (curScale * newSpan / span).coerceIn(MinZoom, MaxZoom)
                                                var off = viewOffset + (newCentroid - centroid)
                                                val anchor = (newCentroid - off) / curScale
                                                viewOffset = clampViewOffset(
                                                    offset = newCentroid - anchor * newScale,
                                                    scale = newScale,
                                                    viewSize = viewSize,
                                                )
                                                viewScale = newScale
                                            }
                                            centroid = newCentroid
                                            span = newSpan
                                            pressed.forEach { it.consume() }
                                        } else if (transforming) {
                                            pressed.forEach { it.consume() }
                                        } else {
                                            val ch = pressed[0]
                                            val pan = ch.position - lastPos
                                            viewOffset = clampViewOffset(viewOffset + pan, viewScale, viewSize)
                                            lastPos = ch.position
                                            ch.consume()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            val cellW = size.width / cols
            val cellH = size.height / rows
            val corner = 14.dp.toPx()

            // 先裁出圆角区域（视口 1:1），再在内部套用缩放+平移，形成圆角"窗口"看进棋盘
            clipPath(Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f, top = 0f, right = size.width, bottom = size.height,
                        radiusX = corner, radiusY = corner,
                    )
                )
            }) {
                // screen = board*scale + offset；translate 后 scale 即等价于该变换
                withTransform({
                    translate(viewOffset.x, viewOffset.y)
                    scale(viewScale, viewScale, pivot = Offset.Zero)
                }) {
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            val left = c * cellW
                            val top = r * cellH

                            val state = CellState.fromValue(board[r][c])
                            // 只在"检查"后生效的错误标红（平时 revealedWrong 为空集）
                            val isWrong = showClues && (r to c) in revealedWrong
                            val isHint = showClues && hintCell?.first == r && hintCell?.second == c
                            val isSatisfiedClue = showClues && (r to c) in satisfiedClues

                            // —— 1. 背景 —
                            // 优先级：图片模式墨黑 > 错误红底 > 涂黑深墨 > 标记空白浅沙 > 空白米白
                            val bg = when {
                                !showClues && state == CellState.FILLED -> PictureFilled
                                isWrong -> WrongBg
                                state == CellState.FILLED -> CellFilled
                                state == CellState.MARKED_EMPTY -> CellMarkedBg
                                else -> CellBlank
                            }
                            drawRect(color = bg, topLeft = Offset(left, top), size = Size(cellW, cellH))

                            // 提示格:叠一层琥珀光晕,让"该在这里落笔"一眼可见
                            if (isHint) {
                                drawRect(color = HintGlow, topLeft = Offset(left, top), size = Size(cellW, cellH))
                            }

                            // —— 2. 提示数字 + 状态点缀（错误格已被红 ✗ 盖住，不再重复画）——
                            if (!isWrong) {
                                // "标记空白"的小圆点
                                if (state == CellState.MARKED_EMPTY && showClues) {
                                    drawCircle(
                                        color = MarkDot,
                                        radius = cellW * 0.15f,
                                        center = Offset(left + cellW / 2, top + cellH / 2),
                                    )
                                }
                                // 提示数字（图片模式不画，让墨色像素合并成完整剪影；已满足变绿）
                                if (showClues && clueGrid[r][c] >= 0) {
                                    val clue = clueGrid[r][c]
                                    val fontSize = with(density) { max(cellW, cellH) * 0.46f }.toSp()
                                    val layout = textMeasurer.measure(
                                        text = AnnotatedString(clue.toString()),
                                        style = TextStyle(
                                            color = when {
                                                isSatisfiedClue && state == CellState.FILLED -> Color(0xFF9BE8C0)
                                                isSatisfiedClue -> SatisfiedClue
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

                            // —— 3. 错误格红 ✗：精确指出"检查"发现画错的那一格 ——
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

                            // —— 4. 格子边框（除以 viewScale：放大后仍是屏幕上约 1px 的细线）——
                            if (showClues) {
                                val borderColor = when {
                                    isWrong -> WrongBorder
                                    isHint -> HintBorder
                                    else -> GridLine
                                }
                                val borderWidth = when {
                                    isWrong || isHint -> 2.5f
                                    else -> 1f
                                }
                                drawRect(
                                    color = borderColor,
                                    topLeft = Offset(left, top),
                                    size = Size(cellW, cellH),
                                    style = Stroke(width = borderWidth / viewScale),
                                )
                            }
                        }
                    }

                    // —— 外框：装裱的像素画边框 —— 放进缩放变换里，**随棋盘一起放大/平移**
                    val baseFrame = if (showClues) 1.5.dp.toPx() else 2.5.dp.toPx()
                    drawRoundRect(
                        color = FrameColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(corner / viewScale, corner / viewScale),
                        style = Stroke(width = baseFrame / viewScale),
                    )
                }
            }
        }

        // —— 复位按钮：放大后出现在右上角，一键回到 1x 全貌（深色玻璃小片）——
        if (canGesture && viewScale > MinZoom + 0.01f) {
            Surface(
                onClick = {
                    viewScale = MinZoom
                    viewOffset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = CircleShape,
                color = Color(0xD9262338),
                contentColor = Color(0xFFF0EFFB),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                shadowElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("复位", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // —— 缩放按钮（右下角）：＋/− 围绕视口中心放大/缩小，双指缩放之外更直观的入口。
        // 只在格子较多的大棋盘显示（小棋盘本就一览无余，按钮反而是噪音）。
        if (canGesture && rows * cols >= 200) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZoomButton(
                    text = "−",
                    enabled = viewScale > MinZoom + 0.01f,
                ) {
                    zoomAround(viewScale, viewOffset, viewSize, 1 / 1.5f,
                        { viewScale = it }, { viewOffset = it })
                }
                ZoomButton(
                    text = "+",
                    enabled = viewScale < MaxZoom - 0.01f,
                ) {
                    zoomAround(viewScale, viewOffset, viewSize, 1.5f,
                        { viewScale = it }, { viewOffset = it })
                }
            }
        }
    }
}

/**
 * 围绕视口中心放大/缩小：把中心点下的棋盘点钉住，缩放后平移使其仍居中。
 */
private fun zoomAround(
    viewScale: Float,
    viewOffset: Offset,
    viewSize: IntSize,
    factor: Float,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
) {
    val newScale = (viewScale * factor).coerceIn(MinZoom, MaxZoom)
    if (viewSize.width == 0 || viewSize.height == 0) {
        onScale(newScale)
        return
    }
    val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
    val anchor = if (viewScale > 0f) (center - viewOffset) / viewScale else Offset.Zero
    val newOffset = clampViewOffset(
        offset = center - anchor * newScale,
        scale = newScale,
        viewSize = viewSize,
    )
    onScale(newScale)
    onOffset(newOffset)
}

/** 圆形缩放按钮：深色玻璃圆片 + 亮符号，禁用时半透明。 */
@Composable
private fun ZoomButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = Color(0xD9262338),
        contentColor = if (enabled) Color(0xFFF0EFFB) else Color(0x80F0EFFB),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 把屏幕触摸坐标换算成棋盘格子下标：先平移再缩放（screen = board*scale + offset 的逆变换）。
 */
private fun boardIndexAt(
    pos: Offset,
    scale: Float,
    offset: Offset,
    canvas: IntSize,
    rows: Int,
    cols: Int,
): Pair<Int, Int> {
    val cellW = canvas.width.toFloat() / cols
    val cellH = canvas.height.toFloat() / rows
    val lx = (pos.x - offset.x) / scale
    val ly = (pos.y - offset.y) / scale
    val c = (lx / cellW).toInt().coerceIn(0, cols - 1)
    val r = (ly / cellH).toInt().coerceIn(0, rows - 1)
    return r to c
}

/**
 * 限制平移偏移，保证棋盘不会完全滑出视口：
 *  - 缩放 ≥ 1x 时棋盘不小于视口，偏移限定在 [view - 放大后的尺寸, 0]，让棋盘始终覆盖视口；
 *  - 未放大（等于视口尺寸）时偏移恒为 0（整盘居中）。
 */
private fun clampViewOffset(offset: Offset, scale: Float, viewSize: IntSize): Offset {
    val w = viewSize.width * scale
    val h = viewSize.height * scale
    val tx = if (w <= viewSize.width) 0f else offset.x.coerceIn(viewSize.width - w, 0f)
    val ty = if (h <= viewSize.height) 0f else offset.y.coerceIn(viewSize.height - h, 0f)
    return Offset(tx, ty)
}

/**
 * 像素画迷你预览：把答案矩阵画成一小块像素图（用于首页卡片、结算展示）。
 * 透明的背景，只有涂黑格画出来，方便放在任意底色上。
 * 默认墨色为亮品牌紫 —— 现在的卡片都是深色夜表面，亮色才清晰。
 */
@Composable
fun PicturePreview(
    answer: Array<IntArray>,
    modifier: Modifier = Modifier,
    filledColor: Color = PreviewInk,
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
