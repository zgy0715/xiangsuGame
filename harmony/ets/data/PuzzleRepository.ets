import { BankResponse, DailyLevelResponse, LevelDto } from '../api/Dtos';
import { GameApi } from '../api/GameApi';
import { levelFromDto } from '../api/LevelMapper';
import { AuthManager } from '../auth/AuthManager';
import { Level } from '../model/Level';
import { LevelCache } from './LevelCache';

/**
 * 题库 / 每日一题的取数入口(对应 Android 版 data/PuzzleRepository.kt)。
 *
 * 统一走这里的好处:取到的在线关顺手写本地缓存,断网时还能重玩;
 * 每日一题**不传日期** —— 由服务端按东八区决定"今天",保证全服同题。
 */
export class PuzzleRepository {

  /** 每日一题(在线;拿到后写缓存)。 */
  static async fetchDaily(): Promise<DailyLevelResponse> {
    const resp: DailyLevelResponse = await AuthManager.api().daily(null);
    await LevelCache.put(resp.level);
    return resp;
  }

  /** 题库分页(难度可为 null = 全部)。 */
  static async fetchBank(difficulty: string | null, offset: number,
                         limit: number): Promise<BankResponse> {
    return AuthManager.api().bank(difficulty, offset, limit);
  }

  /** 按 id 取整题:先查缓存,未命中再请求并写回缓存。 */
  static async fetchLevel(id: number): Promise<Level> {
    const cached: LevelDto | null = await LevelCache.get(id);
    if (cached !== null) {
      return levelFromDto(cached);
    }
    const dto: LevelDto = await AuthManager.api().puzzle(id);
    await LevelCache.put(dto);
    return levelFromDto(dto);
  }
}
