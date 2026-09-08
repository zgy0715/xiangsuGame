package com.example.xiangsugame.battle

import com.example.xiangsugame.api.SharedJson
import com.example.xiangsugame.api.dto.LevelDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 对战线协议 —— 一次成型,网络与蓝牙双端复用同一 schema:
 * 帧 = {"type": "...", "payload": {...}},两端实现完全同构。
 *
 * 上行(客户端→服务器): ready / start / choosePuzzle / progress / finish / ping /
 *                     rematch(重赛) / timeSync(NTP 校时)
 * 下行(服务器→客户端): roomState / playerJoined / playerLeft / raceStart /
 *                     progress / finished / result / error / timeSync
 * 蓝牙端借同一字段名直接互发(去掉房间语义字段,主机兼做"服务器"校验)。
 */
object BattleProtocol {

    // ---- 上行 ----
    const val UP_READY = "ready"
    const val UP_START = "start"
    const val UP_CHOOSE_PUZZLE = "choosePuzzle"
    const val UP_PROGRESS = "progress"
    const val UP_FINISH = "finish"
    const val UP_PING = "ping"
    const val UP_REMATCH = "rematch"
    const val UP_TIME_SYNC = "timeSync"

    // ---- 蓝牙直连握手 ----
    const val BT_HELLO = "hello"

    // ---- 下行 ----
    const val DOWN_ROOM_STATE = "roomState"
    const val DOWN_PLAYER_JOINED = "playerJoined"
    const val DOWN_PLAYER_LEFT = "playerLeft"
    const val DOWN_RACE_START = "raceStart"
    const val DOWN_PROGRESS = "progress"
    const val DOWN_FINISHED = "finished"
    const val DOWN_RESULT = "result"
    const val DOWN_ERROR = "error"
    const val DOWN_TIME_SYNC = "timeSync"

    // ---------------- 负载 DTO ----------------

    @Serializable
    data class RacePlayer(
        val userId: Int,
        val nickname: String,
        val connected: Boolean = true,
        val ready: Boolean = false,
        val filled: Int = 0,
        val finishedRank: Int = 0,
    )

    @Serializable
    data class RoomStatePayload(
        val roomId: String = "",
        val hostId: Int = -1,
        val phase: String = "lobby",
        val players: List<RacePlayer> = emptyList(),
        val puzzleName: String? = null,
    )

    @Serializable
    data class RaceStartPayload(val serverStartMs: Long = 0L, val level: LevelDto)

    @Serializable
    data class ProgressPayload(
        val userId: Int = -1,
        val filled: Int = 0,
        val elapsedMs: Long = 0L,
    )

    @Serializable
    data class FinishPayload(val userId: Int = -1, val rank: Int = 0, val durationMs: Long = 0L)

    @Serializable
    data class ResultEntry(val userId: Int, val nickname: String = "", val rank: Int, val durationMs: Long = 0L)

    @Serializable
    data class ResultPayload(val entries: List<ResultEntry> = emptyList())

    @Serializable
    data class ErrorPayload(val message: String = "")

    @Serializable
    data class ChoosePuzzlePayload(val level: LevelDto)

    @Serializable
    data class FinishUpPayload(val grid: List<List<Int>>, val elapsedMs: Long)

    @Serializable
    data class TimeSyncPayload(val serverTimeMs: Long = 0L)

    @Serializable
    data class HelloPayload(val userId: Int, val nickname: String)

    @Serializable
    data class PlayerJoinedPayload(val player: RacePlayer)

    @Serializable
    data class PlayerLeftPayload(val userId: Int)

    @Serializable
    data class ProgressUpPayload(val filled: Int, val elapsedMs: Long)

    // ---------------- 编码 / 解码 ----------------

    /** 无负载帧(如 start / ping)。 */
    fun encode(type: String): String = buildJsonObject { put("type", type) }.toString()

    /** 带负载帧:payload 须为 @Serializable 类型(经 JsonElement 嵌入,避免字符串转义层)。 */
    inline fun <reified T> encode(type: String, payload: T): String = buildJsonObject {
        put("type", type)
        put("payload", SharedJson.parseToJsonElement(SharedJson.encodeToString(payload)))
    }.toString()

    fun decode(raw: String): Pair<String, JsonObject?> {
        val obj = runCatching { SharedJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return "" to null
        val type = (obj["type"] as? JsonPrimitive)?.content ?: return "" to null
        return type to (obj["payload"] as? JsonObject)
    }

    inline fun <reified T> parse(payload: JsonObject?): T? =
        payload?.let { runCatching { SharedJson.decodeFromString<T>(it.toString()) }.getOrNull() }

    fun gridToList(board: Array<IntArray>): List<List<Int>> =
        board.map { row -> row.toList() }
}
