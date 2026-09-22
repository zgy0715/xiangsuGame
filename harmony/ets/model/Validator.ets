import { CellState, cellKey, cellStateFromValue } from './CellState';
import { Level } from './Level';

/**
 * 实时逻辑校验(对应 Android 版 model/Validator.kt)。
 *
 * 1. 通关判定:涂黑结果与答案逐格比对(未确定与标记空白都算"非涂黑");
 * 2. 显式"检查错误":只标出**已提交但画错**的格子,平时零打扰;
 * 3. 已满足提示:某提示窗口已确定且数量达标 → 数字变绿。
 */
export class Validator {
  private readonly level: Level;
  private readonly clues: number[][];
  private readonly rows: number;
  private readonly cols: number;

  constructor(level: Level) {
    this.level = level;
    this.clues = level.clueGrid;
    this.rows = level.rows;
    this.cols = level.cols;
  }

  /** 精确错误检测:返回所有"已提交但与答案冲突"的格子键集合。未决定格永不算错。 */
  wrongCells(board: number[][]): Set<string> {
    const result: Set<string> = new Set<string>();
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        const state: CellState = cellStateFromValue(board[r][c]);
        if (state === CellState.UNDECIDED) {
          continue;
        }
        const isFilled: boolean = state === CellState.FILLED;
        const answerFilled: boolean = this.level.answerGrid[r][c] === 1;
        if (isFilled !== answerFilled) {
          result.add(cellKey(r, c));
        }
      }
    }
    return result;
  }

  /** 已满足的提示:窗口内没有未决定格且涂黑数恰好等于提示值。 */
  satisfiedClues(board: number[][]): Set<string> {
    const result: Set<string> = new Set<string>();
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        const target: number = this.clues[r][c];
        if (target < 0) {
          continue;
        }
        let filled: number = 0;
        let undecided: number = 0;
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            const nr: number = r + dr;
            const nc: number = c + dc;
            if (nr >= 0 && nr < this.rows && nc >= 0 && nc < this.cols) {
              const v: number = board[nr][nc];
              if (v === (CellState.FILLED as number)) {
                filled++;
              } else if (v === (CellState.UNDECIDED as number)) {
                undecided++;
              }
            }
          }
        }
        if (undecided === 0 && filled === target) {
          result.add(cellKey(r, c));
        }
      }
    }
    return result;
  }

  /** 胜利判定:每格的"是否涂黑"都与答案一致(标了 ✕ 的格算空白)。 */
  isSolved(board: number[][]): boolean {
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        const isFilled: boolean = board[r][c] === (CellState.FILLED as number);
        const answerFilled: boolean = this.level.answerGrid[r][c] === 1;
        if (isFilled !== answerFilled) {
          return false;
        }
      }
    }
    return true;
  }
}
