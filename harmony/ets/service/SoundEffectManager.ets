import { common } from '@kit.AbilityKit';
import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { LocalSettings } from '../data/LocalSettings';

/**
 * UI 音效(对应 Android 版 service/SoundEffectManager.kt):点击 / 通关 / 检查出错。
 *
 * 用 HarmonyOS 的 SoundPool 播放短音效(低延迟,适合按钮反馈)。
 * 注意:工厂方法在 media 上(media.createSoundPool),而不是独立的 soundPool 模块。
 * 资源放在 resources/rawfile/(与安卓端同一批 wav,由 sync_harmony.py 同步)。
 */
export class SoundEffectManager {
  private static pool: media.SoundPool | null = null;
  private static ids: Map<string, number> = new Map<string, number>();
  private static initialized: boolean = false;

  static async init(context: common.Context): Promise<void> {
    if (SoundEffectManager.initialized) {
      return;
    }
    try {
      const rendererInfo: audio.AudioRendererInfo = {
        usage: audio.StreamUsage.STREAM_USAGE_GAME,
        rendererFlags: 0,
      };
      const pool: media.SoundPool = await media.createSoundPool(4, rendererInfo);
      SoundEffectManager.pool = pool;
      await SoundEffectManager.load(context, 'click');
      await SoundEffectManager.load(context, 'win');
      await SoundEffectManager.load(context, 'error');
      // ⚠ 只在**真的成功**之后才置位:以前先置 true 再 try,一次瞬时失败
      //   (音频服务忙/资源缺失)就再也不能重试,整个会话都没声音。
      SoundEffectManager.initialized = true;
    } catch (e) {
      // 音频不可用(设备占用/资源缺失)时静默降级,绝不影响游戏;下次 init 可重试
      SoundEffectManager.pool = null;
      SoundEffectManager.initialized = false;
    }
  }

  private static async load(context: common.Context, name: string): Promise<void> {
    const pool: media.SoundPool | null = SoundEffectManager.pool;
    if (pool === null) {
      return;
    }
    try {
      const fd = await context.resourceManager.getRawFd(`${name}.wav`);
      const id: number = await pool.load(fd.fd, fd.offset, fd.length);
      SoundEffectManager.ids.set(name, id);
      // 载入完就关掉 fd,否则 3 个 wav 的描述符会一直占到进程结束
      try {
        context.resourceManager.closeRawFd(`${name}.wav`);
      } catch (e) {
        // 个别版本不支持 closeRawFd:忽略
      }
    } catch (e) {
      // 单个音效缺失不影响其他音效
    }
  }

  static click(): void {
    SoundEffectManager.play('click');
  }

  static win(): void {
    SoundEffectManager.play('win');
  }

  static error(): void {
    SoundEffectManager.play('error');
  }

  private static play(name: string): void {
    if (!LocalSettings.soundEffectsEnabled()) {
      return;
    }
    const pool: media.SoundPool | null = SoundEffectManager.pool;
    const id: number | undefined = SoundEffectManager.ids.get(name);
    if (pool === null || id === undefined) {
      return;
    }
    try {
      pool.play(id, { loop: 0, rate: 1 });
    } catch (e) {
      // 播放失败不抛出
    }
  }

  /** 释放(进程退出前;正常运行期间不需要调用)。 */
  static async release(): Promise<void> {
    const pool: media.SoundPool | null = SoundEffectManager.pool;
    SoundEffectManager.pool = null;
    SoundEffectManager.ids.clear();
    SoundEffectManager.initialized = false;
    if (pool !== null) {
      try {
        await pool.release();
      } catch (e) {
        // 忽略
      }
    }
  }
}
