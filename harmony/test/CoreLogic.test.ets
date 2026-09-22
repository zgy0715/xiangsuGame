import { describe, expect, it } from '@ohos/hypium';
import { CellState, nextCellState } from '../main/ets/model/CellState';
import { GameBoard } from '../main/ets/model/GameBoard';
import { Level, Difficulty } from '../main/ets/model/Level';
import { Levels } from '../main/ets/model/Levels';
import { PuzzleGenerator } from '../main/ets/model/PuzzleGenerator';
import { PuzzleSolver, nextHint } from '../main/ets/model/PuzzleSolver';
import { Validator } from '../main/ets/model/Validator';

/**
 * 核心逻辑单元测试(对应 Android 版 app/src/test/.../CoreLogicTest.kt)。
 *
 * 覆盖:关卡唯一解与答案一致、提示推导、三态循环、通关判定与错误检测、
 * 笔划撤销/重做语义、「提示下一步」。
 * 断言口径与 Android 版保持一致 —— 两端逻辑有任何分叉,这里会先炸。
 */
export default function coreLogicTest() {
  describe('CoreLogicTest', () => {

    // ———— 1. 关卡数据:唯一解 + 解 = 答案 ————
    it('every_demo_level_has_unique_solution_matching_answer', 0, () => {
      const all: Level[] = Levels.all;
      expect(all.length).assertEqual(10);
      for (let i = 0; i < all.length; i++) {
        const level: Level = all[i];
        const solver: PuzzleSolver = new PuzzleSolver(level.clueGrid, 2, 0, null);
        const solutions: number[][][] = solver.solveAll();
        expect(solver.timedOut).assertFalse();
        expect(solutions.length).assertEqual(1);
        const solution: number[][] = solutions[0];
        for (let r = 0; r < level.rows; r++) {
          for (let c = 0; c < level.cols; c++) {
            expect(solution[r][c]).assertEqual(level.answerGrid[r][c]);
          }
        }
      }
    });

    // ———— 2. 提示推导:3×3 窗口计数 ————
    it('clue_is_count_of_filled_cells_in_3x3_window', 0, () => {
      const answer: number[][] = [
        [1, 0, 0],
        [0, 0, 0],
        [0, 0, 1],
      ];
      const clues: number[][] = PuzzleGenerator.cluesFromAnswer(answer, null);
      expect(clues[0][0]).assertEqual(1); // 角格窗口被裁剪,含自身
      expect(clues[1][1]).assertEqual(2); // 中心窗口含两个对角 1
      expect(clues[2][2]).assertEqual(1);
      expect(clues[0][2]).assertEqual(0);
    });

    // 稀疏提示:只有指定位置有数字,其余为 -1
    it('sparse_clues_only_place_numbers_at_given_positions', 0, () => {
      const answer: number[][] = [
        [1, 1],
        [0, 0],
      ];
      const positions: Set<string> = new Set<string>();
      positions.add('0,0');
      const clues: number[][] = PuzzleGenerator.cluesFromAnswer(answer, positions);
      expect(clues[0][0]).assertEqual(2);
      expect(clues[0][1]).assertEqual(-1);
      expect(clues[1][0]).assertEqual(-1);
    });

    // ———— 3. 三态循环 ————
    it('cell_state_cycles_undecided_filled_marked', 0, () => {
      expect(nextCellState(CellState.UNDECIDED)).assertEqual(CellState.FILLED);
      expect(nextCellState(CellState.FILLED)).assertEqual(CellState.MARKED_EMPTY);
      expect(nextCellState(CellState.MARKED_EMPTY)).assertEqual(CellState.UNDECIDED);
    });

    // ———— 4. 通关判定与错误检测 ————
    it('validator_solved_and_wrong_cells', 0, () => {
      const level: Level = Levels.byId(1) as Level;
      const validator: Validator = new Validator(level);
      const board: number[][] = [];
      for (let r = 0; r < level.rows; r++) {
        const row: number[] = [];
        for (let c = 0; c < level.cols; c++) {
          row.push(level.answerGrid[r][c] === 1 ? (CellState.FILLED as number) : 0);
        }
        board.push(row);
      }
      expect(validator.isSolved(board)).assertTrue();
      expect(validator.wrongCells(board).size).assertEqual(0);

      // 把一格涂黑改成标空白 → 立刻判错,且错误格就是它
      board[0][0] = CellState.UNDECIDED as number;
      if (level.answerGrid[0][0] === 1) {
        expect(validator.isSolved(board)).assertFalse();
        expect(validator.wrongCells(board).size).assertEqual(0); // 未决定不算错
      }
      // 明确画错:把答案该留白的格子涂黑
      let wrongRow: number = -1;
      let wrongCol: number = -1;
      for (let r = 0; r < level.rows && wrongRow < 0; r++) {
        for (let c = 0; c < level.cols; c++) {
          if (level.answerGrid[r][c] === 0) {
            wrongRow = r;
            wrongCol = c;
            break;
          }
        }
      }
      expect(wrongRow >= 0).assertTrue();
      board[wrongRow][wrongCol] = CellState.FILLED as number;
      expect(validator.isSolved(board)).assertFalse();
      expect(validator.wrongCells(board).has(wrongRow.toString() + ',' + wrongCol.toString()))
        .assertTrue();
    });

    // 已满足提示:答案填满、且每个窗口都没有"未决定格"时,所有提示都应满足。
    // 注意:satisfiedClues 要求窗口内**没有未决定格**,所以非涂黑格要标成"确定留白"(✕),
    // 而不是留成未确定 —— 这正是玩家"推理完成"的状态。
    it('satisfied_clues_all_met_when_board_matches_answer', 0, () => {
      const level: Level = Levels.byId(1) as Level;
      const validator: Validator = new Validator(level);
      const board: number[][] = [];
      for (let r = 0; r < level.rows; r++) {
        const row: number[] = [];
        for (let c = 0; c < level.cols; c++) {
          row.push(level.answerGrid[r][c] === 1
            ? (CellState.FILLED as number)
            : (CellState.MARKED_EMPTY as number));
        }
        board.push(row);
      }
      const satisfied: Set<string> = validator.satisfiedClues(board);
      let clueCount: number = 0;
      for (let r = 0; r < level.rows; r++) {
        for (let c = 0; c < level.cols; c++) {
          if (level.clueGrid[r][c] >= 0) {
            clueCount++;
          }
        }
      }
      expect(satisfied.size).assertEqual(clueCount);
      expect(validator.wrongCells(board).size).assertEqual(0);
    });

    // ———— 5. 笔划:一条笔划 = 一步撤销 ————
    it('one_stroke_is_one_undo_step', 0, () => {
      const board: GameBoard = new GameBoard(5, 5);
      board.startStroke();
      board.strokeCell(0, 0, CellState.FILLED);
      board.strokeCell(0, 1, CellState.FILLED);
      board.strokeCell(0, 2, CellState.FILLED);
      expect(board.commitStroke()).assertTrue();
      expect(board.board[0][0]).assertEqual(CellState.FILLED as number);
      expect(board.board[0][2]).assertEqual(CellState.FILLED as number);
      expect(board.canUndo).assertTrue();
      // 一次撤销回退整条笔划
      expect(board.undo()).assertTrue();
      expect(board.board[0][0]).assertEqual(CellState.UNDECIDED as number);
      expect(board.board[0][1]).assertEqual(CellState.UNDECIDED as number);
      expect(board.board[0][2]).assertEqual(CellState.UNDECIDED as number);
      expect(board.canUndo).assertFalse();
      // 重做恢复整条笔划
      expect(board.redo()).assertTrue();
      expect(board.board[0][1]).assertEqual(CellState.FILLED as number);
    });

    it('empty_stroke_produces_no_undo_step', 0, () => {
      const board: GameBoard = new GameBoard(3, 3);
      board.startStroke();
      board.strokeCell(1, 1, CellState.UNDECIDED); // 本来就是未确定 → 无改动
      expect(board.commitStroke()).assertFalse();
      expect(board.canUndo).assertFalse();
    });

    it('cancel_stroke_rolls_back_and_new_move_clears_redo', 0, () => {
      const board: GameBoard = new GameBoard(3, 3);
      board.startStroke();
      board.strokeCell(0, 0, CellState.FILLED);
      board.strokeCell(0, 1, CellState.FILLED);
      board.cancelStroke();
      expect(board.board[0][0]).assertEqual(CellState.UNDECIDED as number);
      expect(board.board[0][1]).assertEqual(CellState.UNDECIDED as number);

      board.paintCell(1, 1, CellState.FILLED);
      board.undo();
      expect(board.canRedo).assertTrue();
      board.paintCell(2, 2, CellState.MARKED_EMPTY); // 新操作 → 重做栈清空
      expect(board.canRedo).assertFalse();
    });

    it('apply_solution_is_one_step_and_reset_clears_history', 0, () => {
      const level: Level = Levels.byId(2) as Level;
      const board: GameBoard = new GameBoard(level.rows, level.cols);
      board.paintCell(0, 0, CellState.FILLED);
      expect(board.applySolution(level.answerGrid)).assertTrue();
      const validator: Validator = new Validator(level);
      expect(validator.isSolved(board.board)).assertTrue();
      board.undo(); // 一键补齐可一步撤销
      expect(validator.isSolved(board.board)).assertFalse();
      board.reset();
      expect(board.canUndo).assertFalse();
      expect(board.canRedo).assertFalse();
    });

    // ———— 6. 提示下一步 ————
    it('next_hint_on_empty_board_points_to_answer_cell', 0, () => {
      const level: Level = Levels.byId(1) as Level;
      const board: GameBoard = new GameBoard(level.rows, level.cols);
      const hint = nextHint(level.clueGrid, board.board);
      expect(hint.hasCell).assertTrue();
      expect(hint.row >= 0 && hint.row < level.rows).assertTrue();
      expect(hint.col >= 0 && hint.col < level.cols).assertTrue();
      // 提示必须与答案一致(涂黑提示指向答案 1,留白提示指向答案 0)
      expect(level.answerGrid[hint.row][hint.col]).assertEqual(hint.target);
    });

    it('next_hint_reports_contradiction_when_board_conflicts', 0, () => {
      const level: Level = Levels.byId(1) as Level;
      const board: GameBoard = new GameBoard(level.rows, level.cols);
      // 全盘涂黑:与线索必然矛盾
      for (let r = 0; r < level.rows; r++) {
        for (let c = 0; c < level.cols; c++) {
          board.board[r][c] = CellState.FILLED as number;
        }
      }
      const hint = nextHint(level.clueGrid, board.board);
      expect(hint.hasCell).assertFalse();
    });

    // ———— 7. 关卡数据完整性 ————
    it('level_metadata_is_consistent', 0, () => {
      const all: Level[] = Levels.all;
      for (let i = 0; i < all.length; i++) {
        const level: Level = all[i];
        expect(level.id).assertEqual(i + 1);          // id 连续 1..10
        expect(level.clueGrid.length).assertEqual(level.rows);
        expect(level.answerGrid.length).assertEqual(level.rows);
        expect(level.timedLimitSeconds > 0).assertTrue();
        for (let r = 0; r < level.rows; r++) {
          expect(level.clueGrid[r].length).assertEqual(level.cols);
          expect(level.answerGrid[r].length).assertEqual(level.cols);
        }
      }
      expect(Levels.byId(999)).assertNull();
      expect(Levels.byId(4)?.name).assertEqual('苹果');
      expect((Levels.byId(4) as Level).difficulty).assertEqual(Difficulty.MEDIUM);
    });
  });
}
