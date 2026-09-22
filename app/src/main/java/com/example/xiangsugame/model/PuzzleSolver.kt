package com.example.xiangsugame.model

/**
 * 回溯 + 约束传播求解器（概要设计 3.2.2 节"提示求解"）。
 *
 * 这是本项目最核心的算法模块。它求解的问题是：
 * 给定一组线索（clue），找出所有满足线索约束的涂黑方案。
 * 约束是 Fill-a-Pix 的规则 —— 每个有线索的格 (r,c)，
 * 它周围 3×3 窗口内涂黑格的数量必须恰好等于 clue[r][c]。
 *
 * 用途：
 *  1. 关卡生成时校验谜面**唯一解**（本 Demo 的胜利判定正确性依赖于此）：
 *     如果 solveAll() 返回的解不止一个，说明提示不足以唯一确定答案，
 *     这样的关卡会让玩家"看着线索也推不出来"；
 *  2. 后续"提示功能"可基于本求解器推导确定格（解空间中被锁定的格子）。
 *
 * 内部状态表示（与 UI 层的三态不同，这里是求解器自己的三态）：
 *  -1 未知（尚未确定）、0 空白（确定不涂黑）、1 涂黑（确定涂黑）。
 *
 * 算法流程分两步交替进行：
 *  ① 约束传播（constraint propagation）：不猜测，仅凭线索把
 *     "必然涂黑 / 必然空白"的格子确定下来，反复扫直到没有新变化；
 *  ② 分支（branching）：若仍有未知格，猜一个值（先猜涂黑再猜空白），
 *     递归进入下一层，回溯时还原现场。
 *
 * 结合以上两步就是"回溯 + 约束传播"：先用廉价推导收缩解空间，
 * 只在推不动时才用枚举，从而大幅减少递归分支数。
 */
class PuzzleSolver(
    /** 提示数字矩阵,与 Level.clueGrid 一致;-1 表示该格无提示。 */
    private val clues: Array<IntArray>,
    /** 最多求多少个解。校验唯一解时传 2 —— 找到第二个即可提前停止。 */
    private val maxSolutions: Int = 2,
    /**
     * 求解时间上限(毫秒);传 0 表示不限时。限时场景(如一次性稀疏提示生成、
     * 超大棋盘交互校验)中,超时后 dfs 提前中止,结果可能不完整,
     * 调用方应通过 [timedOut] 判断 —— 超时的结果不能作为"无解 / 唯一解"的判定依据。
     */
    private val timeLimitMillis: Long = 0,
    /**
     * 初始约束(可选):把玩家当前盘面作为硬约束带入求解 ——
     * 1 = 必须涂黑(玩家已涂)、0 = 必须留白(玩家标 ✕)、-1 = 未定。
     * 用于"提示下一步":在保留当前进度的前提下推导仍可确定的格子。
     */
    private val initial: Array<IntArray>? = null,
) {
    private val rows = clues.size
    private val cols = clues[0].size
    /** 当前搜索路径上的棋盘状态,初始全为未知 (-1)。 */
    private val board = Array(rows) { IntArray(cols) { -1 } }

    /** 求解器第 0 层(根节点)约束传播即可确定的格子 —— "纯逻辑可推出"的候选提示。 */
    var rootForcedCells: List<Pair<Int, Int>> = emptyList()
        private set

    init {
        initial?.let { initBoard ->
            require(initBoard.size == rows) { "初始约束行数与线索不一致" }
            for (r in 0 until rows) {
                require(initBoard[r].size == cols) { "初始约束列数与线索不一致" }
                for (c in 0 until cols) {
                    board[r][c] = initBoard[r][c].coerceIn(-1, 1)
                }
            }
        }
    }

    /** 搜索截止时刻(纳秒);不限时为 Long.MAX_VALUE(永不触发)。 */
    private val deadlineNanos: Long =
        if (timeLimitMillis > 0) System.nanoTime() + timeLimitMillis * 1_000_000L else Long.MAX_VALUE

    /** 是否因超过时间上限而提前中止(此时 [solveAll] 结果不完整)。 */
    var timedOut: Boolean = false
        private set

    /** 求出至多 maxSolutions 个满足全部线索的完整解。 */
    fun solveAll(): List<Array<IntArray>> {
        val solutions = mutableListOf<Array<IntArray>>()
        rootForcedCells = emptyList()
        rootPassed = false
        dfs(solutions)
        return solutions
    }

    /** 是否为根节点(dfs 树的第 0 层调用);用于只在根上抓取"纯推导确定格"。 */
    private var rootPassed = false

    /**
     * 深度优先搜索(DFS)主循环。
     *
     * 一个递归调用代表搜索树中的一个节点:
     * 在当前 board 状态下,先做一轮约束传播,若没有未知格了就是一个叶节点
     * (检查是否满足全部线索,满足则记为一个解),否则选择一个未知格分支。
     *
     * @param solutions 累积已找到解的容器(跨递归共享)
     */
    private fun dfs(solutions: MutableList<Array<IntArray>>) {
        // 剪枝：解已经够了就不再深入（这是"至多 maxSolutions"的关键）
        if (solutions.size >= maxSolutions) return
        // 限时剪枝：超时则标记并中止本分支（向上逐层返回，结果不完整）
        if (System.nanoTime() > deadlineNanos) {
            timedOut = true
            return
        }

        val isRoot = !rootPassed
        rootPassed = true

        // propagated 记录本层约束传播新确定的格子，回溯时要还原成 -1
        val propagated = mutableListOf<Pair<Int, Int>>()

        // ————————————————————————————————————————————
        // ① 约束传播：由线索的"必涂/必空"规则锁定未知格
        // ————————————————————————————————————————————
        // 反复扫描整个棋盘，直到一轮扫描中没有任何格子被新确定（changed=false）。
        // 因为一个线索窗口被填满后，可能又会解锁相邻线索的推理。
        var changed = true
        while (changed) {
            changed = false
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    // 无提示的格子不参与约束
                    if (clues[r][c] < 0) continue

                    val target = clues[r][c]       // 该格要求的涂黑总数
                    var filled = 0                 // 窗口内已经确定的涂黑数
                    val unknownCells = mutableListOf<Pair<Int, Int>>() // 窗口内仍未知的格子

                    // 遍历 (r,c) 的 3×3 邻域，统计已涂黑与未知格
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until rows && nc in 0 until cols) {
                                when (board[nr][nc]) {
                                    1 -> filled++
                                    -1 -> unknownCells.add(nr to nc)
                                    // 0（已确定空白）不需要额外处理
                                }
                            }
                        }
                    }

                    // need = 还差多少个格子需要涂黑
                    val need = target - filled

                    // 矛盾剪枝：要么已经超量（need<0），要么把窗口内所有未知格
                    // 全涂黑也不够（need>未知数）。说明当前搜索路径必然无解，
                    // 直接还原现场并向上回溯。
                    if (need < 0 || need > unknownCells.size) {
                        restore(propagated)
                        return
                    }

                    if (need == 0 && unknownCells.isNotEmpty()) {
                        // 还差 0 个涂黑，即窗口内已满足要求，
                        // 那么剩余未知格一个也不能涂 → 全部定为空白
                        for ((nr, nc) in unknownCells) {
                            board[nr][nc] = 0
                            propagated.add(nr to nc)
                        }
                        changed = true
                    } else if (need == unknownCells.size && unknownCells.isNotEmpty()) {
                        // 还差的数量恰好等于未知格数量，
                        // 即所有未知格都必须涂黑才够 → 全部定为涂黑
                        for ((nr, nc) in unknownCells) {
                            board[nr][nc] = 1
                            propagated.add(nr to nc)
                        }
                        changed = true
                    }
                    // 其他情况（0 < need < 未知数）无法确定任何格子，留给分支解决
                }
            }
        }

        // 根节点:记录"纯推导即可确定"的格子(供"提示下一步"优先展示)
        if (isRoot) {
            rootForcedCells = propagated.toList()
        }

        // ————————————————————————————————————————————
        // ② 找一个未知格进行分支（启发式：找第一个未知格）
        // ————————————————————————————————————————————
        var next: Pair<Int, Int>? = null
        outer@ for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (board[r][c] == -1) {
                    next = r to c
                    break@outer
                }
            }
        }

        // 没有未知格了：当前板面已经完整，验证后作为一个解
        if (next == null) {
            if (isValid()) {
                // 深拷贝存入解集（board 后续会被回溯修改，必须拷贝）
                solutions.add(board.map { it.copyOf() }.toTypedArray())
            }
            restore(propagated)
            return
        }

        // 有未知格：尝试给这个格子赋值并递归。
        // 先尝试涂黑(1)再尝试空白(0)：求解器只需要"数量够"，顺序不影响正确性。
        for (v in intArrayOf(1, 0)) {
            board[next.first][next.second] = v
            dfs(solutions)
            // 回溯：递归返回后撤销刚才的赋值
            board[next.first][next.second] = -1
            if (solutions.size >= maxSolutions) break
        }
        // 还原本层约束传播的结果，把现场交还给父调用
        restore(propagated)
    }

    /** 把约束传播阶段新确定的格子全部还原为未知 (-1)，用于回溯现场。 */
    private fun restore(cells: List<Pair<Int, Int>>) {
        for ((r, c) in cells) board[r][c] = -1
    }

    /** 校验当前完整板面：每个有线索的格，其 3×3 窗口内涂黑数必须恰好等于线索。 */
    private fun isValid(): Boolean {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (clues[r][c] < 0) continue
                var filled = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0 until rows && nc in 0 until cols && board[nr][nc] == 1) {
                            filled++
                        }
                    }
                }
                if (filled != clues[r][c]) return false
            }
        }
        return true
    }
}

/** 一个提示步骤:定位到某格并给出应达成的状态(target: 1 涂黑 / 0 留白)。 */
data class HintStep(val row: Int, val col: Int, val target: Int, val message: String) {
    /** 无具体格子可提示(如盘面矛盾 / 已全部填满),仅用于展示 message。 */
    val hasCell: Boolean get() = row >= 0 && col >= 0
}

/**
 * 单人「提示下一步」:以当前盘面为硬约束求解(本题唯一解),找出当前仍
 * 未决定、但由线索逻辑可推导出结果的格子——优先"纯约束传播"即可确定的格子,
 * 否则取唯一解下按行优先的第一个未决定格。盘面与线索矛盾时提示先检查错误。
 *
 * **为什么要限时**:求解器为了确认"这一步必须这么走",要搜到证明没有第二个解才返回;
 * 在 28×28 的大棋盘上这是重负载(实测第 10 关可达数秒),而调用点在 UI 线程同步执行 ——
 * 玩家点一下💡界面就冻住。所以改成"有限预算的两级搜索":
 *   ① 先用很短预算只做约束传播,拿到"纯逻辑一步可推出"的格子(绝大多数情况够用);
 *   ② 没结果就再用较长但仍有上限的预算完整求解;
 *   ③ 都没赶上就给一句降级提示,绝不卡界面。
 *
 * @param board 当前棋盘(CellState.value:0 未定 / 1 涂黑 / 2 标记留白)
 */
fun nextHint(clues: Array<IntArray>, board: Array<IntArray>): HintStep {
    val init = Array(board.size) { r ->
        IntArray(board[r].size) { c ->
            when (board[r][c]) {
                CellState.FILLED.value -> 1
                CellState.MARKED_EMPTY.value -> 0
                else -> -1
            }
        }
    }

    for (budget in longArrayOf(PropagationBudgetMs, FullSearchBudgetMs)) {
        val solver = PuzzleSolver(clues, maxSolutions = 2, timeLimitMillis = budget, initial = init)
        val solutions = solver.solveAll()
        if (solver.timedOut) {
            continue // 这一遍的结论不可信(可能漏解),换更大预算再来
        }
        if (solutions.isEmpty()) {
            return HintStep(-1, -1, -1, "当前盘面存在矛盾,建议先「检查错误」再继续")
        }
        val solution = solutions[0]
        for ((r, c) in solver.rootForcedCells) {
            if (board[r][c] != CellState.UNDECIDED.value) continue
            return if (solution[r][c] == 1) {
                HintStep(r, c, 1, "第 ${r + 1} 行 ${c + 1} 列应涂黑")
            } else {
                HintStep(r, c, 0, "第 ${r + 1} 行 ${c + 1} 列应留白(标 ✕)")
            }
        }
        for (r in board.indices) {
            for (c in board[r].indices) {
                if (board[r][c] != CellState.UNDECIDED.value) continue
                return if (solution[r][c] == 1) {
                    HintStep(r, c, 1, "第 ${r + 1} 行 ${c + 1} 列应涂黑(推演得出)")
                } else {
                    HintStep(r, c, 0, "第 ${r + 1} 行 ${c + 1} 列应留白(推演得出)")
                }
            }
        }
        return HintStep(-1, -1, -1, "盘面已经填满了,点「检查」看看对不对?")
    }
    return HintStep(-1, -1, -1, "这一步暂时推不出来(棋盘较大,计算超时)—— 先随手确定一格再点提示")
}


/** 提示求解的第一档预算(ms):只够约束传播,但能覆盖绝大多数盘面。 */
private const val PropagationBudgetMs = 300L
/** 提示求解的第二档预算(ms):完整求解的上限,超时就降级提示。 */
private const val FullSearchBudgetMs = 1500L