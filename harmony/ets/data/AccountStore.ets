import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { AccountKeys } from './AccountKeys';
import { Dates } from './Dates';
import { QuotaLedger } from './QuotaLedger';

/**
 * 账号进度(对应 Android 版 data/AccountStore.kt)。
 *
 * - 内置关(正 id):通关集合 + 顺序解锁;登录奖励为"每日 +2"(见 QuotaLedger);
 * - 在线关(负 id):永不进解锁链,markCompleted 对非正 id 直接忽略;
 * - 同设备多账号互不干扰(preferences 名带 userId)。
 *
 * 鸿蒙的 preferences 是异步的,所以用 init() 先加载;数值类状态由 UI 每次进页面时读取
 * (页面数量少、数据量小,不需要额外做观察者)。
 */
export class AccountStore {
  readonly userId: number;

  private store: preferences.Preferences | null = null;
  private completed: Set<number> = new Set<number>();
  private onlineSolvedSet: Set<number> = new Set<number>();
  private fillUsed: number = 0;
  private bonusDay: string = '';

  constructor(userId: number) {
    this.userId = userId;
  }

  async init(context: common.Context): Promise<void> {
    this.store = await preferences.getPreferences(context, { name: `xiangsu_account_${this.userId}` });
    this.completed = await this.readIdSet(AccountKeys.COMPLETED, true);
    this.onlineSolvedSet = await this.readIdSet(AccountKeys.ONLINE_SOLVED, false);
    this.fillUsed = await this.readNumber(AccountKeys.FILL_USED, 0);
    this.bonusDay = await this.readString(AccountKeys.BONUS_DAY, '');
  }

  get completedLevels(): Set<number> {
    return this.completed;
  }

  /** 已通关的在线关 id(负 id;仅"玩过"标记,不参与解锁)。 */
  get onlineSolved(): Set<number> {
    return this.onlineSolvedSet;
  }

  get fillQuotaUsed(): number {
    return this.fillUsed;
  }

  get fillQuotaRemaining(): number {
    return QuotaLedger.FILL_QUOTA_MAX - this.fillUsed;
  }

  get lastBonusDay(): string {
    return this.bonusDay;
  }

  /** 顺序解锁:第 1 关恒解锁,其余要求前一关已通关。 */
  isUnlocked(levelId: number): boolean {
    return levelId > 0 && (levelId === 1 || this.completed.has(levelId - 1));
  }

  /** 标记内置关通关(幂等);在线关(负 id)直接忽略。 */
  async markCompleted(levelId: number): Promise<void> {
    if (levelId <= 0 || this.completed.has(levelId)) {
      return;
    }
    this.completed.add(levelId);
    await this.writeIdSet(AccountKeys.COMPLETED, this.completed);
  }

  /** 在线关通关标记(负 id;不参与解锁链)。 */
  async recordOnlineSolved(onlineId: number): Promise<void> {
    if (onlineId >= 0 || this.onlineSolvedSet.has(onlineId)) {
      return;
    }
    this.onlineSolvedSet.add(onlineId);
    await this.writeIdSet(AccountKeys.ONLINE_SOLVED, this.onlineSolvedSet);
  }

  /** 清洗掉已不存在的关卡 id(关卡表调整后留下的孤儿记录)。 */
  async pruneCompleted(validLevelIds: Set<number>): Promise<void> {
    let changed: boolean = false;
    const kept: Set<number> = new Set<number>();
    this.completed.forEach((id: number) => {
      if (validLevelIds.has(id)) {
        kept.add(id);
      } else {
        changed = true;
      }
    });
    if (!changed) {
      return;
    }
    this.completed = kept;
    await this.writeIdSet(AccountKeys.COMPLETED, this.completed);
  }

  /**
   * 每日登录奖励:跨天才 +2,同日幂等。返回"本次是否真的发了奖励"。
   * 无论有没有真发放,都要把"最近结算日"落库(额度满时 granted=0,
   * 旧写法直接 return 会让结算日永远停在旧日期)。
   */
  async grantDailyBonusIfNewDay(): Promise<boolean> {
    const grant = QuotaLedger.applyDailyBonus(this.fillUsed, this.bonusDay, Dates.todayIso());
    const stateChanged: boolean = grant.usedAfter !== this.fillUsed
      || grant.lastBonusDay !== this.bonusDay;
    if (!stateChanged) {
      return false;
    }
    this.fillUsed = grant.usedAfter;
    this.bonusDay = grant.lastBonusDay;
    await this.writeNumber(AccountKeys.FILL_USED, this.fillUsed);
    await this.writeString(AccountKeys.BONUS_DAY, this.bonusDay);
    return grant.granted > 0;
  }

  /** 消耗一次一键补齐额度;返回是否成功。 */
  async useFill(): Promise<boolean> {
    if (this.fillQuotaRemaining <= 0) {
      return false;
    }
    this.fillUsed += 1;
    await this.writeNumber(AccountKeys.FILL_USED, this.fillUsed);
    return true;
  }

  // ---------------- 内部读写 ----------------

  private async readIdSet(key: string, positiveOnly: boolean): Promise<Set<number>> {
    const raw: string = await this.readString(key, '');
    const result: Set<number> = new Set<number>();
    if (raw.length === 0) {
      return result;
    }
    const parts: string[] = raw.split(',');
    for (let i = 0; i < parts.length; i++) {
      const value: number = Number.parseInt(parts[i], 10);
      if (Number.isNaN(value)) {
        continue;
      }
      if (positiveOnly ? value > 0 : value < 0) {
        result.add(value);
      }
    }
    return result;
  }

  private async writeIdSet(key: string, ids: Set<number>): Promise<void> {
    const parts: string[] = [];
    ids.forEach((id: number) => {
      parts.push(id.toString());
    });
    await this.writeString(key, parts.join(','));
  }

  private async readString(key: string, def: string): Promise<string> {
    const store: preferences.Preferences | null = this.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    return typeof value === 'string' ? value as string : def;
  }

  private async readNumber(key: string, def: number): Promise<number> {
    const store: preferences.Preferences | null = this.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    if (typeof value !== 'number') {
      return def;
    }
    const num: number = value as number;
    if (num < 0) {
      return 0;
    }
    return num > QuotaLedger.FILL_QUOTA_MAX ? QuotaLedger.FILL_QUOTA_MAX : num;
  }

  private async writeString(key: string, value: string): Promise<void> {
    const store: preferences.Preferences | null = this.store;
    if (store === null) {
      return;
    }
    await store.put(key, value);
    await store.flush();
  }

  private async writeNumber(key: string, value: number): Promise<void> {
    const store: preferences.Preferences | null = this.store;
    if (store === null) {
      return;
    }
    await store.put(key, value);
    await store.flush();
  }
}
