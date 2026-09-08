package com.example.xiangsugame.battle

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.xiangsugame.api.dto.toDto
import com.example.xiangsugame.battle.BattleProtocol.BT_HELLO
import com.example.xiangsugame.battle.BattleTransport.Listener
import com.example.xiangsugame.battle.BattleProtocol.DOWN_ERROR
import com.example.xiangsugame.battle.BattleProtocol.DOWN_FINISHED
import com.example.xiangsugame.battle.BattleProtocol.DOWN_PROGRESS
import com.example.xiangsugame.battle.BattleProtocol.DOWN_RACE_START
import com.example.xiangsugame.battle.BattleProtocol.DOWN_RESULT
import com.example.xiangsugame.battle.BattleProtocol.DOWN_ROOM_STATE
import com.example.xiangsugame.battle.BattleProtocol.FinishPayload
import com.example.xiangsugame.battle.BattleProtocol.FinishUpPayload
import com.example.xiangsugame.battle.BattleProtocol.HelloPayload
import com.example.xiangsugame.battle.BattleProtocol.RacePlayer
import com.example.xiangsugame.battle.BattleProtocol.RaceStartPayload
import com.example.xiangsugame.battle.BattleProtocol.ResultEntry
import com.example.xiangsugame.battle.BattleProtocol.ResultPayload
import com.example.xiangsugame.battle.BattleProtocol.RoomStatePayload
import com.example.xiangsugame.battle.BattleProtocol.UP_FINISH
import com.example.xiangsugame.battle.BattleProtocol.ErrorPayload
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.model.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * 蓝牙对战传输(Classic RFCOMM 双机直连,无服务器)。
 *
 * 双端对称仲裁(主机/客机同构,帧协议与网络版共用 BattleProtocol):
 *  1. hello 握手互换 userId/nickname,各自本地合成"房内玩家"状态;
 *  2. 主机选题 → 发 raceStart(完整关卡,双方均有答案,可互相校验);
 *  3. 任一方 finish:接收方校验涂黑掩码,通过才回 finished;
 *  4. 双方都完成 → 本地按时长升序结算并各自注入 result(与服务器语义等价)。
 */
class BluetoothBattleTransport(
    private val role: BattleRole,
    /** 客机侧目标设备;主机侧为 null(等待 accept)。 */
    private val targetDevice: BluetoothDevice? = null,
    private val myUserId: Int,
    private val myNickname: String,
) : BattleTransport {

    companion object {
        val APP_UUID: UUID = UUID.fromString("74b8f1c2-33d9-4a02-8f6c-0f9d8e7a6b5c")
        private const val CONNECT_TIMEOUT_MS = 90_000L

        /** 双端提交后按时长升序排名(无服务器场景的等价结算,纯逻辑供单测)。
         * 用时相同按 userId 升序,保证两端看到同一结果。 */
        fun buildResultEntries(meUid: Int, meNick: String, meMs: Long,
                               peerUid: Int, peerNick: String, peerMs: Long): List<ResultEntry> =
            listOf(
                ResultEntry(meUid, meNick, 0, meMs),
                ResultEntry(peerUid, peerNick, 0, peerMs),
            ).sortedWith(compareBy<ResultEntry> { it.durationMs }.thenBy { it.userId })
                .mapIndexed { i, e -> e.copy(rank = i + 1) }

        /** 与服务器同构的答案比对:仅比较涂黑掩码(1 == 答案 1)。 */
        fun validateGrid(grid: List<List<Int>>, level: Level): Boolean {
            val answer = level.answerGrid
            val rows = answer.size
            val cols = answer[0].size
            if (grid.size != rows) return false
            for (r in 0 until rows) {
                val row = grid[r]
                if (row.size != cols) return false
                for (c in 0 until cols) {
                    val v = row[c]
                    if (v !in 0..2) return false
                    if ((v == 1) != (answer[r][c] == 1)) return false
                }
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: Listener? = null
    private var socket: BluetoothSocket? = null

    // ---- 对局仲裁状态(双端一致) ----
    private var peer: HelloPayload? = null
    private var level: Level? = null
    private var myDoneMs: Long? = null
    private var peerDoneMs: Long? = null
    private var settled = false

    override fun connect(listener: Listener) {
        this.listener = listener
        scope.launch {
            try {
                openSocket() ?: return@launch
                runReader()
            } catch (e: IOException) {
                listener.onClosed("蓝牙连接失败:${e.message ?: "未知错误"}")
            } catch (e: SecurityException) {
                listener.onClosed("缺少蓝牙权限,请在系统设置中允许")
            }
        }
    }

    private suspend fun openSocket(): BluetoothSocket? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return null.also { listener?.onClosed("本机不支持蓝牙") }
        return try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                if (role == BattleRole.HOST) {
                    adapter.listenUsingRfcommWithServiceRecord("xiangsu-pvp", APP_UUID).use { server ->
                        server.accept().also { socket = it }
                    }
                } else {
                    val dev = targetDevice
                        ?: return@withTimeoutOrNull null.also { listener?.onClosed("未选择设备") }
                    adapter.cancelDiscovery()
                    dev.createRfcommSocketToServiceRecord(APP_UUID).also {
                        it.connect()
                        socket = it
                    }
                }
            }
        } catch (e: IOException) {
            listener?.onClosed("蓝牙连接失败:${e.message ?: "未知错误"}")
            null
        }
    }

    private fun write(text: String) {
        runCatching {
            val out = socket?.outputStream ?: return
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }.onFailure { listener?.onClosed("发送失败:${it.message}") }
    }

    private fun runReader() {
        val s = socket ?: return
        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        val l = listener ?: return

        // hello 握手
        write(BattleProtocol.encode(BT_HELLO, HelloPayload(myUserId, myNickname)))

        while (true) {
            val line = reader.readLine() ?: break
            val (type, payload) = BattleProtocol.decode(line)
            when (type) {
                BT_HELLO -> BattleProtocol.parse<HelloPayload>(payload)?.let { h ->
                    if (peer == null) {
                        peer = h
                        val me = RacePlayer(myUserId, myNickname, ready = true)
                        val other = RacePlayer(h.userId, h.nickname, ready = true)
                        l.onFrame(BattleProtocol.encode(DOWN_ROOM_STATE, RoomStatePayload(
                            roomId = "蓝牙直连",
                            hostId = if (role == BattleRole.HOST) myUserId else h.userId,
                            phase = "lobby",
                            players = listOf(me, other),
                        )))
                    }
                }

                DOWN_RACE_START -> BattleProtocol.parse<RaceStartPayload>(payload)?.let { st ->
                    level = st.level.toModel()
                    myDoneMs = null
                    peerDoneMs = null
                    settled = false
                    l.onFrame(line) // 原样交给会话
                }

                UP_FINISH -> {
                    val f = BattleProtocol.parse<FinishUpPayload>(payload)
                    val lv = level
                    when {
                        f == null || lv == null -> write(BattleProtocol.encode(
                            DOWN_ERROR, ErrorPayload("对局尚未开始,收到无效完成消息")))
                        !validateGrid(f.grid, lv) -> write(BattleProtocol.encode(
                            DOWN_ERROR, ErrorPayload("棋盘与答案不一致,无法判定通关")))
                        else -> {
                            peerDoneMs = f.elapsedMs
                            peer?.let { p ->
                                l.onFrame(BattleProtocol.encode(DOWN_FINISHED,
                                    FinishPayload(userId = p.userId, rank = 0, durationMs = f.elapsedMs)))
                            }
                            settleIfBothDone()
                        }
                    }
                }

                else -> l.onFrame(line) // roomState 后的其余帧原样转发
            }
        }
        l.onClosed(null)
    }

    override fun send(text: String) {
        val (type, payload) = BattleProtocol.decode(text)
        if (type == UP_FINISH) {
            val f = BattleProtocol.parse<FinishUpPayload>(payload)
            if (f != null) {
                myDoneMs = f.elapsedMs
                settled = false
                write(text)
                settleIfBothDone()
                return
            }
        }
        write(text)
    }

    /** 主机选题发车:向对端发 raceStart 并本地注入本机会话。 */
    fun sendRaceStart(level: Level) {
        this.level = level
        myDoneMs = null
        peerDoneMs = null
        settled = false
        val frame = BattleProtocol.encode(
            DOWN_RACE_START,
            RaceStartPayload(serverStartMs = System.currentTimeMillis(), level = level.toDto()),
        )
        write(frame)
        listener?.onFrame(frame)
    }

    /** 双方都完成且各知对方用时 → 本地结算(只结算一次)。 */
    private fun settleIfBothDone() {
        val m = myDoneMs ?: return
        val p = peerDoneMs ?: return
        val peerInfo = peer ?: return
        if (settled) return
        settled = true
        val entries = buildResultEntries(myUserId, myNickname, m, peerInfo.userId, peerInfo.nickname, p)
        val frame = BattleProtocol.encode(DOWN_RESULT, ResultPayload(entries))
        write(frame)
        listener?.onFrame(frame)
    }

    override fun close() {
        runCatching { socket?.close() }
        scope.cancel()
    }
}
