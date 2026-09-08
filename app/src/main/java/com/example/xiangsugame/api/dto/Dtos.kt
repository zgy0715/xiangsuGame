package com.example.xiangsugame.api.dto

import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.Level
import kotlinx.serialization.Serializable

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

// ---------------- 认证(模拟微信小程序一键登录) ----------------

@Serializable
data class LoginRequest(val code: String, val nickname: String? = null)

@Serializable
data class LoginResponse(val userId: Int, val token: String, val nickname: String, val isNew: Boolean)

@Serializable
data class MeResponse(val userId: Int, val nickname: String)

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

/** 服务端错误体: {"error": "..."}(FastAPI HTTPException detail 透传)。 */
@Serializable
data class ApiError(val error: String? = null, val detail: String? = null) {
    val message: String get() = error ?: detail ?: "请求失败"
}
