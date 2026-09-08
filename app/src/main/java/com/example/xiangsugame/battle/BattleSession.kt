package com.example.xiangsugame.battle

import androidx.compose.runtime.getValue
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
import com.example.xiangsugame.battle.BattleProtocol.UP_FINISH
import com.example.xiangsugame.battle.BattleProtocol.UP_PROGRESS
import com.example.xiangsugame.battle.BattleProtocol.UP_START
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

    /** 对方先完成导致我被冻结(仅未完成方可见)。 */
    val pausedForMe: Boolean
        get() = phase == BattlePhase.RACING && !mySubmitted &&
            players.any { it.finishedRank > 0 }

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
            }

            DOWN_PLAYER_LEFT -> {
                val uid = BattleProtocol.parse<BattleProtocol.PlayerLeftPayload>(payload)?.userId ?: return
                players = players.filterNot { it.userId == uid }
            }

            DOWN_RACE_START -> {
                val st = BattleProtocol.parse<BattleProtocol.RaceStartPayload>(payload) ?: return
                phase = BattlePhase.RACING
                raceLevel = st.level.toModel()
                mySubmitted = false
                players = players.map {
                    if (it.userId == myUserId) it.copy(finishedRank = 0) else it.copy(filled = 0, finishedRank = 0)
                }
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
