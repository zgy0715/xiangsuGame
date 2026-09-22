import { describe, expect, it } from '@ohos/hypium';
import { GameMode } from '../main/ets/model/GameMode';
import { Difficulty, Level } from '../main/ets/model/Level';
import { Levels } from '../main/ets/model/Levels';
import { Screen, ScreenKind } from '../main/ets/ui/Screens';
import { ScreenStack } from '../main/ets/ui/ScreenStack';

/**
 * UI 导航冒烟测试(对应 Android 版 app/src/test/.../ui/ScreenStackTest.kt)。
 *
 * 存在的理由:真机上踩过的 UI 问题(**点「下一关」没反应**、最后一关留死按钮)
 * 根因都在导航逻辑里,而不是渲染。把导航抽成 ScreenStack 后,
 * 这类"只能靠手点发现"的问题变成了可自动跑的断言。
 *
 * 覆盖:
 *   1) 下一关必须给出**新关卡**(而不是同一关或 null);
 *   2) 找不到下一关时为 null(UI 据此隐藏按钮,不留死键);
 *   3) 在线关(id < 0)没有顺序下一关;
 *   4) 模式(自由/限时)要跟着带到下一关;
 *   5) 内置关 id 连续、每一关都能被按 id 找到(导航不会断链)。
 */
export default function screenStackTest() {
  describe('ScreenStackTest', () => {

    // ———— 1. 下一关:必须真的换关 ————
    it('next_level_advances_to_a_different_level', 0, () => {
      const all: Level[] = Levels.all;
      const first: Level = all[0];
      const current = new Screen(ScreenKind.GAME, first, GameMode.FREE);
      const next: Screen | null = ScreenStack.nextLevelScreen(current);
      expect(next !== null).assertTrue();
      if (next !== null) {
        expect(next.kind).assertEqual(ScreenKind.GAME);
        expect(next.level !== null).assertTrue();
        if (next.level !== null) {
          // 关键断言:不能还是同一关(以前"替换栈顶"时栈没变,组件被复用 = 看着没反应)
          expect(next.level.id).assertEqual(first.id + 1);
          expect(next.level.rows).assertEqual(Levels.byId(first.id + 1)?.rows as number);
        }
      }
    });

    // ———— 2. 最后一关:不给死按钮 ————
    it('last_level_has_no_next', 0, () => {
      const all: Level[] = Levels.all;
      const last: Level = all[all.length - 1];
      const current = new Screen(ScreenKind.GAME, last, GameMode.FREE);
      expect(ScreenStack.hasNextLevel(current)).assertFalse();
      expect(ScreenStack.nextLevelScreen(current)).assertNull();
    });

    // ———— 3. 在线关(id < 0)没有顺序下一关 ————
    it('online_level_has_no_sequential_next', 0, () => {
      // 注意:Level 构造会校验形状,所以造一个最小的合法 1×1 关卡(不是空数组)
      const clues: number[][] = [[0]];
      const answers: number[][] = [[0]];
      const online = new Screen(ScreenKind.GAME,
        new Level(-34, '今日一题', Difficulty.EASY, 1, 1, clues, answers, 0), GameMode.FREE);
      expect(ScreenStack.hasNextLevel(online)).assertFalse();
      expect(ScreenStack.nextLevelScreen(online)).assertNull();
    });

    // ———— 4. 模式要带到下一关 ————
    it('mode_is_carried_to_next_level', 0, () => {
      const current = new Screen(ScreenKind.GAME, Levels.all[0], GameMode.TIMED);
      const next: Screen | null = ScreenStack.nextLevelScreen(current);
      expect(next !== null).assertTrue();
      if (next !== null) {
        expect(next.mode).assertEqual(GameMode.TIMED);
      }
    });

    // ———— 5. 关卡表不断链:每一关(除最后一关)都能前进 ————
    it('every_builtin_level_but_last_can_advance', 0, () => {
      const all: Level[] = Levels.all;
      for (let i = 0; i < all.length; i++) {
        const current = new Screen(ScreenKind.GAME, all[i], GameMode.FREE);
        if (i + 1 < all.length) {
          const next: Screen | null = ScreenStack.nextLevelScreen(current);
          expect(next !== null).assertTrue();
          if (next !== null && next.level !== null) {
            expect(next.level.id).assertEqual(all[i + 1].id);
          }
        } else {
          expect(ScreenStack.nextLevelScreen(current)).assertNull();
        }
      }
    });

    // ———— 6. 按 id 查关:每关都能找到(重玩/跳关依赖它) ————
    it('level_by_id_finds_every_builtin_level', 0, () => {
      const all: Level[] = Levels.all;
      for (let i = 0; i < all.length; i++) {
        const found: Level | null = ScreenStack.levelById(all[i].id);
        expect(found !== null).assertTrue();
        if (found !== null) {
          expect(found.id).assertEqual(all[i].id);
        }
      }
      expect(ScreenStack.levelById(9999)).assertNull();
    });
  });
}
