/**
 * 日期工具(对应 Android 版 data/Dates.ets)。
 *
 * ⚠️ 鸿蒙版**不再把设备本地日期发给服务端**:每日一题的日历日由服务端按东八区判定
 * (接口只接受今天 ±1 天)。设备时区不同会导致"同一天拿到不同题目",破坏"全服同题"。
 * 这里只提供"本地展示用"的日期串。
 */
export class Dates {

  /** 本地日期 yyyy-MM-dd(仅用于界面展示)。 */
  static todayIso(): string {
    const now: Date = new Date();
    const year: number = now.getFullYear();
    const month: number = now.getMonth() + 1;
    const day: number = now.getDate();
    return `${year}-${Dates.pad2(month)}-${Dates.pad2(day)}`;
  }

  private static pad2(value: number): string {
    return value < 10 ? `0${value}` : `${value}`;
  }
}
