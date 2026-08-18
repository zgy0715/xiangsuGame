package com.example.xiangsugame.model

/**
 * 实时逻辑校验（概要设计 3.2.2 节"逻辑校验子模块"）。
 *
 * 负责两类职责：
 *  1. 解谜过程中的"错误提示"（F2.3）：用【确定性矛盾检测】实时标红"必错"格子；
 *  2. 通关判定：比对玩家涂黑结果与答案像素矩阵是否完全一致。
 *
 * 设计要点 —— 为什么不用"线索约束是否满足"来判定错误？
 * 解谜过程中绝大多数中间状态天然不满足全部数字约束（因为还没画完）。
 * 例如线索为 9 的格子，玩家刚涂 3 格时约束"不满足"，但这是正常推进，不是错。
 * 因此"错误提示"必须采用【确定性矛盾检测】：只报告"无论如何补救都注定错误"
 * 的格子，避免解谜中间态误报。该检测与提示求解共用同一套 3×3 约束推导。
 */
class Validator(private val level: Level) {

    // 直接引用关卡数据，避免每次调用都解引用 level
    private val clues = level.clueGrid
    private val rows = level.rows
    private val cols = level.cols

    /**
     * 确定性矛盾检测：返回与线索产生"必错矛盾"的所有方格（集合）。
     *
     * 判定逻辑：
     * 对每个有线索的格，统计其 3×3 邻域内的已涂黑数 filled 与未知格数 unknown：
     *  - filled > clue：   涂黑已经超量，把窗口内剩余未知格全擦掉也救不回来 → 必错；
     *  - filled + unknown < clue：即便把剩余未知格全部涂黑，总数也不够 → 必错。
     * 除此之外的中间态（还没涂够，但还有余地）都视为"正常推进"，不报错。
     *
     * 注意：返回值是"处于矛盾窗口内的所有格子"（不只针对单个涂错格），
     * 这样错误高亮时整片受影响的区域都会被标出来，玩家更容易发现问题。
     *
     * @param board 当前棋盘状态（由 GameBoard.board 传入）
     * @return 矛盾格坐标集合；无矛盾时返回空集合
     */
    fun contradictionCells(board: Array<IntArray>): Set<Pair<Int, Int>> {
        val result = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 无提示的格子不构成约束，跳过
                if (clues[r][c] < 0) continue

                val clue = clues[r][c]
                var filled = 0
                var unknown = 0
                // 收集该格 3×3 邻域内所有格子（用于矛盾时整体标红）
                val cells = mutableListOf<Pair<Int, Int>>()
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            cells.add(nr to nc)
                            when (board[nr][nc]) {
                                CellState.FILLED.value -> filled++       // 涂黑
                                CellState.UNDECIDED.value -> unknown++   // 未知（标记空白视为已确定，不计入 unknown）
                            }
                        }
                    }
                }
                if (filled > clue || filled + unknown < clue) {
                    result.addAll(cells)
                }
            }
        }
        return result
    }

    /**
     * 胜利判定：玩家涂黑结果与答案像素矩阵逐格比对。
     *
     * 规则：未确定 (UNDECIDED) 与标记空白 (MARKED_EMPTY) 都视为"非涂黑"，
     * 即玩家"标了叉"的格子算作空白。只有当每个格的涂黑状态（涂黑/非涂黑）
     * 都与答案完全一致时才算通关 —— 这也是 Fill-a-Pix 的完成标准：
     * 不要求玩家标记出每个空白格，只要涂黑的格都对即可。
     *
     * @param board 当前棋盘状态
     * @return true 表示与答案完全一致，可以判定通关
     */
    fun isSolved(board: Array<IntArray>): Boolean {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val isFilled = board[r][c] == CellState.FILLED.value
                val answerFilled = level.answerGrid[r][c] == 1
                if (isFilled != answerFilled) return false
            }
        }
        return true
    }
}
