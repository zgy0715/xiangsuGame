package com.example.xiangsugame.battle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.xiangsugame.api.dto.toDto
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.battle.BattleProtocol.DOWN_ERROR
import com.example.xiangsugame.battle.BattleProtocol.DOWN_FINISHED
import com.example.xiangsugame.battle.BattleProtocol.DOWN_PLAYER_LEFT
import com.example.xiangsugame.battle.BattleProtocol.DOWN_PROGRESS
import com.example.xiangsugame.battle.BattleProtocol.DOWN_RACE_START
import com.example.xiangsugame.battle.BattleProtocol.DOWN_RESULT
import com.example.xiangsugame.battle.BattleProtocol.DOWN_ROOM_STATE
import com.example.xiangsugame.battle.BattleProtocol.DOWN_TIME_SYNC
import com.example.xiangsugame.battle.BattleProtocol.UP_FINISH
import com.example.xiangsugame.battle.BattleProtocol.UP_PROGRESS
import com.example.xiangsugame.battle.BattleProtocol.UP_REMATCH
import com.example.xiangsugame.battle.BattleProtocol.UP_START
import com.example.xiangsugame.battle.BattleProtocol.UP_TIME_SYNC
import com.example.xiangsugame.model.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** 对战侧身份(网络房:房主/房客;蓝牙:主机/客机语义相同)。 */
enum class BattleRole { HOST, GUEST }

/** 房间阶段(与服务器 phase 字符串一致)。 */
enum class BattlePhase { LOBBY, RACING, SETTLED, CLOSED }

/**
 * 对战会话 —— 与传输无关的竞速状态机:
 * 网络与蓝牙都只喂 BattleFrame 事件(见 [BattleTransport.Listener]),逻辑零分叉;
 * Compose 可观察字段驱动房间页/竞速页/结算页。localFinish 前的正确性校验在
 * GameScreen(Validator.isSolved),提交后由服务器(或蓝牙主机)再次权威校验。
 */
class BattleSession(
    private val transport: BattleTransport,
    val role: BattleRole,
    val myUserId: Int = AuthManager.session?.userId ?: -1,
    /** 展示用房间标识(网络=4 位房号;蓝牙=本机蓝牙名)。 */
    val roomTag: String = "",
    /** 是否可断线重连(网络房重连 = 同一房号重新建会话;蓝牙近场直连不支持)。 */
    val canReconnect: Boolean = false,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- Compose 可观察状态 ----
    var phase by mutableStateOf(BattlePhase.LOBBY)
        private set
    var hostId by mutableStateOf(-1)
        private set
    var players by mutableStateOf<List<BattleProtocol.RacePlayer>>(emptyList())
        private set
    var puzzleName by mutableStateOf<String?>(null)
        private set

    /** 竞速开始后携带的完整关卡(含答案;服务器/主机下发的权威数据)。 */
    var raceLevel by mutableStateOf<Level?>(null)
        private set

    /** 服务器(主机)宣布起跑的服务器时刻(ms),配合时钟差还原权威计时。 */
    var serverStartMs by mutableLongStateOf(0L)
        private set

    /** 服务器时钟 - 本机时钟 的差值(ms),由 timeSync 握手一次校准(NTP)。 */
    var clockOffsetMs by mutableLongStateOf(0L)
        private set

    private var syncSentAt = 0L

    /** 我是否已完成并提交(等待服务器确认名次)。 */
    var mySubmitted by mutableStateOf(false)
        private set

    /** 本局最终名次与成绩(结算后才有)。 */
    var myFinalRank by mutableStateOf(0)
        private set
    var resultEntries by mutableStateOf<List<BattleProtocol.ResultEntry>>(emptyList())
        private set

    /** 服务器/主机判我提交不正确等提示(一次性展示)。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 房间被解散/连接失败等致命错误(展示后回大厅)。 */
    var fatal by mutableStateOf<String?>(null)
        private set

    /**
     * 本局已完成(拿到名次)的玩家,按名次升序 —— 用于竞速中把"第几名是谁"实时展示给
     * 还在做的人(非阻断,不影响继续操作)。
     */
    val finishedPlayers: List<BattleProtocol.RacePlayer>
        get() = players.filter { it.finishedRank > 0 }.sortedBy { it.finishedRank }

    /** 是否已经有人完成(仍在竞速中) —— 驱动"有人完成"提示横幅,不冻结棋盘。 */
    val someoneFinished: Boolean
        get() = phase == BattlePhase.RACING && players.any { it.finishedRank > 0 }

    /**
     * 我已经提交、但服务器尚未宣布结算:此时继续保持棋盘可见,等其他人陆续完成。
     * 期间计时器仍在走,名次已由服务器记录,不影响最终结算。
     */
    val awaitingMyResult: Boolean
        get() = phase == BattlePhase.RACING && mySubmitted &&
            players.any { it.finishedRank == 0 && it.connected }

    /** 竞速中还没完成的玩家(用于横幅提示"还有 N 人在做")。 */
    val racingCount: Int
        get() = players.count { it.connected && it.finishedRank == 0 }

    val isHost: Boolean get() = role == BattleRole.HOST
    val me: BattleProtocol.RacePlayer?
        get() = players.firstOrNull { it.userId == myUserId }

    private var lastProgressSent = 0L

    // ---------------- 连接 / 关闭 ----------------

    /** 传输层已连接(由 UI 在合适时机调用)。 */
    fun open() {
        transport.connect(object : BattleTransport.Listener {
            override fun onFrame(raw: String) {
                handleFrame(raw)
            }

            override fun onClosed(reason: String?) {
                if (reason != null && reason != "bye") fatal = reason
                phase = BattlePhase.CLOSED
            }
        })
    }

    fun close() {
        transport.close()
        scope.cancel()
    }

    /** 一次性提示已读(弹窗关闭后清除,避免重复弹出)。 */
    fun consumeNotice() {
        notice = null
    }

    /** 本机时钟换算成服务器时钟(offset 由 timeSync 校准);未校准前即为本机时间。 */
    fun serverNowMs(): Long = System.currentTimeMillis() + clockOffsetMs

    /**
     * 请求一次 timeSync 校准:记录本地发送时刻,收到回包后按往返时延折半
     * 计算服务器与本机时钟差。在任何 roomState / raceStart 后调用一次即可。
     */
    fun requestTimeSync() {
        syncSentAt = System.currentTimeMillis()
        transport.send(BattleProtocol.encode(BattleProtocol.UP_TIME_SYNC))
    }

    // ---------------- 上行动作 ----------------

    /** 房主选题:level 为完整关卡(内置关直接传,在线关先 fetch 完整 JSON)。 */
    fun choosePuzzle(level: Level) {
        transport.send(BattleProtocol.encode(
            BattleProtocol.UP_CHOOSE_PUZZLE,
            BattleProtocol.ChoosePuzzlePayload(level.toDto()),
        ))
    }

    /** 房主开局(≥2 人且已选题才被服务器接受)。 */
    fun requestStart() {
        transport.send(BattleProtocol.encode(BattleProtocol.UP_START))
    }

    /** 房主重赛:结算后回到 lobby,可重新选题再开一局(服务器校验仅房主有效)。 */
    fun requestRematch() {
        transport.send(BattleProtocol.encode(BattleProtocol.UP_REMATCH))
    }

    /** 竞速中的进度上报(本地 1s 节流,服务器另有 0.5s 节流)。 */
    fun reportProgress(filled: Int, elapsedSeconds: Int) {
        if (phase != BattlePhase.RACING || mySubmitted) return
        val now = System.currentTimeMillis()
        if (now - lastProgressSent < 1000) return
        lastProgressSent = now
        transport.send(BattleProtocol.encode(
            BattleProtocol.UP_PROGRESS,
            BattleProtocol.ProgressUpPayload(filled, elapsedSeconds * 1000L),
        ))
    }

    /** 本地解出(Validator 已确认) → 提交棋盘与本地用时(名次由对端权威判定)。 */
    fun localFinish(board: Array<IntArray>, elapsedSeconds: Int) {
        if (mySubmitted) return
        mySubmitted = true
        transport.send(BattleProtocol.encode(
            BattleProtocol.UP_FINISH,
            BattleProtocol.FinishUpPayload(BattleProtocol.gridToList(board), elapsedSeconds * 1000L),
        ))
    }

    // ---------------- 下行处理 ----------------

    /** 接收一帧并更新状态(网络:服务器下行;蓝牙:对端帧/本地注入)。线程安全。 */
    fun ingestFrame(raw: String) {
        handleFrame(raw)
    }

    private fun handleFrame(raw: String) {
        val (type, payload) = BattleProtocol.decode(raw) ?: return
        when (type) {
            DOWN_ROOM_STATE -> BattleProtocol.parse<BattleProtocol.RoomStatePayload>(payload)?.let { st ->
                phase = parsePhase(st.phase)
                hostId = st.hostId
                players = st.players
                puzzleName = st.puzzleName
                requestTimeSync()
                if (st.phase == "lobby") {
                    // 重赛/回到待机:清空上局竞速与结算状态
                    raceLevel = null
                    serverStartMs = 0L
                    mySubmitted = false
                    resultEntries = emptyList()
                    myFinalRank = 0
                }
            }

            DOWN_PLAYER_LEFT -> {
                val uid = BattleProtocol.parse<BattleProtocol.PlayerLeftPayload>(payload)?.userId ?: return
                players = players.filterNot { it.userId == uid }
            }

            DOWN_RACE_START -> {
                val st = BattleProtocol.parse<BattleProtocol.RaceStartPayload>(payload) ?: return
                phase = BattlePhase.RACING
                raceLevel = st.level.toModel()
                serverStartMs = st.serverStartMs
                mySubmitted = false
                requestTimeSync()
                players = players.map {
                    if (it.userId == myUserId) it.copy(finishedRank = 0) else it.copy(filled = 0, finishedRank = 0)
                }
            }

            DOWN_TIME_SYNC -> {
                val p = BattleProtocol.parse<BattleProtocol.TimeSyncPayload>(payload) ?: return
                val rtt = System.currentTimeMillis() - syncSentAt
                clockOffsetMs = p.serverTimeMs - System.currentTimeMillis() + rtt / 2
            }

            DOWN_PROGRESS -> {
                val p = BattleProtocol.parse<BattleProtocol.ProgressPayload>(payload) ?: return
                players = players.map {
                    if (it.userId == p.userId) it.copy(filled = p.filled) else it
                }
            }

            DOWN_FINISHED -> {
                val f = BattleProtocol.parse<BattleProtocol.FinishPayload>(payload) ?: return
                players = players.map {
                    if (it.userId == f.userId) it.copy(finishedRank = f.rank) else it
                }
                if (f.userId == myUserId && !mySubmitted) mySubmitted = true
            }

            DOWN_RESULT -> {
                val r = BattleProtocol.parse<BattleProtocol.ResultPayload>(payload) ?: return
                resultEntries = r.entries
                myFinalRank = r.entries.firstOrNull { it.userId == myUserId }?.rank ?: 0
                players = players.map { p ->
                    r.entries.firstOrNull { it.userId == p.userId }?.let {
                        p.copy(finishedRank = it.rank)
                    } ?: p
                }
                phase = BattlePhase.SETTLED
            }

            DOWN_ERROR -> {
                val e = BattleProtocol.parse<BattleProtocol.ErrorPayload>(payload) ?: return
                notice = e.message
                if (e.message.contains("解散")) fatal = e.message
            }
        }
    }

    private fun parsePhase(name: String): BattlePhase = when (name) {
        "lobby" -> BattlePhase.LOBBY
        "racing" -> BattlePhase.RACING
        "settled" -> BattlePhase.SETTLED
        else -> BattlePhase.LOBBY
    }
}
