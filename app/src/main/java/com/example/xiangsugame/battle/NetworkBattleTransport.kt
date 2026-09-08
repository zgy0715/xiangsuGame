package com.example.xiangsugame.battle

import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.LocalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 网络对战传输:OkHttp WebSocket → 服务器 /ws/rooms/{code}。
 * 25 秒一次 {"type":"ping"} 保活(服务器 90 秒无消息判掉线)。
 * Listener 回调在 OkHttp 线程;BattleSession 解析后驱动 Compose 状态(线程安全写入)。
 */
class NetworkBattleTransport(private val code: String) : BattleTransport {

    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pingJob: Job? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 空闲超时由协议心跳负责
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun connect(listener: BattleTransport.Listener) {
        val token = AuthManager.session?.token.orEmpty()
        val base = LocalSettings.serverUrl.trimEnd('/')
        val url = "$base/ws/rooms/${code.uppercase()}?token=${URLEncoder.encode(token, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startPing()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onClosed(t.message ?: "连接失败")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason.ifBlank { "连接已关闭" })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason.ifBlank { null })
            }
        })
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(25_000)
                ws?.send(BattleProtocol.encode(BattleProtocol.UP_PING))
            }
        }
    }

    override fun send(text: String) {
        ws?.send(text)
    }

    override fun close() {
        pingJob?.cancel()
        ws?.close(1000, "bye")
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
