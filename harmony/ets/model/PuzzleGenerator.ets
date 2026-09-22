/**
 * 关卡生成管线中的"提示推导"环节(对应 Android 版 model/PuzzleGenerator.ets)。
 *
 * 由答案像素矩阵自动推导提示数字 —— 提示必须与答案严格一致,否则谜面无解,
 * 所以关卡只定义"答案画",提示统一由本类算出来。
 *
 * 推导规则(Fill-a-Pix 核心规则):
 *   clue[r][c] = 以 (r,c) 为中心的 3×3 窗口内涂黑格的数量(越界邻居不计)。
 */
import { cellKey } from './CellState';

export class PuzzleGenerator {

  /**
   * 由答案生成提示矩阵;positions 为 null 表示"全格提示",
   * 指定集合时只在那些格子放数字(稀疏提示),其余为 -1(无提示)。
   */
  static cluesFromAnswer(answer: number[][], positions: Set<string> | null = null): number[][] {
    const rows: number = answer.length;
    const cols: number = answer[0].length;
    const clues: number[][] = [];
    for (let r = 0; r < rows; r++) {
      const row: number[] = [];
      for (let c = 0; c < cols; c++) {
        row.push(-1);
      }
      clues.push(row);
    }
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (positions !== null && !positions.has(cellKey(r, c))) {
          continue;
        }
        let count: number = 0;
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            const nr: number = r + dr;
            const nc: number = c + dc;
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && answer[nr][nc] === 1) {
              count++;
            }
          }
        }
        clues[r][c] = count;
      }
    }
    return clues;
  }
}
