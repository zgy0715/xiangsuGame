/**
 * 方格三态(对应 Android 版 model/CellState.kt)。
 *
 *  0 = 未确定(UNDECIDED)   —— 初始空白,尚未推理;
 *  1 = 已涂黑(FILLED)      —— 玩家判断这里应该是像素画的一部分;
 *  2 = 标记为空白(MARKED_EMPTY) —— 玩家确定这里不涂黑(打叉)。
 *
 * 状态以 number 存储,棋盘用 number[][] 紧凑承载,快速读写。
 * ArkTS 的 enum 不能带方法,所以 value 转换与"循环切换"写成下面两个自由函数,
 * 语义与 Kotlin 版完全一致。
 */
export enum CellState {
  UNDECIDED = 0,
  FILLED = 1,
  MARKED_EMPTY = 2,
}

/** 点击循环切换:空白 → 涂黑 → 标记空白 → 空白。 */
export function nextCellState(state: CellState): CellState {
  if (state === CellState.UNDECIDED) {
    return CellState.FILLED;
  }
  if (state === CellState.FILLED) {
    return CellState.MARKED_EMPTY;
  }
  return CellState.UNDECIDED;
}

/** 由 number 值还原枚举;未识别值一律归为 UNDECIDED(宽容处理脏数据)。 */
export function cellStateFromValue(value: number): CellState {
  if (value === 1) {
    return CellState.FILLED;
  }
  if (value === 2) {
    return CellState.MARKED_EMPTY;
  }
  return CellState.UNDECIDED;
}

/** 坐标 → Set 键("行,列")。提示坐标、错误格集合统一用它,与生成脚本输出格式一致。 */
export function cellKey(row: number, col: number): string {
  return row.toString() + ',' + col.toString();
}
