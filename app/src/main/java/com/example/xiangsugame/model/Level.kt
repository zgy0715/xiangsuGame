package com.example.xiangsugame.model

/**
 * 难度分级。当前仅用于关卡卡片展示，后续可按难度过滤关卡列表。
 * 基础逻辑（简单/中等）与高级逻辑（困难）的划分源自玩法设计。
 */
enum class Difficulty(val label: String) {
    EASY("简单"),
    MEDIUM("中等"),
    HARD("困难"),
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
) {
    init {
        require(rows > 0 && cols > 0) { "棋盘尺寸必须大于 0" }
        require(clueGrid.size == rows && answerGrid.size == rows) { "提示布局行数不匹配" }
        require(clueGrid[0].size == cols && answerGrid[0].size == cols) { "列数不匹配" }
    }

    companion object {
        /**
         * 由答案像素矩阵构建关卡：自动推导提示数字（PuzzleGenerator）。
         *
         * 这是 Demo 关卡的标准构造方式 —— 设计者只需画出像素画（answer），
         * 提示数字由生成器自动算出来。相比手写提示，既省事又保证一致性。
         *
         * 关卡难度由数据的提示密度/推理深度决定；本 Demo 先统一生成全量提示，
         * 所以当前三关的难度标签更多是"名义难度"。
         */
        fun fromAnswer(
            id: Int,
            name: String,
            difficulty: Difficulty,
            answer: Array<IntArray>,
        ): Level {
            val rows = answer.size
            val cols = answer[0].size
            return Level(
                id = id,
                name = name,
                difficulty = difficulty,
                rows = rows,
                cols = cols,
                clueGrid = PuzzleGenerator.cluesFromAnswer(answer),
                answerGrid = answer,
            )
        }
    }
}
