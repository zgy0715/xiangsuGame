import { common } from '@kit.AbilityKit';
import { media } from '@kit.MediaKit';
import { LocalSettings } from '../data/LocalSettings';

/**
 * 背景音乐(对应 Android 版 service/BackgroundMusicManager.kt):
 * 循环播放 resources/rawfile/bgm.mp3(与安卓端同一个素材文件)。
 *
 * 生命周期约定(与安卓一致):前台播放 / 退到后台暂停,不释放播放器;
 * 开关关闭时立即暂停。所有操作都 try/catch —— 放不出音乐绝不影响游戏。
 */
export class BackgroundMusicManager {
  private static player: media.AVPlayer | null = null;
  private static prepared: boolean = false;
  private static startFailed: boolean = false;
  private static pendingPlay: boolean = false;

  /** 进入前台:允许播放则开始/续播。 */
  static async start(context: common.Context): Promise<void> {
    if (!LocalSettings.backgroundMusicEnabled()) {
      return;
    }
    if (BackgroundMusicManager.player !== null) {
      await BackgroundMusicManager.resume();
      return;
    }
    if (BackgroundMusicManager.startFailed) {
      return;
    }
    try {
      await BackgroundMusicManager.create(context);
    } catch (e) {
      // create 里可能已经把 AVPlayer 建出来了(原生实例会占音频焦点):
      // 失败时必须显式释放,不能只把引用置 null 了事。
      await BackgroundMusicManager.release();
      BackgroundMusicManager.startFailed = true;
    }
  }

  private static async create(context: common.Context): Promise<void> {
    const player: media.AVPlayer = await media.createAVPlayer();
    BackgroundMusicManager.player = player;
    BackgroundMusicManager.pendingPlay = true;

    player.on('stateChange', (state: string) => {
      if (state === 'initialized') {
        player.prepare();
      } else if (state === 'prepared') {
        BackgroundMusicManager.prepared = true;
        player.loop = true;
        if (BackgroundMusicManager.pendingPlay
          && LocalSettings.backgroundMusicEnabled()) {
          player.play();
        }
      } else if (state === 'error') {
        BackgroundMusicManager.startFailed = true;
      }
    });

    const fd = await context.resourceManager.getRawFd('bgm.mp3');
    player.fdSrc = { fd: fd.fd, offset: fd.offset, length: fd.length };
    // 交给播放器后即可关闭 fd(播放器已持有自己的引用)
    try {
      context.resourceManager.closeRawFd('bgm.mp3');
    } catch (e) {
      // 个别版本不支持:忽略
    }
  }

  private static async resume(): Promise<void> {
    const player: media.AVPlayer | null = BackgroundMusicManager.player;
    if (player === null) {
      return;
    }
    if (!BackgroundMusicManager.prepared) {
      BackgroundMusicManager.pendingPlay = true;
      return;
    }
    BackgroundMusicManager.pendingPlay = true;
    try {
      player.play();
    } catch (e) {
      // 忽略
    }
  }

  /** 退到后台:暂停但保留进度,回来接着放。 */
  static pause(): void {
    BackgroundMusicManager.pendingPlay = false;
    const player: media.AVPlayer | null = BackgroundMusicManager.player;
    if (player === null || !BackgroundMusicManager.prepared) {
      return;
    }
    try {
      player.pause();
    } catch (e) {
      // 忽略
    }
  }

  /** 设置页切换开关:开则立即播放,关则立即暂停。 */
  static async onEnabledChanged(context: common.Context, enabled: boolean): Promise<void> {
    if (enabled) {
      await BackgroundMusicManager.start(context);
    } else {
      BackgroundMusicManager.pause();
    }
  }

  /** 释放播放器(进程退出前 / create 失败时清理)。 */
  static async release(): Promise<void> {
    const player: media.AVPlayer | null = BackgroundMusicManager.player;
    BackgroundMusicManager.player = null;
    BackgroundMusicManager.prepared = false;
    // ⚠ 这两个状态也必须复位:以前 release 后 startFailed 仍为 true,
    //   导致后续 start() 直接 return,音乐再也起不来(安卓端有复位,鸿蒙漏了)。
    BackgroundMusicManager.startFailed = false;
    BackgroundMusicManager.pendingPlay = false;
    if (player !== null) {
      try {
        await player.release();
      } catch (e) {
        // 忽略
      }
    }
  }
}
