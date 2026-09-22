import { Level } from '../model/Level';
import { Levels } from '../model/Levels';
import { Screen, ScreenKind } from './Screens';

/**
 * 页面导航的**纯逻辑**(与鸿蒙 pages/Index.ets 配合;对应 Android 版 ui/ScreenStack.kt)。
 *
 * 为什么单独抽出来:鸿蒙端踩过"点下一关没反应"的坑 —— 根因是切关时**替换栈顶**
 * (栈长度不变 → ArkUI 复用同一个 GameScreen 实例 → 新关卡的棋盘没重建)。
 * 这类问题以前只能靠真机手点发现;抽成纯函数后就能**被单测覆盖**:
 *   · 下一关必须走 push(栈变长 → 组件重建);
 *   · 已是最后一关时不能留下"点了没反应"的死按钮;
 *   · 非法/未知关卡不能抛异常或给出错误目标。
 *
 * UI 层只负责"把 current 渲染出来 + 把回调转发过来"。
 */
export class ScreenStack {
  /**
   * 计算"下一关"的目标页面。
   *
   * 返回 null 表示**没有下一关**(已是最后一关,或当前是在线关 id < 0)——
   * 调用方据此隐藏「下一关」按钮或给一句提示,而不是留个死按钮。
   */
  static nextLevelScreen(current: Screen): Screen | null {
    const level: Level | null = current.level;
    if (level === null || level.id <= 0) {
      return null; // 在线关(id < 0)没有顺序下一关
    }
    const all: Level[] = Levels.all;
    for (let i = 0; i < all.length; i++) {
      if (all[i].id === level.id) {
        if (i + 1 < all.length) {
          // 保持当前模式(自由/限时),与新关卡一起入栈
          return new Screen(ScreenKind.GAME, all[i + 1], current.mode);
        }
        return null; // 已是最后一关
      }
    }
    return null; // 不在内置关表里
  }

  /** 当前关是否还有下一关(UI 用它决定按钮显不显示)。 */
  static hasNextLevel(current: Screen): boolean {
    return ScreenStack.nextLevelScreen(current) !== null;
  }

  /**
   * 从关卡列表里按 id 找关卡;找不到返回 null。
   * 抽出来是因为"下一关/重玩"都要用它,以前散在各处容易写出不一致的查找逻辑。
   */
  static levelById(id: number): Level | null {
    return Levels.byId(id);
  }
}
