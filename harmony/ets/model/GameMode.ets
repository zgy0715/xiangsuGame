/** 游戏模式(对应 Android 版 model/GameMode.kt)。 */
export enum GameMode {
  /** 自由模式:不计时,随时可以慢慢推理。 */
  FREE = 0,
  /** 限时模式:超过关卡时限判失败。 */
  TIMED = 1,
}
