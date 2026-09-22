import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

/**
 * 本机设置(对应 Android 版 data/LocalSettings.ets):服务器地址 + 音效/背景音乐/摇一摇/演示解锁。
 *
 * 存在 preferences("xiangsu_settings"),同时镜像到 AppStorage,
 * 这样 UI 里用 @StorageProp / @StorageLink 就能直接绑定,不必手动刷新。
 */
export class LocalSettings {

  /**
   * 演示默认:开发电脑的局域网地址。
   *
   * ⚠ 鸿蒙模拟器/真机**都不能**用 `10.0.2.2`(那是安卓模拟器的宿主机别名)
   * 或 `localhost`(指模拟器自己)—— 必须填电脑在局域网里的真实 IPv4。
   * 换电脑/换 Wi-Fi 后 IP 会变,用 `ipconfig` 查到新 IP 后在设置页改掉即可;
   * 登录页的「服务器设置」里有「测试连接」按钮可以先验证再登录。
   */
  static readonly DEFAULT_SERVER_URL: string = 'http://192.168.43.50:8000/';

  private static store: preferences.Preferences | null = null;

  static async init(context: common.Context): Promise<void> {
    LocalSettings.store = await preferences.getPreferences(context, { name: 'xiangsu_settings' });
    const url: string = await LocalSettings.readString(K_SERVER, LocalSettings.DEFAULT_SERVER_URL);
    AppStorage.setOrCreate<string>(K_SERVER, url);
    AppStorage.setOrCreate<boolean>(K_DEMO_UNLOCK, await LocalSettings.readBool(K_DEMO_UNLOCK, false));
    AppStorage.setOrCreate<boolean>(K_SOUND, await LocalSettings.readBool(K_SOUND, true));
    AppStorage.setOrCreate<boolean>(K_BGM, await LocalSettings.readBool(K_BGM, true));
    AppStorage.setOrCreate<boolean>(K_SHAKE, await LocalSettings.readBool(K_SHAKE, true));
  }

  static serverUrl(): string {
    return AppStorage.get<string>(K_SERVER) ?? LocalSettings.DEFAULT_SERVER_URL;
  }

  /**
   * 服务器地址归一化 —— **唯一入口**,testConnection / saveServerUrl / HttpClient
   * 三处必须共用同一规则。
   *
   * 以前"测试连接"会补 `http://`、而"保存"不补,于是填 `192.168.1.5:8000` 时:
   * 测试通过 ✅ → 保存 → 之后所有请求用无协议 URL → 全部"无法连接服务器"。
   *
   * 规则:去空白 → 空串回默认值 → 缺协议补 `http://` → 补结尾 `/`。
   */
  static normalizeServerUrl(url: string): string {
    let normalized: string = (url ?? '').trim();
    if (normalized.length === 0) {
      return LocalSettings.DEFAULT_SERVER_URL;
    }
    if (!normalized.startsWith('http://') && !normalized.startsWith('https://')) {
      normalized = `http://${normalized}`;
    }
    if (!normalized.endsWith('/')) {
      normalized += '/';
    }
    return normalized;
  }

  static async saveServerUrl(url: string): Promise<void> {
    const normalized: string = LocalSettings.normalizeServerUrl(url);
    AppStorage.setOrCreate<string>(K_SERVER, normalized);
    await LocalSettings.write(K_SERVER, normalized);
  }

  static demoAllUnlocked(): boolean {
    return AppStorage.get<boolean>(K_DEMO_UNLOCK) ?? false;
  }

  static async saveDemoAllUnlocked(enabled: boolean): Promise<void> {
    AppStorage.setOrCreate<boolean>(K_DEMO_UNLOCK, enabled);
    await LocalSettings.write(K_DEMO_UNLOCK, enabled);
  }

  static soundEffectsEnabled(): boolean {
    return AppStorage.get<boolean>(K_SOUND) ?? true;
  }

  static async saveSoundEffectsEnabled(enabled: boolean): Promise<void> {
    AppStorage.setOrCreate<boolean>(K_SOUND, enabled);
    await LocalSettings.write(K_SOUND, enabled);
  }

  static backgroundMusicEnabled(): boolean {
    return AppStorage.get<boolean>(K_BGM) ?? true;
  }

  static async saveBackgroundMusicEnabled(enabled: boolean): Promise<void> {
    AppStorage.setOrCreate<boolean>(K_BGM, enabled);
    await LocalSettings.write(K_BGM, enabled);
  }

  static shakeToResetEnabled(): boolean {
    return AppStorage.get<boolean>(K_SHAKE) ?? true;
  }

  static async saveShakeEnabled(enabled: boolean): Promise<void> {
    AppStorage.setOrCreate<boolean>(K_SHAKE, enabled);
    await LocalSettings.write(K_SHAKE, enabled);
  }

  // ---- preferences 读写小工具(未初始化时一律走默认值,不抛异常)----

  private static async readString(key: string, def: string): Promise<string> {
    const store: preferences.Preferences | null = LocalSettings.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    return typeof value === 'string' ? value as string : def;
  }

  private static async readBool(key: string, def: boolean): Promise<boolean> {
    const store: preferences.Preferences | null = LocalSettings.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    return typeof value === 'boolean' ? value as boolean : def;
  }

  private static async write(key: string, value: preferences.ValueType): Promise<void> {
    const store: preferences.Preferences | null = LocalSettings.store;
    if (store === null) {
      return;
    }
    await store.put(key, value);
    await store.flush();
  }
}

const K_SERVER: string = 'server_url';
const K_DEMO_UNLOCK: string = 'demo_all_unlocked';
const K_SOUND: string = 'sound_effects_enabled';
const K_BGM: string = 'bgm_enabled';
const K_SHAKE: string = 'shake_to_reset';
