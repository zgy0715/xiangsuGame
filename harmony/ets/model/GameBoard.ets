import { CellState } from './CellState';

/**
 * 棋盘状态容器 + 撤销/重做指令栈(对应 Android 版 model/GameBoard.kt)。
 *
 * 职责:
 *  - 保存每个格子的三态(0 未确定 / 1 涂黑 / 2 标记空白);
 *  - 提供"笔划涂刷"入口:按住拖动/单击 = 一条笔划(start → stroke ×N → commit),
 *    整条笔划作为一个可撤销步骤;
 *  - 两条指令栈实现标准撤销/重做,产生新操作时清空重做栈。
 *
 * Move 用扁平数组记录一批格子变化:每 4 个数一组 [row, col, before, after]。
 * ArkTS 没有 sealed class,这样比类继承更省事,也不在热路径上频繁分配小对象。
 */
export class GameBoard {
  readonly rows: number;
  readonly cols: number;
  /** board[r][c] 取值见 CellState(0 未确定 / 1 涂黑 / 2 标记空白)。 */
  readonly board: number[][];

  private undoStack: number[][] = [];
  private redoStack: number[][] = [];
  private strokeActive: boolean = false;
  private strokeCells: number[] = [];

  constructor(rows: number, cols: number) {
    this.rows = rows;
    this.cols = cols;
    this.board = [];
    for (let r = 0; r < rows; r++) {
      const row: number[] = [];
      for (let c = 0; c < cols; c++) {
        row.push(CellState.UNDECIDED as number);
      }
      this.board.push(row);
    }
  }

  /** 撤销栈非空 → 允许撤销。 */
  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  /** 重做栈非空 → 允许重做。 */
  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  // ———— 笔划涂刷(工具笔拖动连涂的核心)————

  /** 开始一条笔划:清空本次收集的改动(手指落下时调用)。 */
  startStroke(): void {
    this.strokeActive = true;
    this.strokeCells = [];
  }

  /** 在笔划中给单格上目标状态;与目标一致、越界或不在笔划中时返回 false。 */
  strokeCell(row: number, col: number, target: CellState): boolean {
    if (!this.strokeActive) {
      return false;
    }
    if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) {
      return false;
    }
    const before: number = this.board[row][col];
    const after: number = target as number;
    if (before === after) {
      return false;
    }
    this.board[row][col] = after;
    this.strokeCells.push(row, col, before, after);
    return true;
  }

  /** 结束笔划:把本次所有单格改动作为一个 Batch 压入撤销栈。空笔划返回 false。 */
  commitStroke(): boolean {
    this.strokeActive = false;
    if (this.strokeCells.length === 0) {
      return false;
    }
    this.undoStack.push(this.strokeCells.slice());
    this.strokeCells = [];
    this.redoStack = [];
    return true;
  }

  /** 放弃当前笔划:回滚本次画出的所有改动(手势被取消等场景)。 */
  cancelStroke(): void {
    if (!this.strokeActive) {
      return;
    }
    // 逆序回滚:同一格被涂改多次时,最早写入的 before 才是原始值
    for (let i = this.strokeCells.length - 4; i >= 0; i -= 4) {
      const row: number = this.strokeCells[i];
      const col: number = this.strokeCells[i + 1];
      const before: number = this.strokeCells[i + 2];
      this.board[row][col] = before;
    }
    this.strokeCells = [];
    this.strokeActive = false;
  }

  /** 便利方法:一条单格笔划(单击落笔即走 start/commit 的路径)。 */
  paintCell(row: number, col: number, target: CellState): boolean {
    this.startStroke();
    this.strokeCell(row, col, target);
    return this.commitStroke();
  }

  /** 一键补齐:答案需要涂黑的补成涂黑,其余清回未确定。整批作为一步撤销。 */
  applySolution(answer: number[][]): boolean {
    const moves: number[] = [];
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        const target: number = answer[r][c] === 1
          ? (CellState.FILLED as number)
          : (CellState.UNDECIDED as number);
        const before: number = this.board[r][c];
        if (before !== target) {
          this.board[r][c] = target;
          moves.push(r, c, before, target);
        }
      }
    }
    if (moves.length === 0) {
      return false;
    }
    this.undoStack.push(moves);
    this.redoStack = [];
    return true;
  }

  /** 撤销一步(单格或整批),并转入重做栈。 */
  undo(): boolean {
    const move: number[] | undefined = this.undoStack.pop();
    if (move === undefined) {
      return false;
    }
    this.replay(move, false);
    this.redoStack.push(move);
    return true;
  }

  /** 重做一步(重放最近一次被撤销的改动),并转回撤销栈。 */
  redo(): boolean {
    const move: number[] | undefined = this.redoStack.pop();
    if (move === undefined) {
      return false;
    }
    this.replay(move, true);
    this.undoStack.push(move);
    return true;
  }

  private replay(move: number[], useAfter: boolean): void {
    for (let i = 0; i < move.length; i += 4) {
      this.board[move[i]][move[i + 1]] = useAfter ? move[i + 3] : move[i + 2];
    }
  }

  /** 清空棋盘并清除撤销/重做历史("重置")。 */
  reset(): void {
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        this.board[r][c] = CellState.UNDECIDED as number;
      }
    }
    this.undoStack = [];
    this.redoStack = [];
  }
}
