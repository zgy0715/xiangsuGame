package com.example.xiangsugame.model

/**
 * 内置关卡：10 个常规关 + 1 个隐藏关，按尺寸与扫雷难度递进构成关卡进度：
 *  - 第 1~3 关：简单，10×10 / 10×10 / 12×12（红心 / 笑脸 / 蝴蝶）；
 *  - 第 4~6 关：中等，14×14 / 16×16 / 16×16（苹果 / 小房子 / 小鱼）；
 *  - 第 7~10 关：困难，20×20 / 20×20 / 24×24 / 28×28（星星 / 皇冠 / 月亮 / 火箭）；
 *  - 第 11 关：隐藏关（isHidden），64×64 超大尺寸（城堡），
 *    玩家需通关全部 10 个常规关才解锁，管理员可直接开启。
 *
 * 每个图案都经过逐格校验，保证清晰、居中、一眼可辨：
 * 小图（红心 / 笑脸 / 蝴蝶 / 苹果 / 小房子 / 小鱼）用 [art] 的 ASCII 小图直接画出来，
 * 每个字符串一行，`X` 表示涂黑、`.` 表示空白，直观、好改、可读性极强；
 * 大尺寸关卡用绘图工具函数（fillRect / fillTriangle / fillEllipse /
 * carveRect / carveDiamond 等）程序化生成。
 *
 * 提示数字由 [Level.fromAnswer] 自动推导（见 [PuzzleGenerator]）。
 * 本版使用**稀疏提示**：只在一部分格子放数字，位置由"贪心去提示 + 唯一解校验"
 * 管线生成（见 PuzzleGenerator 注释），保证每个关卡仍然只有唯一解 ——
 * 谜面唯一解由 CoreLogicTest 校验。
 * 关卡 id 即"第几关"，顺序解锁由 GameProgress 管理；隐藏关依赖线性解锁规则，
 * 玩家必须先把 1~10 关全部通关。
 */
object Levels {

    /** 全部关卡，按第 1 关到第 11 关的顺序展示。 */
    val all: List<Level> = listOf(
        heart(), smiley(), butterfly(), apple(), house(), fish(),
        star(), crown(), moon(), rocket(), castle(),
    )

    /** 红心：10×10，第 1 关（简单）。两瓣圆润 + 中缝凹口 + 收尖底部。 */
    private fun heart() = Level.fromAnswer(
        id = 1,
        name = "红心",
        difficulty = Difficulty.EASY,
        answer = art(
            ".XX....XX.",
            "XXXX..XXXX",
            "XXXX..XXXX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            ".XXXXXXXX.",
            "..XXXXXX..",
            "...XXXX...",
            "....XX....",
        ),
        cluePositions = SparseClues.heart,
        timedLimitSeconds = 180,
    )

    /** 笑脸：10×10，第 2 关（简单）。圆脸 + 两只方眼睛 + 上翘微笑。 */
    private fun smiley() = Level.fromAnswer(
        id = 2,
        name = "笑脸",
        difficulty = Difficulty.EASY,
        answer = art(
            ".XXXXXXXX.",
            "XXXXXXXXXX",
            "XX..XX..XX",
            "XX..XX..XX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            "XXXX..XXXX",
            "XXX....XXX",
            "XX......XX",
            ".XXXXXXXX.",
        ),
        cluePositions = SparseClues.smiley,
        timedLimitSeconds = 180,
    )

    /** 蝴蝶：12×12，第 3 关（简单）。圆润上下四翅 + 细身体 + 触角。 */
    private fun butterfly() = Level.fromAnswer(
        id = 3,
        name = "蝴蝶",
        difficulty = Difficulty.EASY,
        answer = art(
            "....X..X....",
            ".....XX.....",
            ".....XX.....",
            "XXX..XX..XXX",
            "XXXX.XX.XXXX",
            "XXXX.XX.XXXX",
            "XXX..XX..XXX",
            ".XX..XX..XX.",
            "..XX.XX.XX..",
            "...X.XX.X...",
            "...X.XX.X...",
            ".....XX.....",
        ),
        cluePositions = SparseClues.butterfly,
        timedLimitSeconds = 240,
    )

    /** 苹果：14×14，第 4 关（中等）。圆润果身 + 顶部凹槽 + 果柄。 */
    private fun apple() = Level.fromAnswer(
        id = 4,
        name = "苹果",
        difficulty = Difficulty.MEDIUM,
        answer = art(
            "..............",
            "......XX......",
            ".....XXXX.....",
            ".XXXXX..XXXXX.",
            ".XXXXX..XXXXX.",
            "XXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXX",
            ".XXXXXXXXXXXX.",
            ".XXXXXXXXXXXX.",
            "..XXXXXXXXXX..",
            "..XXXXXXXXXX..",
            "...XXXXXXXX...",
            "....XXXXXX....",
            ".....XXXX.....",
        ),
        cluePositions = SparseClues.apple,
        timedLimitSeconds = 300,
    )

    /** 小房子：16×16，第 5 关（中等）。对称三角屋顶 + 两扇窗 + 一扇门。 */
    private fun house() = Level.fromAnswer(
        id = 5,
        name = "小房子",
        difficulty = Difficulty.MEDIUM,
        answer = art(
            ".......XX.......",
            "......XXXX......",
            ".....XXXXXX.....",
            "....XXXXXXXX....",
            "...XXXXXXXXXX...",
            "..XXXXXXXXXXXX..",
            ".XXXXXXXXXXXXXX.",
            "XXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXX",
            "XXX...XXXX...XXX",
            "XXX...XXXX...XXX",
            "XXXXXXX..XXXXXXX",
            "XXXXXXX..XXXXXXX",
            "XXXXXXX..XXXXXXX",
            "XXXXXXX..XXXXXXX",
            "XXXXXXX..XXXXXXX",
        ),
        cluePositions = SparseClues.house,
        timedLimitSeconds = 360,
    )

    /** 小鱼：16×16，第 6 关（中等）。扇尾 + 椭圆鱼身 + 单眼，朝右游。 */
    private fun fish() = Level.fromAnswer(
        id = 6,
        name = "小鱼",
        difficulty = Difficulty.MEDIUM,
        answer = art(
            "................",
            "................",
            "................",
            ".....XXXXXXXX...",
            "..X..XXXXXXXXX..",
            ".XXX.XXXXXXXXXXX",
            "XXXX.XXXXXXXXXXX",
            "XXXXXXXXXXXX.XXX",
            "XXXXXXXXXXXX.XXX",
            "XXXX.XXXXXXXXXXX",
            ".XXX.XXXXXXXXXXX",
            "..X..XXXXXXXXX..",
            ".....XXXXXXXX...",
            "................",
            "................",
            "................",
        ),
        cluePositions = SparseClues.fish,
        timedLimitSeconds = 360,
    )

    /** 星星：20×20，第 7 关（困难）。实心五角星，五只角清晰可辨。 */
    private fun star() = Level.fromAnswer(
        id = 7,
        name = "星星",
        difficulty = Difficulty.HARD,
        answer = star20(),
        cluePositions = SparseClues.star,
        timedLimitSeconds = 480,
    )

    /** 皇冠：20×20，第 8 关（困难）。宽底座 + 五级尖峰 + 三颗宝石留白（手写，完全对称）。 */
    private fun crown() = Level.fromAnswer(
        id = 8,
        name = "皇冠",
        difficulty = Difficulty.HARD,
        answer = crown20(),
        cluePositions = SparseClues.crown,
        timedLimitSeconds = 480,
    )

    /** 月亮：24×24，第 9 关（困难）。满月 + 六处陨坑（比月牙更干净可辨）。 */
    private fun moon() = Level.fromAnswer(
        id = 9,
        name = "月亮",
        difficulty = Difficulty.HARD,
        answer = moon24(),
        cluePositions = SparseClues.moon,
        timedLimitSeconds = 600,
    )

    /** 火箭：28×28，第 10 关（困难）。锥头 + 机身 + 舷窗 + 尾翼 + 尾焰。 */
    private fun rocket() = Level.fromAnswer(
        id = 10,
        name = "火箭",
        difficulty = Difficulty.HARD,
        answer = rocket28(),
        cluePositions = SparseClues.rocket,
        timedLimitSeconds = 720,
    )

    /** 城堡：64×64，第 11 关（隐藏·困难）。城墙城垛 + 主堡尖顶 + 双塔锥顶 + 拱门窗户。 */
    private fun castle() = Level.fromAnswer(
        id = 11,
        name = "城堡",
        difficulty = Difficulty.HARD,
        answer = castle64(),
        cluePositions = SparseClues.castle,
        isHidden = true,
        timedLimitSeconds = 1800,
    )

    // ———— ASCII 像素画：X 涂黑 / . 空白 ————

    /** 把多行 ASCII 小图转成答案像素矩阵（每行必须等长）。 */
    private fun art(vararg rows: String): Array<IntArray> =
        rows.map { row -> IntArray(row.length) { c -> if (row[c] == 'X') 1 else 0 } }.toTypedArray()

    // ———— 大尺寸关卡答案的"程序化作画" ————

    /**
     * 星星 20×20：实心五角星。
     * 直接填充五角星多边形（外角 + 内角交替的 10 顶点），
     * 内角取外径的 0.42 —— 比"星环"更粗、更实，五只角依然清晰可辨。
     */
    private fun star20(): Array<IntArray> {
        val g = Array(20) { IntArray(20) }
        val cx = 9.5
        // 五角星竖直方向不对称（顶点在上、两只下角在下），cy 略下移让整体在棋盘内视觉居中
        val cy = 10.4
        val outer = 9.5
        val inner = outer * 0.42
        val verts = starVertices(cx, cy, outer, inner)
        for (r in 0 until 20) {
            for (c in 0 until 20) {
                if (pointInPolygon(c + 0.5, r + 0.5, verts)) g[r][c] = 1
            }
        }
        return g
    }

    /**
     * 皇冠 20×20：宽底座 + 五个尖峰（中间最高、峰间留凹口）+ 三颗宝石留白。
     * 手写 ASCII 并逐行校验左右对称（中心列 9.5），相比程序化三角更规整。
     */
    private fun crown20(): Array<IntArray> = art(
        "....................",
        "....................",
        "....................",
        ".........XX.........",
        ".........XX.........",
        "......X.XXXX.X......",
        "......X.XXXX.X......",
        "....X.X.XXXX.X.X....",
        "....XXXXXXXXXXXX....",
        "....XXXXXXXXXXXX....",
        "...XXXXXXXXXXXXXX...",
        "...XXXXXXXXXXXXXX...",
        "...XXXXXXXXXXXXXX...",
        "...XXXXXXXXXXXXXX...",
        "..XXXXXXXXXXXXXXXX..",
        "..XXXXXXXXXXXXXXXX..",
        "...XXXXXXXXXXXXXX...",
        "...XXX..X..X..XXX...",
        "...XXX..X..X..XXX...",
        "...XXXXXXXXXXXXXX...",
    )

    /** 月亮 24×24：满月 = 大圆 + 六处菱形陨坑。圆用严格不等式填充，边缘无毛刺。 */
    private fun moon24(): Array<IntArray> {
        val g = Array(24) { IntArray(24) }
        fillEllipse(g, 12, 12, 10, 10)   // 满月圆面
        for ((r, c) in listOf(6 to 6, 8 to 12, 10 to 17, 14 to 8, 16 to 15, 18 to 11)) {
            carveDiamond(g, r, c)        // 六处陨坑
        }
        return g
    }

    /** 火箭 28×28：锥头三角 + 圆柱机身 + 方形舷窗 + 左右尾翼 + 下方尾焰。 */
    private fun rocket28(): Array<IntArray> {
        val g = Array(28) { IntArray(28) }
        fillRect(g, 9, 9, 20, 18)             // 机身
        fillTriangle(g, 1, 13, 9, 9, 18)      // 锥头
        carveRect(g, 12, 12, 15, 15)          // 舷窗
        fillTriangle(g, 19, 9, 24, 4, 10)     // 左尾翼
        fillTriangle(g, 19, 18, 24, 17, 23)   // 右尾翼
        fillTriangle(g, 21, 13, 27, 11, 16)   // 尾焰
        return g
    }

    /**
     * 城堡 64×64（隐藏关压轴）：城墙 + 墙顶城垛 + 中央主堡（尖顶 + 旗帜）
     * + 左右双塔（锥顶 + 旗帜）+ 拱门 + 窗户。全程序化拼合。
     */
    private fun castle64(): Array<IntArray> {
        val g = Array(64) { IntArray(64) }
        // 主城墙：行 44..56，列 6..57
        fillRect(g, 44, 6, 56, 57)
        // 墙顶城垛（雉堞）：5 宽实、2 宽空交替
        for (c in 6..57 step 7) {
            fillRect(g, 40, c, 43, minOf(c + 4, 57))
        }
        // 中央主堡 + 尖顶 + 旗
        fillRect(g, 28, 27, 56, 36)
        fillTriangle(g, 20, 31, 28, 25, 38)
        fillRect(g, 16, 31, 19, 31)   // 旗杆
        fillRect(g, 16, 32, 16, 34)   // 旗帜
        // 左塔 + 锥顶 + 旗
        fillRect(g, 28, 7, 56, 15)
        fillTriangle(g, 18, 11, 28, 5, 17)
        fillRect(g, 14, 11, 17, 11)
        fillRect(g, 14, 12, 14, 14)
        // 右塔 + 锥顶 + 旗
        fillRect(g, 28, 48, 56, 56)
        fillTriangle(g, 18, 52, 28, 46, 58)
        fillRect(g, 14, 52, 17, 52)
        fillRect(g, 14, 49, 14, 51)
        // 拱门（下部矩形 + 上部窄矩形拼出尖拱）
        carveRect(g, 48, 28, 56, 35)
        carveRect(g, 44, 30, 47, 33)
        // 窗户：城墙左右、主堡、双塔各一
        carveRect(g, 46, 12, 48, 14)
        carveRect(g, 46, 19, 48, 21)
        carveRect(g, 46, 42, 48, 44)
        carveRect(g, 46, 49, 48, 51)
        carveRect(g, 34, 29, 37, 32)
        carveRect(g, 38, 10, 40, 12)
        carveRect(g, 38, 51, 40, 53)
        return g
    }

    // ———— 绘图工具 —— 在答案像素矩阵上画基本图形 ————

    /**
     * 实心椭圆：中心 (cx,cy)，水平/垂直半轴 rx/ry。
     * 用严格不等式（<1.0）判定，保证边界处只有真正落在椭圆内的格子被填充，
     * 不会在正上/正下方冒出 1 像素毛刺。
     */
    private fun fillEllipse(grid: Array<IntArray>, cx: Int, cy: Int, rx: Int, ry: Int) {
        for (r in 0 until grid.size) {
            for (c in 0 until grid[0].size) {
                val dx = (c - cx).toDouble() / rx
                val dy = (r - cy).toDouble() / ry
                if (dx * dx + dy * dy < 1.0) grid[r][c] = 1
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

    /** 挖空小菱形：中心 + 上下左右共 5 格清为空白（做陨坑、圆点等小细节）。 */
    private fun carveDiamond(grid: Array<IntArray>, r: Int, c: Int) {
        val offsets = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in offsets) {
            val nr = r + dr
            val nc = c + dc
            if (nr in 0 until grid.size && nc in 0 until grid[0].size) grid[nr][nc] = 0
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

    /** 生成五角星的 10 个顶点（外角、内角交替），供 pointInPolygon 使用。 */
    private fun starVertices(
        cx: Double,
        cy: Double,
        outer: Double,
        inner: Double,
    ): List<Pair<Double, Double>> {
        val vertices = mutableListOf<Pair<Double, Double>>()
        for (k in 0 until 5) {
            val outerAng = Math.toRadians(-90.0 + k * 72.0)
            vertices.add((cx + outer * Math.cos(outerAng)) to (cy + outer * Math.sin(outerAng)))
            val innerAng = Math.toRadians(-90.0 + 36.0 + k * 72.0)
            vertices.add((cx + inner * Math.cos(innerAng)) to (cy + inner * Math.sin(innerAng)))
        }
        return vertices
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
