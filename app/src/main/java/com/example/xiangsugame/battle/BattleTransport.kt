package com.example.xiangsugame.battle

/**
 * 对战传输抽象 —— 网络(服务器 WS 中转)与蓝牙(RFCOMM 直连)共用同一接口与帧协议,
 * BattleSession 只面向本接口编程,玩法逻辑零分叉。
 */
interface BattleTransport {

    /** 发起连接(异步)。成功后回调 listener;失败/断开回调 onClosed。 */
    fun connect(listener: Listener)

    /** 发送一帧(帧 = BattleProtocol.encode 产物)。线程安全。 */
    fun send(text: String)

    fun close()

    interface Listener {
        fun onFrame(raw: String)
        /** 连接关闭/失败。reason 为可展示原因;正常退出传 "bye"。 */
        fun onClosed(reason: String?)
    }
}
