import { connection } from '@kit.NetworkKit';

/**
 * 网络状态监听(对应 Android 版 receiver/NetworkMonitor.kt)。
 *
 * 进程级单例:根页面初始化一次,断网时顶部提示条由它驱动。
 * netAvailable / netLost / netCapabilitiesChange 三个事件共同维护 isConnected。
 */
export class NetworkMonitor {
  private static netConnection: connection.NetConnection | null = null;
  private static connected: boolean = true;

  static get isConnected(): boolean {
    return NetworkMonitor.connected;
  }

  static init(): void {
    if (NetworkMonitor.netConnection !== null) {
      return;
    }
    try {
      const net: connection.NetConnection = connection.createNetConnection();
      net.on('netAvailable', () => {
        NetworkMonitor.connected = true;
        AppStorage.setOrCreate<boolean>('offline', false);
      });
      net.on('netLost', () => {
        NetworkMonitor.connected = false;
        AppStorage.setOrCreate<boolean>('offline', true);
      });
      net.on('netCapabilitiesChange', (data: connection.NetCapabilityInfo) => {
        // ⚠ 这里原来写的是 `caps !== undefined`,而 netCap 是**必填字段** —— 判断恒真,
        //   于是 netLost 之后只要再来一次能力变化回调,红条就被误清掉(用户以为有网)。
        //   正确做法:看能力集合里**是否真的具备联网能力**(INTERNET)。
        const caps: connection.NetCapabilities | undefined = data.netCap;
        let hasInternet: boolean = false;
        const list: number[] | undefined = caps === undefined ? undefined : caps.networkCap;
        if (list !== undefined && list !== null) {
          for (let i = 0; i < list.length; i++) {
            if (list[i] === connection.NetCap.NET_CAPABILITY_INTERNET) {
              hasInternet = true;
              break;
            }
          }
        }
        if (!hasInternet) {
          // 没有联网能力:保持/置为离线,绝不清红条
          NetworkMonitor.connected = false;
          AppStorage.setOrCreate<boolean>('offline', true);
          return;
        }
        NetworkMonitor.connected = true;
        AppStorage.setOrCreate<boolean>('offline', false);
      });
      net.register((err: Object) => {
        if (err !== undefined && err !== null) {
          NetworkMonitor.connected = true;
        }
      });
      NetworkMonitor.netConnection = net;
      AppStorage.setOrCreate<boolean>('offline', false);
    } catch (e) {
      NetworkMonitor.connected = true;
    }
  }
}
