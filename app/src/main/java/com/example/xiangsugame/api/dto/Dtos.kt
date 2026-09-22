package com.example.xiangsugame.api.dto

import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.Level
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------- 谜题 ----------------

/**
 * 网络关卡 JSON(与 server/ 返回的完整关卡字段一一对应)。
 * grid 用 List<List<Int>> 承载,序列化友好;映射到 model.Level 时转 Array<IntArray>。
 */
@Serializable
data class LevelDto(
    val id: Int,
    val name: String,
    val difficulty: String,
    val rows: Int,
    val cols: Int,
    val clueGrid: List<List<Int>>,
    val answerGrid: List<List<Int>>,
    val timedLimitSeconds: Int? = null,
    /** 题库列表元信息里的已解次数(取整题接口为 null)。 */
    val solves: Int? = null,
)

@Serializable
data class DailyLevelResponse(
    val date: String,
    val difficulty: String,
    val level: LevelDto,
)

@Serializable
data class PuzzleMeta(
    val id: Int,
    val name: String,
    val difficulty: String,
    val rows: Int,
    val cols: Int,
    val timedLimitSeconds: Int? = null,
    val solves: Int? = null,
)

@Serializable
data class BankResponse(
    val total: Int,
    val items: List<PuzzleMeta>,
)

/** 服务端难度字符串 → 模型 Difficulty(未知值按 MEDIUM 兜底,避免版本差异崩溃)。 */
fun difficultyFromName(name: String?): Difficulty =
    Difficulty.entries.firstOrNull { it.name == name } ?: Difficulty.MEDIUM

/** LevelDto → model.Level(与内置关同型,可直接进 GameScreen / Validator / 求解器)。 */
fun LevelDto.toModel(): Level {
    require(rows > 0 && cols > 0 && clueGrid.size == rows && answerGrid.size == rows) {
        "网络关卡数据非法: ${rows}x${cols}"
    }
    return Level(
        id = id,
        name = name,
        difficulty = difficultyFromName(difficulty),
        rows = rows,
        cols = cols,
        clueGrid = Array(rows) { r -> IntArray(cols) { c -> clueGrid[r][c] } },
        answerGrid = Array(rows) { r -> IntArray(cols) { c -> answerGrid[r][c] } },
        timedLimitSeconds = timedLimitSeconds,
    )
}

/** model.Level → LevelDto(蓝牙/对局内把内置关整关 JSON 化下发的路径)。 */
fun Level.toDto(): LevelDto = LevelDto(
    id = id,
    name = name,
    difficulty = difficulty.name,
    rows = rows,
    cols = cols,
    clueGrid = clueGrid.map { row -> row.toList() },
    answerGrid = answerGrid.map { row -> row.toList() },
    timedLimitSeconds = timedLimitSeconds,
    solves = null,
)

// ---------------- 认证(邮箱 + 6 位验证码) ----------------

/** 第一步:请求发送验证码;服务端规范化邮箱后返回有效期与重发间隔。 */
@Serializable
data class SendCodeRequest(val email: String)

@Serializable
data class SendCodeResponse(
    val email: String,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
    /** true = SMTP 真发成功;false = 服务端回退到控制台打印(开发/演示兜底)。 */
    val delivered: Boolean = false,
    /** 仅服务端开启 XIANGSU_ECHO_CODE 时返回,便于局域网真机演示。 */
    val devCode: String? = null,
)

/** 第二步:验证码登录;isNew 用于引导首次登录设置昵称。 */
@Serializable
data class LoginRequest(
    val email: String,
    val code: String,
    val nickname: String? = null,
)

@Serializable
data class LoginResponse(val userId: Int, val token: String, val nickname: String, val isNew: Boolean)

@Serializable
data class MeResponse(val userId: Int, val nickname: String)

/** 昵称修改:请求携带新昵称,服务端规范化后返回。 */
@Serializable
data class NicknameRequest(val nickname: String)

@Serializable
data class NicknameResponse(val nickname: String)

// ---------------- 排行榜 ----------------

@Serializable
data class SubmitRecordRequest(val puzzleId: Int, val durationMs: Int, val source: String = "single")

@Serializable
data class SubmitRecordResponse(val recordId: Int)

@Serializable
data class BoardEntry(val rank: Int, val userId: Int, val nickname: String, val durationMs: Int)

@Serializable
data class MyBest(val rank: Int, val durationMs: Int)

@Serializable
data class LeaderboardResponse(
    val puzzleId: Int,
    val entries: List<BoardEntry> = emptyList(),
    val myBest: MyBest? = null,
)

@Serializable
data class CreateRoomResponse(val roomId: String)

/**
 * 我打过分的题(用于排行榜"选题目看榜")。
 * 内置关(puzzleId > 0)的名称/难度由客户端本地关卡表补;在线关由服务端给出。
 */
@Serializable
data class PlayedPuzzle(
    val puzzleId: Int,
    val name: String? = null,
    val difficulty: String? = null,
    /** "online" = 在线关(负 id);"builtin" = 内置关(正 id)。 */
    val source: String = "online",
    val plays: Int = 0,
    val lastAt: String? = null,
)

@Serializable
data class MyPuzzlesResponse(val items: List<PlayedPuzzle> = emptyList())

/**
 * 服务端错误体。
 *
 * FastAPI 的 HTTPException(detail=…) 会原样包成 `{"detail": …}`:
 *   - 本项目绝大多数错误是 `detail={"error": "验证码错误"}` → 对象;
 *   - 少数(如 422 参数校验)是 `detail=[…]` → 数组;
 *   - 字符串形式的 detail 也存在。
 *
 * 以前这里把 detail 声明成 `String?`,于是**对象形式的 detail 解码直接失败**,
 * 被 ApiClient.safe 的 runCatching 吞掉,所有服务端文案都退化成"请求失败(400/429)"。
 */
@Serializable
data class ApiError(
    val error: String? = null,
    val detail: JsonElement? = null,
) {
    val message: String
        get() = error
            ?: (detail as? JsonPrimitive)?.contentOrNull
            ?: ((detail as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: "请求失败"
}
