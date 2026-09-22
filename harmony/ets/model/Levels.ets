import { LevelSeed, LEVEL_SEEDS } from './GeneratedLevels';
import { Difficulty, Level, difficultyFromName } from './Level';

/**
 * 内置关卡(对应 Android 版 model/Levels.kt)。
 *
 * 数据来自 GeneratedLevels.ets —— 由 tools/gen_harmony_levels.py 从 Android 的
 * Levels.kt / SparseClues.kt 生成,生成时已逐关校验:
 * 图案与 Levels.kt 逐行一致、提示集唯一解、唯一解 = 图案答案。
 *
 * ⚠️ 不要手改 GeneratedLevels.ets:Android 侧改了图案或提示后,重跑
 *    python tools/gen_harmony_levels.py && python tools/sync_harmony.py
 */
let cachedLevels: Level[] | null = null;

function buildAll(): Level[] {
  const list: Level[] = [];
  for (let i = 0; i < LEVEL_SEEDS.length; i++) {
    const seed: LevelSeed = LEVEL_SEEDS[i];
    const positions: Set<string> = new Set<string>();
    for (let k = 0; k < seed.cluePositions.length; k++) {
      positions.add(seed.cluePositions[k]);
    }
    const difficulty: Difficulty = difficultyFromName(seed.difficulty);
    list.push(Level.fromAnswer(seed.id, seed.name, difficulty, seed.answerGrid,
      positions, seed.timedLimitSeconds));
  }
  return list;
}

export class Levels {

  /** 全部内置关,按第 1 关到第 10 关的顺序(首次访问时构造并缓存)。 */
  static get all(): Level[] {
    if (cachedLevels === null) {
      cachedLevels = buildAll();
    }
    return cachedLevels;
  }

  /** 按 id 取内置关(找不到返回 null)。 */
  static byId(id: number): Level | null {
    const list: Level[] = Levels.all;
    for (let i = 0; i < list.length; i++) {
      if (list[i].id === id) {
        return list[i];
      }
    }
    return null;
  }

  /** 关卡总数(供进度/解锁判断使用)。 */
  static count(): number {
    return Levels.all.length;
  }
}
