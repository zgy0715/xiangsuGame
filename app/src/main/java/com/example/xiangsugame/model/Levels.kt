package com.example.xiangsugame.model

/**
 * 内置关卡：10 个常规关，按尺寸与扫雷难度递进构成关卡进度：
 *  - 第 1~3 关：简单，10×10 / 10×10 / 12×12（红心 / 笑脸 / 蝴蝶）；
 *  - 第 4~6 关：中等，14×14 / 16×16 / 16×16（苹果 / 小房子 / 小鱼）；
 *  - 第 7~10 关：困难，20×20 / 20×20 / 24×24 / 28×28（星星 / 皇冠 / 月亮 / 火箭）。
 *
 * 每个图案都经过逐格校验，保证清晰、居中、一眼可辨：
 * 小图（红心 / 笑脸 / 蝴蝶 / 苹果 / 小房子 / 小鱼）用 [art] 的 ASCII 小图直接画出来，
 * 每个字符串一行，`X` 表示涂黑、`.` 表示空白，直观、好改、可读性极强；
 * 大尺寸关卡用绘图工具函数（fillRect / fillTriangle / fillEllipse /
 * carveRect / carveEllipse 等）程序化生成。
 *
 * 提示数字由 [Level.fromAnswer] 自动推导（见 [PuzzleGenerator]）。
 * 本版使用**稀疏提示**：只在一部分格子放数字，位置由"贪心去提示 + 唯一解校验"
 * 管线生成（见 PuzzleGenerator 注释），保证每个关卡仍然只有唯一解 ——
 * 谜面唯一解由 CoreLogicTest 校验。
 * 关卡 id 即"第几关"，顺序解锁由 AccountStore 管理（通关第 N 关解锁 N+1 关）。
 */
object Levels {

    /** 全部关卡，按第 1 关到第 10 关的顺序展示。 */
    val all: List<Level> = listOf(
        heart(), smiley(), butterfly(), apple(), house(), fish(),
        star(), crown(), moon(), rocket(),
    )

    /**
     * 按 id 取内置关(找不到返回 null)。
     * 用途:排行榜里"我打过的题"只返回 id,内置关的题名需在客户端本地补全 ——
     * 内置关数据固化在 [Levels] 中,服务端 puzzles 表只有在线关(负 id)。
     */
    fun byId(id: Int): Level? = all.firstOrNull { it.id == id }

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
            "XX......XX",
            "XXX....XXX",
            "XXXX..XXXX",
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

    /** 苹果：14×14，第 4 关（中等）。细果柄 + 圆润果身（顶部连贯无分叉）+ 收底尖，一眼可辨。 */
    private fun apple() = Level.fromAnswer(
        id = 4,
        name = "苹果",
        difficulty = Difficulty.MEDIUM,
        answer = art(
        "......XX......",   // 果柄
        "......XX......",
        "...XXXXXXXX...",   // 宽肩（苹果顶部不是尖的）
        "..XXXXXXXXXX..",
        ".XXXXXXXXXXXX.",
        "XXXXXXXXXXXXXX",   // 最饱满处
        "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX",
        ".XXXXXXXXXXXX.",   // 圆底收口
        "..XXXXXXXXXX..",
        "....XXXXXX....",
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

    /** 月亮：24×24，第 9 关（困难）。大圆挖去偏置小圆，弯月造型一目了然。 */
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

    // ———— ASCII 像素画：X 涂黑 / . 空白 ————

    /** 把多行 ASCII 小图转成答案像素矩阵（每行必须等长）。 */
    private fun art(vararg rows: String): Array<IntArray> =
        rows.map { row -> IntArray(row.length) { c -> if (row[c] == 'X') 1 else 0 } }.toTypedArray()

    // ———— 大尺寸关卡答案的"程序化作画" ————

    /**
     * 星星 20×20：标准实心五角星（手写逐格，完全左右对称）。
     * 顶尖向上 → 上臂水平横伸（占 1 行全宽）→ 凹口向内向下收 → 下方汇合 →
     * 两个下脚向斜下分叉收尖，五个角清晰可辨。
     */
    private fun star20(): Array<IntArray> = art(
    "....................",
    ".........XX.........",
    ".........XX.........",
    "........XXXX........",
    "........XXXX........",
    ".......XXXXXX.......",
    ".......XXXXXX.......",
    "XXXXXXXXXXXXXXXXXXXX",   // 臂展最宽
    "XXXXXXXXXXXXXXXXXXXX",
    ".XXXXXXXXXXXXXXXXXX.",
    "..XXXXXXXXXXXXXXXX..",
    "...XXXXXXXXXXXXXX...",
    "....XXXXXXXXXXXX....",   // 这里是内凹顶点所在，仍然实心
    "....XXXX....XXXX....",   // 开始分叉
    "....XXX......XXX....",
    "....XX........XX....",
    "....XX........XX....",
    "....X..........X....",   // 腿尖
    "....................",
    "....................",
    )

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

    /** 月亮 24×24：月牙 = 大圆挖去偏置小圆，弯月造型一目了然。圆用严格不等式，边缘无毛刺。 */
    private fun moon24(): Array<IntArray> {
        val g = Array(24) { IntArray(24) }
        fillEllipse(g, 12.0, 12.0, 10.0, 10.0)     // 月轮
        carveEllipse(g, 16.0, 12.0, 7.7, 7.7)      // 偏右挖空 → 弯月（7.7 消除边缘杂散像素）
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

    // ———— 绘图工具 —— 在答案像素矩阵上画基本图形 ————

    /**
     * 实心椭圆：中心 (cx,cy)，水平/垂直半轴 rx/ry。
     * 用严格不等式（<1.0）判定，保证边界处只有真正落在椭圆内的格子被填充，
     * 不会在正上/正下方冒出 1 像素毛刺。
     */
    private fun fillEllipse(grid: Array<IntArray>, cx: Double, cy: Double, rx: Double, ry: Double) {
        for (r in 0 until grid.size) {
            for (c in 0 until grid[0].size) {
                val dx = (c - cx) / rx
                val dy = (r - cy) / ry
                if (dx * dx + dy * dy < 1.0) grid[r][c] = 1
            }
        }
    }

    /** 挖空椭圆：把落在椭圆内的格子清为空白（月牙用）。严格不等式防毛刺。 */
    private fun carveEllipse(grid: Array<IntArray>, cx: Double, cy: Double, rx: Double, ry: Double) {
        for (r in 0 until grid.size) {
            for (c in 0 until grid[0].size) {
                val dx = (c - cx) / rx
                val dy = (r - cy) / ry
                if (dx * dx + dy * dy < 1.0) grid[r][c] = 0
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
}
