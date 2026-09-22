import { CellState, cellKey } from './CellState';

/**
 * 回溯 + 约束传播求解器(对应 Android 版 model/PuzzleSolver.kt)。
 *
 * 求:给定线索矩阵,找出所有满足线索的涂黑方案(至多 maxSolutions 个)。
 *
 * 求解器内部三态(与 UI 的三态不同):-1 未知 / 0 确定空白 / 1 确定涂黑。
 *
 * 两步交替:
 *  ① 约束传播:仅凭线索锁定"必然涂黑/必然空白"的格子,反复扫到无新变化;
 *  ② 分支:仍有未知格时猜一个值(先 1 后 0),递归后回溯还原现场。
 *
 * 用途:关卡生成时校验唯一解;以及「提示下一步」按当前盘面推导确定格。
 */
export class PuzzleSolver {
  private readonly clues: number[][];
  private readonly maxSolutions: number;
  private readonly rows: number;
  private readonly cols: number;
  private readonly board: number[][];
  private readonly deadlineMs: number;

  /** 求解器第 0 层(根节点)约束传播即可确定的格子 —— "纯逻辑可推出"的候选提示。 */
  rootForcedCells: number[][] = [];
  /** 是否因超过时间上限而提前中止(此时 [solveAll] 结果不完整)。 */
  timedOut: boolean = false;

  private rootPassed: boolean = false;

  /**
   * @param timeLimitMillis 求解时间上限(毫秒);0 表示不限时。
   *        超时后 dfs 提前中止,结果可能不完整,调用方必须看 [timedOut]。
   * @param initial 初始约束(可选):1 必须涂黑 / 0 必须留白 / -1 未定 —— 用于「提示下一步」。
   */
  constructor(clues: number[][], maxSolutions: number = 2, timeLimitMillis: number = 0,
              initial: number[][] | null = null) {
    this.clues = clues;
    this.maxSolutions = maxSolutions;
    this.rows = clues.length;
    this.cols = clues[0].length;
    this.deadlineMs = timeLimitMillis > 0 ? Date.now() + timeLimitMillis : Number.MAX_SAFE_INTEGER;

    this.board = [];
    for (let r = 0; r < this.rows; r++) {
      const row: number[] = [];
      for (let c = 0; c < this.cols; c++) {
        row.push(-1);
      }
      this.board.push(row);
    }
    if (initial !== null) {
      if (initial.length !== this.rows) {
        throw new Error('初始约束行数与线索不一致');
      }
      for (let r = 0; r < this.rows; r++) {
        if (initial[r].length !== this.cols) {
          throw new Error('初始约束列数与线索不一致');
        }
        for (let c = 0; c < this.cols; c++) {
          const v: number = initial[r][c];
          this.board[r][c] = v < -1 ? -1 : (v > 1 ? 1 : v);
        }
      }
    }
  }

  /** 求出至多 maxSolutions 个满足全部线索的完整解。 */
  solveAll(): number[][][] {
    const solutions: number[][][] = [];
    this.rootForcedCells = [];
    this.rootPassed = false;
    this.dfs(solutions);
    return solutions;
  }

  private dfs(solutions: number[][][]): void {
    if (solutions.length >= this.maxSolutions) {
      return;
    }
    if (Date.now() > this.deadlineMs) {
      this.timedOut = true;
      return;
    }

    const isRoot: boolean = !this.rootPassed;
    this.rootPassed = true;

    // 本层约束传播新确定的格子,回溯时要还原成 -1
    const propagated: number[][] = [];

    // ① 约束传播
    let changed: boolean = true;
    while (changed) {
      changed = false;
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          if (this.clues[r][c] < 0) {
            continue;
          }
          const target: number = this.clues[r][c];
          let filled: number = 0;
          const unknownCells: number[][] = [];
          for (let dr = -1; dr <= 1; dr++) {
            for (let dc = -1; dc <= 1; dc++) {
              const nr: number = r + dr;
              const nc: number = c + dc;
              if (nr >= 0 && nr < this.rows && nc >= 0 && nc < this.cols) {
                const v: number = this.board[nr][nc];
                if (v === 1) {
                  filled++;
                } else if (v === -1) {
                  unknownCells.push([nr, nc]);
                }
              }
            }
          }
          const need: number = target - filled;
          // 矛盾剪枝:已超量,或把未知格全涂黑也不够 → 当前路径必然无解
          if (need < 0 || need > unknownCells.length) {
            this.restore(propagated);
            return;
          }
          if (need === 0 && unknownCells.length > 0) {
            for (let i = 0; i < unknownCells.length; i++) {
              this.board[unknownCells[i][0]][unknownCells[i][1]] = 0;
              propagated.push(unknownCells[i]);
            }
            changed = true;
          } else if (need === unknownCells.length && unknownCells.length > 0) {
            for (let i = 0; i < unknownCells.length; i++) {
              this.board[unknownCells[i][0]][unknownCells[i][1]] = 1;
              propagated.push(unknownCells[i]);
            }
            changed = true;
          }
        }
      }
    }

    if (isRoot) {
      this.rootForcedCells = propagated.slice();
    }

    // ② 找第一个未知格分支
    let nextRow: number = -1;
    let nextCol: number = -1;
    for (let r = 0; r < this.rows && nextRow < 0; r++) {
      for (let c = 0; c < this.cols; c++) {
        if (this.board[r][c] === -1) {
          nextRow = r;
          nextCol = c;
          break;
        }
      }
    }

    // 没有未知格:完整解,验证后记录
    if (nextRow < 0) {
      if (this.isValid()) {
        const copy: number[][] = [];
        for (let r = 0; r < this.rows; r++) {
          copy.push(this.board[r].slice());
        }
        solutions.push(copy);
      }
      this.restore(propagated);
      return;
    }

    const values: number[] = [1, 0];
    for (let i = 0; i < values.length; i++) {
      this.board[nextRow][nextCol] = values[i];
      this.dfs(solutions);
      this.board[nextRow][nextCol] = -1;
      if (solutions.length >= this.maxSolutions) {
        break;
      }
    }
    this.restore(propagated);
  }

  private restore(cells: number[][]): void {
    for (let i = 0; i < cells.length; i++) {
      this.board[cells[i][0]][cells[i][1]] = -1;
    }
  }

  /** 校验完整板面:每个有线索的格,其 3×3 窗口涂黑数必须恰好等于线索。 */
  private isValid(): boolean {
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        if (this.clues[r][c] < 0) {
          continue;
        }
        let filled: number = 0;
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            const nr: number = r + dr;
            const nc: number = c + dc;
            if (nr >= 0 && nr < this.rows && nc >= 0 && nc < this.cols
              && this.board[nr][nc] === 1) {
              filled++;
            }
          }
        }
        if (filled !== this.clues[r][c]) {
          return false;
        }
      }
    }
    return true;
  }
}

/** 一个提示步骤(对应 Kotlin 的 HintStep):target 1 涂黑 / 0 留白 / -1 仅展示文案。 */
export class HintStep {
  readonly row: number;
  readonly col: number;
  readonly target: number;
  readonly message: string;

  constructor(row: number, col: number, target: number, message: string) {
    this.row = row;
    this.col = col;
    this.target = target;
    this.message = message;
  }

  /** 无具体格子可提示(盘面矛盾 / 已填满),仅展示 message。 */
  get hasCell(): boolean {
    return this.row >= 0 && this.col >= 0;
  }
}

/**
 * 单人「提示下一步」(对应 Android 版 model/PuzzleSolver.kt 的 nextHint):
 * 以当前盘面为硬约束求解,优先给出"纯约束传播即可确定"的格子,
 * 否则取唯一解下按行优先的第一个未决定格;盘面矛盾时提示先检查错误。
 *
 * **为什么要限时**:求解器要搜到"证明没有第二个解"才返回,28×28 大棋盘上是重负载
 * (实测第 10 关数秒),而调用方在 UI 线程同步执行 —— 点一下💡界面就冻住。
 * 这里改成"有限预算的两级搜索":先短预算只做约束传播,再较长预算完整求解,
 * 都没赶上就给降级提示,绝不卡界面(与安卓端同口径)。
 *
 * @param board 当前棋盘(CellState:0 未定 / 1 涂黑 / 2 标记留白)
 */
export function nextHint(clues: number[][], board: number[][]): HintStep {
  const init: number[][] = [];
  for (let r = 0; r < board.length; r++) {
    const row: number[] = [];
    for (let c = 0; c < board[r].length; c++) {
      const v: number = board[r][c];
      if (v === (CellState.FILLED as number)) {
        row.push(1);
      } else if (v === (CellState.MARKED_EMPTY as number)) {
        row.push(0);
      } else {
        row.push(-1);
      }
    }
    init.push(row);
  }

  const budgets: number[] = [HINT_PROPAGATION_BUDGET_MS, HINT_FULL_SEARCH_BUDGET_MS];
  for (let b = 0; b < budgets.length; b++) {
    const solver: PuzzleSolver = new PuzzleSolver(clues, 2, budgets[b], init);
    const solutions: number[][][] = solver.solveAll();
    if (solver.timedOut) {
      continue; // 这一遍没搜完,结论可能漏解,换更大预算
    }
    if (solutions.length === 0) {
      return new HintStep(-1, -1, -1, '当前盘面存在矛盾,建议先「检查错误」再继续');
    }
    const solution: number[][] = solutions[0];
    const forced: number[][] = solver.rootForcedCells;
    for (let i = 0; i < forced.length; i++) {
      const r: number = forced[i][0];
      const c: number = forced[i][1];
      if (board[r][c] !== (CellState.UNDECIDED as number)) {
        continue;
      }
      return solution[r][c] === 1
        ? new HintStep(r, c, 1, `第 ${r + 1} 行 ${c + 1} 列应涂黑`)
        : new HintStep(r, c, 0, `第 ${r + 1} 行 ${c + 1} 列应留白(标 ✕)`);
    }
    for (let r = 0; r < board.length; r++) {
      for (let c = 0; c < board[r].length; c++) {
        if (board[r][c] !== (CellState.UNDECIDED as number)) {
          continue;
        }
        return solution[r][c] === 1
          ? new HintStep(r, c, 1, `第 ${r + 1} 行 ${c + 1} 列应涂黑(推演得出)`)
          : new HintStep(r, c, 0, `第 ${r + 1} 行 ${c + 1} 列应留白(推演得出)`);
      }
    }
    return new HintStep(-1, -1, -1, '盘面已经填满了,点「检查」看看对不对?');
  }
  return new HintStep(-1, -1, -1,
    '这一步暂时推不出来(棋盘较大,计算超时)—— 先随手确定一格再点提示');
}

/** 提示求解第一档预算(ms):只够约束传播,覆盖绝大多数盘面。 */
const HINT_PROPAGATION_BUDGET_MS: number = 300;
/** 提示求解第二档预算(ms):完整求解上限,超时即降级提示。 */
const HINT_FULL_SEARCH_BUDGET_MS: number = 1500;
