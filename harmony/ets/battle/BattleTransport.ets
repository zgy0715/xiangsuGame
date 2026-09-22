/**
 * 对战传输抽象(对应 Android 版 battle/BattleTransport.kt)。
 * 会话只面向本接口编程,协议与玩法逻辑不因传输而分叉。
 */
export interface BattleTransportListener {
  /** 收到一帧(原始 JSON 文本)。 */
  onFrame(raw: string): void;

  /** 连接关闭/失败;reason 为可展示原因,"bye" 表示正常退出。 */
  onClosed(reason: string): void;
}

export interface BattleTransport {
  /** 发起连接(异步)。 */
  connect(listener: BattleTransportListener): void;

  /** 发送一帧(线程安全)。 */
  send(text: string): void;

  close(): void;
}
