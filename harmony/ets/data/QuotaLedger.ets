/**
 * 一键补齐额度规则(对应 Android 版 data/QuotaLedger.kt,纯逻辑、可单测)。
 *
 * "每次登录 +2、上限 6"在自动续登后会形同虚设,故改为**每日登录奖励**:
 * 每次(跨天)登录 +LOGIN_BONUS,未用额度可跨日累积,上限 FILL_QUOTA_MAX;
 * 同日重复登录不重复发放(由 lastBonusDay 判断)。
 */
export class DailyGrant {
  readonly usedAfter: number;
  readonly lastBonusDay: string;
  readonly granted: number;

  constructor(usedAfter: number, lastBonusDay: string, granted: number) {
    this.usedAfter = usedAfter;
    this.lastBonusDay = lastBonusDay;
    this.granted = granted;
  }
}

export class QuotaLedger {
  static readonly FILL_QUOTA_MAX: number = 6;
  static readonly LOGIN_BONUS: number = 2;

  /**
   * @param fillUsed 已使用额度(0..MAX)
   * @param lastBonusDay 上次发奖的日历日(空串 = 从未发过)
   * @param today 今天(yyyy-MM-dd)
   */
  static applyDailyBonus(fillUsed: number, lastBonusDay: string, today: string): DailyGrant {
    if (lastBonusDay.length > 0 && lastBonusDay >= today) {
      return new DailyGrant(fillUsed, lastBonusDay, 0); // 同日不重复发
    }
    const room: number = QuotaLedger.FILL_QUOTA_MAX - fillUsed;
    let granted: number = QuotaLedger.LOGIN_BONUS < room ? QuotaLedger.LOGIN_BONUS : room;
    if (granted < 0) {
      granted = 0;
    }
    return new DailyGrant(fillUsed + granted, today, granted);
  }
}
