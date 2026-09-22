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
import com.example.xiangsugame.battle.BattleProtocol.ProgressPayload
import com.example.xiangsugame.battle.BattleProtocol.ProgressUpPayload
import com.example.xiangsugame.battle.BattleProtocol.RacePlayer
import com.example.xiangsugame.battle.BattleProtocol.RaceStartPayload
import com.example.xiangsugame.battle.BattleProtocol.ResultEntry
import com.example.xiangsugame.battle.BattleProtocol.ResultPayload
import com.example.xiangsugame.battle.BattleProtocol.RoomStatePayload
import com.example.xiangsugame.battle.BattleProtocol.UP_FINISH
import com.example.xiangsugame.battle.BattleProtocol.UP_PROGRESS
import com.example.xiangsugame.battle.BattleProtocol.ErrorPayload
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.model.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 *  3. 任一方 finish:接收方校验涂黑掩码,通过才认账;
 *  4. 双方都完成 → 本地按时长升序结算并各自注入 result(与服务器语义等价)。
 *
 * 计时与防作弊(无服务器场景能做到的部分):
 *  - 排名用**本机单调时钟观察到的完成时刻**(`SystemClock.elapsedRealtime`)计算,
 *    **不采信对端帧里自报的 elapsedMs** —— 否则改包端发一个 elapsedMs=1 就能夺冠;
 *  - 只接受白名单帧类型,对端发来的 result 一律忽略(结算由两端各自算),
 *    避免对端注入一帧就把本地界面推成"已结算"。
 *  说明:纯 P2P 无法阻止"改包端拿到答案后瞬间提交"这类作弊,
 *  只能保证"用时不来自对端自报"。
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
        private const val SERVICE_NAME = "xiangsu-pvp"
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
    private val writeLock = Any()  // 多线程(主线程上报 / IO 协程结算)串行化写帧

    // ---- 对局仲裁状态(双端一致) ----
    private var peer: HelloPayload? = null
    private var level: Level? = null
    private var myDoneMs: Long? = null
    private var peerDoneMs: Long? = null
    private var settled = false

    /** 本机起跑时刻(单调时钟),用于把"完成时刻"换算成用时。 */
    private var raceStartAtMs: Long = 0L

    private fun elapsedSinceStart(): Long =
        if (raceStartAtMs == 0L) 0L
        else (android.os.SystemClock.elapsedRealtime() - raceStartAtMs).coerceAtLeast(0L)

    override fun connect(listener: Listener) {
        this.listener = listener
        scope.launch {
            try {
                val s = openSocket() ?: return@launch  // 失败原因已在 openSocket 里回调
                socket = s
                runReader()
            } catch (e: IOException) {
                listener.onClosed(friendlyError(e))
            } catch (e: SecurityException) {
                listener.onClosed("缺少蓝牙权限,请在系统设置里允许「附近的设备」权限")
            }
        }
    }

    private suspend fun openSocket(): BluetoothSocket? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            listener?.onClosed("本机没有蓝牙适配器(模拟器不支持蓝牙对战)")
            return null
        }
        if (!adapter.isEnabled) {
            listener?.onClosed("蓝牙未打开,请先在系统设置里打开蓝牙")
            return null
        }
        return if (role == BattleRole.HOST) acceptLoop(adapter) else connectOnce(adapter)
    }

    /**
     * 主机:循环等待连接,**不做"一次性 90 秒截止"**。
     *
     * 旧实现用 withTimeoutOrNull(90s) 包住 accept():客机那边要过权限弹窗、搜索设备、
     * 甚至先完成系统配对,超过 90 秒很常见;一旦超时,主机就静默退出监听(旧界面还会变成
     * 白屏),客机之后每次点「连接」都必然被拒 —— 表现就是"设备能搜到,就是连不上"。
     * 现在只要用户还停在这个页面就一直等(离开页面时 close() 会取消协程)。
     */
    private suspend fun acceptLoop(adapter: BluetoothAdapter): BluetoothSocket? {
        while (currentCoroutineContext().isActive) {
            try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID).use { server ->
                    return server.accept()  // 阻塞直到对方连上;use 会在返回后关闭服务端套接字
                }
            } catch (e: IOException) {
                delay(500)  // 监听被打断:稍等后重新开始等待,而不是放弃
            } catch (e: SecurityException) {
                listener?.onClosed("缺少蓝牙权限,请在系统设置里允许「附近的设备」权限")
                return null
            }
        }
        return null
    }

    /** 客机:一次性连接(先取消发现 —— 发现过程会显著拖慢甚至让连接失败)。 */
    private suspend fun connectOnce(adapter: BluetoothAdapter): BluetoothSocket? {
        val dev = targetDevice
        if (dev == null) {
            listener?.onClosed("未选择对端设备")
            return null
        }
        runCatching { adapter.cancelDiscovery() }
        val s = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                val sock = dev.createRfcommSocketToServiceRecord(APP_UUID)
                try {
                    sock.connect()
                } catch (e: IOException) {
                    runCatching { sock.close() }  // 失败必须关掉,否则 RFCOMM socket 泄漏
                    throw e
                }
                sock
            }
        } catch (e: IOException) {
            listener?.onClosed(friendlyError(e))
            return null
        } catch (e: SecurityException) {
            listener?.onClosed("缺少蓝牙权限,请在系统设置里允许「附近的设备」权限")
            return null
        }
        if (s == null) {
            listener?.onClosed(
                "连接超时(90 秒)。请依次确认:①两台手机已在「系统设置 → 蓝牙」里配对成功;" +
                    "②主机停在「等待好友连接…」页面;③主机屏幕保持点亮、两台手机靠近。",
            )
        }
        return s
    }

    /** 把系统原文翻译成"能照着做"的中文提示(系统原文基本没法指导操作)。 */
    private fun friendlyError(e: IOException): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("read failed", true) || raw.contains("socket might closed", true) ->
                "连接被对方拒绝。最常见的原因是两台手机还没在「系统设置 → 蓝牙」里配对;" +
                    "也可能是主机不在「等待好友连接」页面。请先配对,再让主机点「作为主机开局」。"
            raw.contains("Connection refused", true) ->
                "对方没有在等待连接。请让主机先点「作为主机开局」并停在等待页,客机再点连接。"
            raw.contains("Service discovery", true) || raw.contains("SDP", true) ->
                "找不到对方的对战服务。请确认对方也已打开本 App 并停在「等待好友连接」页面。"
            raw.contains("closed", true) ->
                "蓝牙连接被关闭。请关掉蓝牙再打开,然后重试。"
            else -> "蓝牙连接失败:${raw.ifBlank { "未知错误" }}(可尝试重新配对两台手机)"
        }
    }

    private fun write(text: String) {
        // 加锁:主线程(进度/完成上报)与 IO 协程(结算广播)可能同时写,
        // 两条帧交错会让对端 readLine 解析失败并静默丢弃。
        synchronized(writeLock) {
            runCatching {
                val out = socket?.outputStream ?: return
                out.write((text + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }.onFailure { listener?.onClosed("发送失败:${it.message}") }
        }
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
                    raceStartAtMs = android.os.SystemClock.elapsedRealtime()  // 本机起跑基准
                    myDoneMs = null
                    peerDoneMs = null
                    settled = false
                    l.onFrame(line) // 原样交给会话
                }

                UP_FINISH -> {
                    val f = BattleProtocol.parse<FinishUpPayload>(payload)
                    val lv = level
                    when {
                        f == null || lv == null || raceStartAtMs == 0L -> write(BattleProtocol.encode(
                            DOWN_ERROR, ErrorPayload("对局尚未开始,收到无效完成消息")))
                        !validateGrid(f.grid, lv) -> write(BattleProtocol.encode(
                            DOWN_ERROR, ErrorPayload("棋盘与答案不一致,无法判定通关")))
                        else -> {
                            // 用时 = 本机观察到的到达时刻 − 本机起跑时刻。
                            // 不采信 f.elapsedMs(对端自报):改包端发 elapsedMs=1 就能夺冠。
                            val observedMs = elapsedSinceStart()
                            peerDoneMs = observedMs
                            peer?.let { p ->
                                // 名次由本地判定:对方先完成 → 第 1 名。
                                // (旧代码写死 rank = 0,而客户端用 finishedRank > 0 判断,
                                //  "对方完成"的横幅永远不会出现。)
                                val peerRank = if (myDoneMs == null) 1 else 2
                                l.onFrame(BattleProtocol.encode(DOWN_FINISHED, FinishPayload(
                                    userId = p.userId, rank = peerRank, durationMs = observedMs)))
                            }
                            settleIfBothDone()
                        }
                    }
                }

                // 进度帧:网络版由服务器把上行 `progress` 转成下行广播给其他人;蓝牙没有服务器,
                // 必须自己转 —— 以前这条落到下面的 else 被静默丢弃,表现为"连上了但对端进度条全程不动"。
                UP_PROGRESS -> {
                    val p = BattleProtocol.parse<ProgressUpPayload>(payload)
                    val other = peer
                    if (p != null && other != null) {
                        l.onFrame(BattleProtocol.encode(DOWN_PROGRESS, ProgressPayload(
                            userId = other.userId,
                            filled = p.filled,
                            elapsedMs = p.elapsedMs,
                        )))
                    }
                }

                // 只放行展示类帧:对端发来的 result 一律忽略(结算必须由两端各自算),
                // 未知 type 也忽略 —— 以前是 `else -> l.onFrame(line)`,对端随便发一帧
                // 就能把本地界面推成"已结算"或伪造名次。
                DOWN_ROOM_STATE, DOWN_PROGRESS, DOWN_ERROR -> l.onFrame(line)
                else -> Unit
            }
        }
        l.onClosed(null)
    }

    override fun send(text: String) {
        val (type, payload) = BattleProtocol.decode(text)
        if (type == UP_FINISH) {
            val f = BattleProtocol.parse<FinishUpPayload>(payload)
            if (f != null) {
                // 本机用时用单调时钟自测(不受系统改时间影响),不直接用上层传入的 elapsedMs
                myDoneMs = elapsedSinceStart()
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
        raceStartAtMs = android.os.SystemClock.elapsedRealtime()
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
