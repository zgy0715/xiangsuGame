import { cellKey } from './CellState';
import { PuzzleGenerator } from './PuzzleGenerator';

/** 难度分级(对应 Android 版 model/Level.kt 的 Difficulty)。 */
export enum Difficulty {
  EASY = 0,
  MEDIUM = 1,
  HARD = 2,
}

/** 难度 → 显示名。ArkTS 的 enum 不能带字段,用查找表代替 Kotlin 的构造参数。 */
export function difficultyLabel(difficulty: Difficulty): string {
  if (difficulty === Difficulty.EASY) {
    return '简单';
  }
  if (difficulty === Difficulty.MEDIUM) {
    return '中等';
  }
  return '困难';
}

/** 难度 → 星级(关卡卡片上的 ★ 个数)。 */
export function difficultyStars(difficulty: Difficulty): number {
  if (difficulty === Difficulty.EASY) {
    return 1;
  }
  if (difficulty === Difficulty.MEDIUM) {
    return 2;
  }
  return 3;
}

/** 难度 → 与服务端一致的字符串档位。 */
export function difficultyName(difficulty: Difficulty): string {
  if (difficulty === Difficulty.EASY) {
    return 'EASY';
  }
  if (difficulty === Difficulty.MEDIUM) {
    return 'MEDIUM';
  }
  return 'HARD';
}

/** 服务端难度字符串 → 难度(未知值按 MEDIUM 兜底,避免版本差异崩溃)。 */
export function difficultyFromName(name: string | null | undefined): Difficulty {
  if (name === 'EASY') {
    return Difficulty.EASY;
  }
  if (name === 'HARD') {
    return Difficulty.HARD;
  }
  return Difficulty.MEDIUM;
}

/**
 * 关卡数据(对应 Android 版 model/Level.kt)。
 *
 * 两套同尺寸矩阵:
 *  - clueGrid[r][c]:0..9 提示数字(该格 3×3 窗口内需涂黑数),-1 表示无提示;
 *  - answerGrid[r][c]:答案像素矩阵,1 涂黑 / 0 空白。
 *
 * 构造时逐行做防御性校验:只查第 0 行会放过"锯齿矩阵",
 * 之后会以数组越界的形式崩在游戏页的求解/答案比对上。
 */
export class Level {
  readonly id: number;
  readonly name: string;
  readonly difficulty: Difficulty;
  readonly rows: number;
  readonly cols: number;
  readonly clueGrid: number[][];
  readonly answerGrid: number[][];
  /** 限时模式的时限(秒);0 表示该关不用于限时模式。 */
  readonly timedLimitSeconds: number;

  constructor(id: number, name: string, difficulty: Difficulty, rows: number, cols: number,
              clueGrid: number[][], answerGrid: number[][], timedLimitSeconds: number) {
    if (rows <= 0 || cols <= 0) {
      throw new Error('棋盘尺寸必须大于 0');
    }
    if (clueGrid.length !== rows || answerGrid.length !== rows) {
      throw new Error('提示布局行数不匹配');
    }
    for (let r = 0; r < rows; r++) {
      if (clueGrid[r].length !== cols) {
        throw new Error('提示布局存在列数不一致的行');
      }
      if (answerGrid[r].length !== cols) {
        throw new Error('答案矩阵存在列数不一致的行');
      }
    }
    this.id = id;
    this.name = name;
    this.difficulty = difficulty;
    this.rows = rows;
    this.cols = cols;
    this.clueGrid = clueGrid;
    this.answerGrid = answerGrid;
    this.timedLimitSeconds = timedLimitSeconds;
  }

  /**
   * 由答案像素矩阵构建关卡:提示数字自动推导。
   * @param cluePositions 需要放提示的格子集合(null = 全格提示)。
   */
  static fromAnswer(id: number, name: string, difficulty: Difficulty, answer: number[][],
                    cluePositions: Set<string> | null, timedLimitSeconds: number): Level {
    const rows: number = answer.length;
    const cols: number = answer[0].length;
    const clues: number[][] = PuzzleGenerator.cluesFromAnswer(answer, cluePositions);
    return new Level(id, name, difficulty, rows, cols, clues, answer, timedLimitSeconds);
  }
}
