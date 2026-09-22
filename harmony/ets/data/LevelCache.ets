import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { LevelDto } from '../api/Dtos';

/**
 * 在线关卡本地缓存(对应 Android 版 data/LevelCache.kt)。
 *
 * key = "lv_<id>",最多 30 条,按写入序淘汰最旧 —— 断网也能重玩玩过的在线关。
 */
export class LevelCache {
  private static readonly PREFS: string = 'xiangsu_level_cache';
  private static readonly KEY_ORDER: string = 'order';
  private static readonly PREFIX: string = 'lv_';
  private static readonly MAX_ENTRIES: number = 30;

  private static store: preferences.Preferences | null = null;
  /** put 的串行化队列:保证 order 的"读-改-写"不交错(见 put 的说明)。 */
  private static writeChain: Promise<void> = Promise.resolve();

  static async init(context: common.Context): Promise<void> {
    LevelCache.store = await preferences.getPreferences(context, { name: LevelCache.PREFS });
  }

  static async get(id: number): Promise<LevelDto | null> {
    const store: preferences.Preferences | null = LevelCache.store;
    if (store === null) {
      return null;
    }
    try {
      const raw: preferences.ValueType = await store.get(LevelCache.PREFIX + id, '');
      const text: string = typeof raw === 'string' ? raw as string : '';
      if (text.length === 0) {
        return null;
      }
      return JSON.parse(text) as LevelDto;
    } catch (e) {
      return null;
    }
  }

  /**
   * 写入缓存。
   *
   * ⚠ 这段是**跨多个 await 的读-改-写**(读 order → 改 → 写回):两个并发 put
   * (例如首页拉每日一题与题库拉在线关同时发生)会各自基于旧 order 计算再互相覆盖,
   * 丢掉对方的 id —— 被丢的那个永不淘汰,`lv_<id>` 条目无上限堆积,MAX_ENTRIES 形同失效。
   * 所以用一个**串行化队列**把 put 排队执行(队列本身只用到 Promise 链,不需要锁原语)。
   */
  static async put(level: LevelDto): Promise<void> {
    const prev: Promise<void> = LevelCache.writeChain;
    let release: () => void = () => {
    };
    LevelCache.writeChain = new Promise<void>((resolve: () => void) => {
      release = resolve;
    });
    await prev; // 等前一个 put 完成,保证 order 的读改写不交错
    try {
      await LevelCache.putInner(level);
    } finally {
      release();
    }
  }

  private static async putInner(level: LevelDto): Promise<void> {
    const store: preferences.Preferences | null = LevelCache.store;
    if (store === null) {
      return;
    }
    try {
      await store.put(LevelCache.PREFIX + level.id, JSON.stringify(level));
      const orderRaw: preferences.ValueType = await store.get(LevelCache.KEY_ORDER, '');
      const orderText: string = typeof orderRaw === 'string' ? orderRaw as string : '';
      const ids: number[] = [];
      if (orderText.length > 0) {
        const parts: string[] = orderText.split(',');
        for (let i = 0; i < parts.length; i++) {
          const value: number = Number.parseInt(parts[i], 10);
          if (!Number.isNaN(value) && value !== level.id) {
            ids.push(value);
          }
        }
      }
      ids.unshift(level.id);
      while (ids.length > LevelCache.MAX_ENTRIES) {
        const evicted: number = ids.pop() as number;
        await store.delete(LevelCache.PREFIX + evicted);
      }
      await store.put(LevelCache.KEY_ORDER, ids.join(','));
      await store.flush();
    } catch (e) {
      // 缓存写入失败不影响游戏
    }
  }
}
