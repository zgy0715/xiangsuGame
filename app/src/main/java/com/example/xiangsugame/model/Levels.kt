package com.example.xiangsugame.model

import kotlin.math.abs

/**
 * 内置关卡：六关，按难度对应棋盘尺寸，顺序构成关卡进度：
 *  - 第 1、2 关：简单，10×10（红心 / 笑脸）；
 *  - 第 3、4 关：中等，20×20（小房子 / 小鱼）；
 *  - 第 5、6 关：困难，35×35（星星 / 皇冠）。
 *
 * 每个关卡只定义"答案像素矩阵"（answer），提示数字由 Level.fromAnswer
 * 自动推导生成（见 [PuzzleGenerator]），设计者无需手写提示。
 *
 * 大尺寸关卡（20×20 / 35×35）手写 intArray 太啰嗦，改为用绘图工具函数
 * （fillEllipse / fillRect / fillTriangle / carveRect 等）在代码里"画"出来，
 * 直观且易维护。像素画的画法：answer 元素 1 表示该格涂黑、0 表示空白。
 *
 * 每个关卡的谜面**唯一解**由 CoreLogicTest 校验 —— 如果某个关卡答案不唯一，
 * 意味着提示信息不足，玩家会推不出来，所以生成后必须过一遍求解器验证。
 * 关卡 id 即"第几关"，顺序解锁由 GameProgress 管理。
 */
object Levels {

    /** 全部关卡，按第 1 关到第 6 关的顺序展示。 */
    val all: List<Level> = listOf(heart(), smiley(), house(), fish(), star(), crown())

    /** 红心：10×10，第 1 关（简单）。顶部两行尖角、中部饱满、底部收尖。 */
    private fun heart() = Level.fromAnswer(
        id = 1,
        name = "红心",
        difficulty = Difficulty.EASY,
        answer = arrayOf(
            intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 0),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
            intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 0),
            intArrayOf(0, 0, 0, 1, 1, 1, 1, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 1, 1, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        ),
    )

    /** 笑脸：10×10，第 2 关（简单）。密实圆脸 + 两只眼睛留白 + 一排嘴留白。 */
    private fun smiley() = Level.fromAnswer(
        id = 2,
        name = "笑脸",
        difficulty = Difficulty.EASY,
        answer = arrayOf(
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 0, 1, 1, 1, 0, 1, 1, 1),
            intArrayOf(1, 1, 0, 1, 1, 1, 0, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 0, 0, 0, 0, 0, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
        ),
    )

    /** 小房子：20×20，第 3 关（中等）。三角屋顶 + 实心墙体 + 两窗一门。 */
    private fun house() = Level.fromAnswer(
        id = 3,
        name = "小房子",
        difficulty = Difficulty.MEDIUM,
        answer = house20(),
    )

    /** 小鱼：20×20，第 4 关（中等）。椭圆鱼身 + 左侧三角尾 + 单眼留白。 */
    private fun fish() = Level.fromAnswer(
        id = 4,
        name = "小鱼",
        difficulty = Difficulty.MEDIUM,
        answer = fish20(),
    )

    /** 星星：35×35，第 5 关（困难）。实心五角星。 */
    private fun star() = Level.fromAnswer(
        id = 5,
        name = "星星",
        difficulty = Difficulty.HARD,
        answer = star35(),
    )

    /** 皇冠：35×35，第 6 关（困难）。宽底座 + 五个三角尖峰 + 三颗宝石留白。 */
    private fun crown() = Level.fromAnswer(
        id = 6,
        name = "皇冠",
        difficulty = Difficulty.HARD,
        answer = crown35(),
    )

    // ———— 大尺寸关卡答案的"程序化作画" ————

    /** 小房子 20×20：三角屋顶 + 实心墙体 + 两扇窗 + 一扇门。 */
    private fun house20(): Array<IntArray> {
        val g = Array(20) { IntArray(20) }
        // 屋顶：三角形，顶点在第 0 行中部，底边到第 7 行（2..17 列）
        fillTriangle(g, ar = 0, ac = 9, br = 7, bc0 = 2, bc1 = 17)
        // 墙体：8..19 行全宽实心
        fillRect(g, 8, 0, 19, 19)
        // 两扇窗户：3×3 留空，左右对称
        carveRect(g, 10, 4, 12, 6)
        carveRect(g, 10, 13, 12, 15)
        // 门：4×7 留空，一直通到地面
        carveRect(g, 13, 8, 19, 11)
        return g
    }

    /** 小鱼 20×20：椭圆鱼身（朝右）+ 左侧叶片形尾巴 + 前部单眼留白。 */
    private fun fish20(): Array<IntArray> {
        val g = Array(20) { IntArray(20) }
        // 鱼身（朝右）：实心椭圆，中心 (11,9)，半轴 7×4 → 行 5..13、列 4..18
        fillEllipse(g, cx = 11, cy = 9, rx = 7, ry = 4)
        // 尾巴：向左展开的叶片形，顶点在 (9,0)，向右接到鱼身（列 4）
        for (r in 5..13) {
            val t = 1.0 - abs(r - 9) / 4.0 // r=9 → 1，r=5/13 → 0
            val maxC = (4 - 4.0 * t).toInt() // 左缘列 0..4，越靠中间越窄
            for (c in 0..maxC) g[r][c] = 1
        }
        // 眼睛：鱼身前部 2×2 留白
        carveRect(g, 8, 14, 9, 15)
        return g
    }

    /** 星星 35×35：实心五角星，按顶点多边形用射线法逐格填充。 */
    private fun star35(): Array<IntArray> {
        val g = Array(35) { IntArray(35) }
        val cx = 17.0
        val cy = 17.0
        val outer = 16.0
        val inner = outer * 0.36
        // 五角星的 10 个顶点：外角、内角交替出现
        val vertices = mutableListOf<Pair<Double, Double>>()
        for (k in 0 until 5) {
            val outerAng = Math.toRadians(-90.0 + k * 72.0)
            vertices.add((cx + outer * Math.cos(outerAng)) to (cy + outer * Math.sin(outerAng)))
            val innerAng = Math.toRadians(-90.0 + 36.0 + k * 72.0)
            vertices.add((cx + inner * Math.cos(innerAng)) to (cy + inner * Math.sin(innerAng)))
        }
        // 用格子中心点判定，边缘更干净
        for (r in 0 until 35) {
            for (c in 0 until 35) {
                if (pointInPolygon(c + 0.5, r + 0.5, vertices)) g[r][c] = 1
            }
        }
        return g
    }

    /** 皇冠 35×35：宽底座 + 五个三角尖峰（中间最高、峰间留凹口）+ 三颗宝石留白。 */
    private fun crown35(): Array<IntArray> {
        val g = Array(35) { IntArray(35) }
        // 底座：3..31 列，24..31 行
        fillRect(g, 24, 3, 31, 31)
        // 五个尖峰：主峰最高，向两侧递减；底座半宽 ±2，峰间自然留出凹口
        fillTriangle(g, ar = 5, ac = 17, br = 24, bc0 = 15, bc1 = 19)   // 主峰
        fillTriangle(g, ar = 10, ac = 12, br = 24, bc0 = 10, bc1 = 14)
        fillTriangle(g, ar = 10, ac = 22, br = 24, bc0 = 20, bc1 = 24)
        fillTriangle(g, ar = 15, ac = 7, br = 24, bc0 = 5, bc1 = 9)
        fillTriangle(g, ar = 15, ac = 27, br = 24, bc0 = 25, bc1 = 29)
        // 三颗宝石：底座上的小方块留白
        carveRect(g, 26, 10, 27, 12)
        carveRect(g, 26, 16, 27, 18)
        carveRect(g, 26, 22, 27, 24)
        return g
    }

    // ———— 绘图工具 —— 在答案像素矩阵上画基本图形 ————

    /** 实心椭圆：中心 (cx,cy)，水平/垂直半轴 rx/ry。 */
    private fun fillEllipse(grid: Array<IntArray>, cx: Int, cy: Int, rx: Int, ry: Int) {
        for (r in 0 until grid.size) {
            for (c in 0 until grid[0].size) {
                val dx = (c - cx).toDouble() / rx
                val dy = (r - cy).toDouble() / ry
                if (dx * dx + dy * dy <= 1.0) grid[r][c] = 1
            }
        }
    }

    /** 实心矩形：行 [r0,r1]、列 [c0,c1]（自动裁剪越界部分）。 */
    private fun fillRect(grid: Array<IntArray>, r0: Int, c0: Int, r1: Int, c1: Int) {
        for (r in r0..r1) {
            for (c in c0..c1) {
                if (r in 0 until grid.size && c in 0 until grid[0].size) grid[r][c] = 1
            }
        }
    }

    /** 挖空矩形：把行 [r0,r1]、列 [c0,c1] 清为空白（自动裁剪越界部分）。 */
    private fun carveRect(grid: Array<IntArray>, r0: Int, c0: Int, r1: Int, c1: Int) {
        for (r in r0..r1) {
            for (c in c0..c1) {
                if (r in 0 until grid.size && c in 0 until grid[0].size) grid[r][c] = 0
            }
        }
    }

    /** 实心三角形：顶点 (ar,ac) 在上，底边在第 br 行、列 [bc0,bc1]。 */
    private fun fillTriangle(grid: Array<IntArray>, ar: Int, ac: Int, br: Int, bc0: Int, bc1: Int) {
        for (r in ar..br) {
            val t = if (br > ar) (r - ar).toDouble() / (br - ar) else 1.0
            val left = (ac + (bc0 - ac) * t).toInt()
            val right = (ac + (bc1 - ac) * t).toInt()
            for (c in left..right) {
                if (r in 0 until grid.size && c in 0 until grid[0].size) grid[r][c] = 1
            }
        }
    }

    /** 射线法点-in-多边形判定（even-odd），用于画实心多边形（如五角星）。 */
    private fun pointInPolygon(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
