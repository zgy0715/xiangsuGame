package com.example.xiangsugame.model

/**
 * 难度分级。label 用于展示，stars 用于关卡卡片上的星级（★ 个数）。
 * 基础逻辑（简单/中等）与高级逻辑（困难）的划分源自玩法设计。
 */
enum class Difficulty(val label: String, val stars: Int) {
    EASY("简单", 1),
    MEDIUM("中等", 2),
    HARD("困难", 3),
}

/**
 * 关卡数据（概要设计 3.4 节）。
 *
 * 一个关卡由两套同样尺寸的矩阵组成：
 *  - clueGrid[r][c]：0..9 提示数字，表示该格 3×3 窗口内需要涂黑的格数；
 *                     -1 表示该格无提示；
 *  - answerGrid[r][c]：答案像素矩阵，1 涂黑 / 0 空白 —— 这就是玩家最终
 *                     要还原出来的那幅像素画。
 *
 * 构造时通过 init 块做防御性校验，保证两套矩阵尺寸与 rows/cols 一致，
 * 避免后续所有算法（求解、校验、渲染）拿到不一致的数据。
 */
data class Level(
    val id: Int,
    val name: String,
    val difficulty: Difficulty,
    val rows: Int,
    val cols: Int,
    val clueGrid: Array<IntArray>,
    val answerGrid: Array<IntArray>,
    /** 限时挑战模式下的时限（秒）；null 表示不在限时模式使用该关（正常按难度给默认值）。 */
    val timedLimitSeconds: Int? = null,
) {
    init {
        require(rows > 0 && cols > 0) { "棋盘尺寸必须大于 0" }
        require(clueGrid.size == rows && answerGrid.size == rows) { "提示布局行数不匹配" }
        // 逐行校验:只查第 0 行会放过"锯齿矩阵"(某一行短一格),
        // 之后会以数组越界的形式崩在游戏页的求解/答案比对上。
        require(clueGrid.all { it.size == cols }) { "提示布局存在列数不一致的行" }
        require(answerGrid.all { it.size == cols }) { "答案矩阵存在列数不一致的行" }
    }

    companion object {
        /**
         * 由答案像素矩阵构建关卡：自动推导提示数字（PuzzleGenerator）。
         *
         * 这是 Demo 关卡的标准构造方式 —— 设计者只需画出像素画（answer），
         * 提示数字由生成器自动算出来。相比手写提示，既省事又保证一致性。
         *
         * @param cluePositions 需要放置提示数字的格子集合。
         *   - null：全格提示（每个格子都有数字，简单但棋盘很"满"）；
         *   - 指定集合：只在这些位置显示提示（棋盘清爽、推理有难度），
         *     集合一般由"贪心去提示 + 唯一解校验"管线生成，保证谜面有唯一解。
         */
        fun fromAnswer(
            id: Int,
            name: String,
            difficulty: Difficulty,
            answer: Array<IntArray>,
            cluePositions: Set<Pair<Int, Int>>? = null,
            timedLimitSeconds: Int? = null,
        ): Level {
            val rows = answer.size
            val cols = answer[0].size
            return Level(
                id = id,
                name = name,
                difficulty = difficulty,
                rows = rows,
                cols = cols,
                clueGrid = if (cluePositions == null) {
                    PuzzleGenerator.cluesFromAnswer(answer)
                } else {
                    PuzzleGenerator.cluesFromAnswer(answer, cluePositions)
                },
                answerGrid = answer,
                timedLimitSeconds = timedLimitSeconds,
            )
        }
    }
}
