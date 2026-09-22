import { webSocket } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { AuthManager } from '../auth/AuthManager';
import { LocalSettings } from '../data/LocalSettings';
import { BattleProtocol, AuthPayload } from './BattleProtocol';
import { BattleTransport, BattleTransportListener } from './BattleTransport';

/**
 * 网络对战传输(对应 Android 版 battle/NetworkBattleTransport.kt):
 * WebSocket → 服务端 /ws/rooms/{code}。
 *
 * 鉴权走**连接建立后的第一帧 auth**(token 不进 URL):URL query 会被
 * 服务器/反向代理的访问日志完整打印,等于把 30 天有效的令牌写进日志。
 * 25 秒一次 ping 保活(服务器 90 秒无消息判掉线)。
 */
export class NetworkBattleTransport implements BattleTransport {
  private readonly code: string;
  private ws: webSocket.WebSocket | null = null;
  private listener: BattleTransportListener | null = null;
  private pingTimer: number = -1;
  private closedByMe: boolean = false;

  constructor(code: string) {
    this.code = code;
  }

  connect(listener: BattleTransportListener): void {
    this.listener = listener;
    const url: string = NetworkBattleTransport.wsUrl(this.code);
    try {
      const ws: webSocket.WebSocket = webSocket.createWebSocket();
      this.ws = ws;
      ws.on('open', () => {
        // 首帧鉴权:服务器等这一帧确认身份
        const payload: AuthPayload = { token: AuthManager.token() };
        ws.send(BattleProtocol.encode(BattleProtocol.UP_AUTH, payload));
        this.startPing();
      });
      ws.on('message', (_err: BusinessError, value: string | ArrayBuffer) => {
        if (typeof value === 'string') {
          const l: BattleTransportListener | null = this.listener;
          if (l !== null) {
            l.onFrame(value as string);
          }
        }
      });
      ws.on('close', (_err: BusinessError, value: webSocket.CloseResult) => {
        this.stopPing();
        const l: BattleTransportListener | null = this.listener;
        if (l !== null) {
          l.onClosed(this.closedByMe ? 'bye' : `连接已关闭(${value.code})`);
        }
      });
      ws.on('error', (_err: BusinessError) => {
        this.stopPing();
        const l: BattleTransportListener | null = this.listener;
        if (l !== null) {
          l.onClosed('无法连接服务器,请检查网络或设置页的服务器地址');
        }
      });
      ws.connect(url, (err: BusinessError) => {
        if (err !== undefined && err !== null) {
          const l: BattleTransportListener | null = this.listener;
          if (l !== null) {
            l.onClosed(`连接房间失败:${err.message}`);
          }
        }
      });
    } catch (e) {
      const err = e as BusinessError;
      listener.onClosed(`连接房间失败:${err.message}`);
    }
  }

  /** http(s) → ws(s);房间号统一大写(与服务端一致)。 */
  private static wsUrl(code: string): string {
    let base: string = LocalSettings.serverUrl();
    if (base.startsWith('https://')) {
      base = 'wss://' + base.substring(8);
    } else if (base.startsWith('http://')) {
      base = 'ws://' + base.substring(7);
    }
    while (base.endsWith('/')) {
      base = base.substring(0, base.length - 1);
    }
    return `${base}/ws/rooms/${code.toUpperCase()}`;
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      const ws: webSocket.WebSocket | null = this.ws;
      if (ws !== null) {
        ws.send(BattleProtocol.encode(BattleProtocol.UP_PING));
      }
    }, 25_000);
  }

  private stopPing(): void {
    if (this.pingTimer >= 0) {
      clearInterval(this.pingTimer);
      this.pingTimer = -1;
    }
  }

  send(text: string): void {
    const ws: webSocket.WebSocket | null = this.ws;
    if (ws !== null) {
      ws.send(text);
    }
  }

  close(): void {
    this.closedByMe = true;
    this.stopPing();
    const ws: webSocket.WebSocket | null = this.ws;
    if (ws !== null) {
      try {
        ws.close({ code: 1000, reason: 'bye' });
      } catch (e) {
        // 忽略
      }
    }
    this.ws = null;
  }
}
