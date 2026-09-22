/** AccountStore 用到 preferences 键名(与 Android 版保持一致,便于对照排查)。 */
export class AccountKeys {
  static readonly COMPLETED: string = 'completed_levels';
  static readonly ONLINE_SOLVED: string = 'online_solved';
  static readonly FILL_USED: string = 'fill_quota_used';
  static readonly BONUS_DAY: string = 'last_bonus_day';
}
