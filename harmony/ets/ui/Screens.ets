import { GameMode } from '../model/GameMode';
import { Level } from '../model/Level';

/**
 * 页面标识(对应 Android 版 MainActivity 里的 sealed interface Screen)。
 *
 * 鸿蒙版同样走"手写页面栈":单个 @Entry 页面内用数组模拟栈,
 * 各页面以组件形式渲染 —— 与安卓端结构一致,便于双端对照维护。
 */
export enum ScreenKind {
  LOGIN = 0,
  MAIN = 1,
  HALL = 2,
  LEADERBOARD = 3,
  BATTLE_HOME = 4,
  SETTINGS = 5,
  GAME = 6,
}

/** 一个页面条目;GAME 需要带上关卡与模式。 */
export class Screen {
  readonly kind: ScreenKind;
  readonly level: Level | null;
  readonly mode: GameMode;

  constructor(kind: ScreenKind, level: Level | null = null, mode: GameMode = GameMode.FREE) {
    this.kind = kind;
    this.level = level;
    this.mode = mode;
  }
}
