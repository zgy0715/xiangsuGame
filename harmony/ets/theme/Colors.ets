/**
 * 主题配色 —— "深夜画室"风(对应 Android 版 ui/theme/Color.kt,色值逐一相同)。
 *
 * 深靛夜幕做底(沉浸、聚焦),棋盘是一张被聚光照亮的画纸;
 * 品牌靛紫 + 湖水青 + 暖金三色点缀,卡片走玻璃质感。
 * 游戏恒定深色观感,不随系统亮暗切换。
 */
export class Colors {
  // —— 品牌三主色(深色底上直接可用的高亮度取值)——
  static readonly Coral: string = '#8B7CFF';          // 品牌靛紫(亮):主操作、强调
  static readonly CoralContainer: string = '#3C2FA0'; // 主色容器:深紫底
  static readonly CoralDeep: string = '#5E4FD1';      // 品牌紫的渐变深端
  static readonly GlowPurple: string = '#B06CF7';     // 品牌紫的渐变亮端 / 光晕色

  static readonly Cyan: string = '#5EDDD8';           // 湖水青(亮):次操作、正向反馈
  static readonly CyanContainer: string = '#00514E';

  static readonly Amber: string = '#FFC85C';          // 暖金:星级、榜首
  static readonly AmberContainer: string = '#574100';

  // —— 通用语义色(深夜画室基底)——
  static readonly Cream: string = '#100E1C';          // 页面背景:深靛夜幕
  static readonly SurfaceWhite: string = '#1B1929';   // 卡片 / 面板:夜表面
  static readonly Sand: string = '#2A2740';           // surfaceVariant / 进度轨道:深紫灰
  static readonly Cocoa: string = '#B9B4D6';          // 次要文字:柔紫灰
  static readonly Ink: string = '#F0EFFB';            // 主文字:夜光白

  /** 通关/错误提示用的红。 */
  static readonly Danger: string = '#E53935';
  /** 半透明白(描边/玻璃感)。 */
  static readonly GlassLine: string = '#33FFFFFF';
  static readonly GlassFill: string = '#1FFFFFFF';
}

/** Hero 渐变(登录页/首页头卡)。ArkUI 的 linearGradient 要 [颜色, 位置] 元组数组。 */
export const HERO_GRADIENT: Array<[string, number]> = [
  ['#7A68F2', 0.0],
  ['#5647C9', 0.55],
  ['#3B2E8F', 1.0],
];

/** 页面背景渐变(上浅下深的夜幕)。 */
export const PAGE_GRADIENT: Array<[string, number]> = [
  ['#171531', 0.0],
  ['#100E1C', 1.0],
];

/** 主按钮渐变。 */
export const PRIMARY_GRADIENT: Array<[string, number]> = [
  ['#7A68F2', 0.0],
  ['#5647C9', 1.0],
];
