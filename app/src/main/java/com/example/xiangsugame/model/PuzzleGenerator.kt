package com.example.xiangsugame.model

/**
 * 关卡生成管线中的"提示推导"环节（概要设计 3.4 节）：
 * 由答案像素矩阵自动推导每个格的提示数字。
 *
 * 为什么要"自动推导"而非手写提示？
 * 手写一份 10×10 的提示矩阵既繁琐又容易出错，而且提示必须与答案严格一致
 * （否则谜面无解）。所以 Demo 关卡只用像素画定义"答案"，
 * 提示数字统一由本生成器自动算出来，保证 100% 一致。
 *
 * 推导规则（Fill-a-Pix 的核心规则）：
 *   clue[r][c] = 以 (r,c) 为中心的 3×3 窗口内涂黑格的数量。
 * 注意窗口会超出棋盘边界，越界部分不计入数量（边界/角格窗口会被"裁剪"）。
 *
 * 本 Demo 生成的是"全量提示"——每个格都有提示数字。
 * 实际关卡设计可以让部分格没有提示（值为 -1），难度会随之上升。
 */
object PuzzleGenerator {

    /**
     * 由答案像素矩阵生成提示数字矩阵。
     *
     * @param answer 答案像素矩阵，answer[r][c] = 1 表示该格涂黑、0 表示空白。
     * @return 与 answer 同尺寸的提示矩阵；clue[r][c] 为 0..9 的涂黑计数。
     */
    fun cluesFromAnswer(answer: Array<IntArray>): Array<IntArray> {
        val rows = answer.size
        val cols = answer[0].size
        // 初始化为 -1（表示"无提示"），随后每个格子都会被填充为实际计数
        val clues = Array(rows) { IntArray(cols) { -1 } }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var count = 0
                // 遍历以 (r,c) 为中心的 3×3 窗口（dr, dc 取 -1/0/1）
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = c + dc
                        // 越界的邻居不计入；只统计涂黑的格
                        if (nr in 0 until rows && nc in 0 until cols && answer[nr][nc] == 1) {
                            count++
                        }
                    }
                }
                clues[r][c] = count
            }
        }
        return clues
    }
}
