import { common } from '@kit.AbilityKit';
import { AuthManager } from './auth/AuthManager';
import { AccountStore } from './data/AccountStore';
import { LevelCache } from './data/LevelCache';
import { LocalSettings } from './data/LocalSettings';

/**
 * 轻量依赖容器(对应 Android 版 AppGraph.kt)。
 *
 * 由根页面(Index)调用一次:初始化设置、会话、题目缓存与网络地址;
 * 各页面经此取账号级 AccountStore(同设备多账号互不干扰)。
 */
export class AppGraph {
  private static context: common.Context | null = null;
  private static accounts: Map<number, AccountStore> = new Map<number, AccountStore>();

  static async init(context: common.Context): Promise<void> {
    AppGraph.context = context;
    await LocalSettings.init(context);
    await LevelCache.init(context);
    await AuthManager.init(context);
    AuthManager.refreshBaseUrl(); // 服务器地址来自设置
  }

  static getContext(): common.Context | null {
    return AppGraph.context;
  }

  /** 取当前账号的进度 Store;未初始化过则先加载。 */
  static async account(userId: number): Promise<AccountStore> {
    const cached: AccountStore | undefined = AppGraph.accounts.get(userId);
    if (cached !== undefined) {
      return cached;
    }
    const store: AccountStore = new AccountStore(userId);
    const ctx: common.Context | null = AppGraph.context;
    if (ctx !== null) {
      await store.init(ctx);
    }
    AppGraph.accounts.set(userId, store);
    return store;
  }

  /** 退出登录时清掉账号缓存(下次登录重新加载),避免脏状态跨账号泄漏。 */
  static clearAccounts(): void {
    AppGraph.accounts.clear();
  }
}
