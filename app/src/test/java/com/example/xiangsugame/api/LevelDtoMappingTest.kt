package com.example.xiangsugame.api

import com.example.xiangsugame.api.dto.LevelDto
import com.example.xiangsugame.api.dto.toDto
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.Level
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 网络关卡 JSON 契约:Level ↔ LevelDto ↔ JSON 全等往返(服务端字段名稳定性回归)。 */
class LevelDtoMappingTest {

    private fun sampleAnswer(size: Int): Array<IntArray> =
        Array(size) { r -> IntArray(size) { c -> if ((r * size + c) % 3 == 0) 1 else 0 } }

    @Test
    fun `Level toDto toModel round trip preserves everything`() {
        val level = Level.fromAnswer(
            id = -7,
            name = "像素方块 7",
            difficulty = Difficulty.HARD,
            answer = sampleAnswer(14),
        )
        val dto = level.toDto()
        val back = dto.toModel()
        assertEquals(level.id, back.id)
        assertEquals(level.name, back.name)
        assertEquals(level.difficulty, back.difficulty)
        assertEquals(level.rows, back.rows)
        assertEquals(level.cols, back.cols)
        assertEquals(level.timedLimitSeconds, back.timedLimitSeconds)
        for (r in 0 until level.rows) {
            assertEquals(level.clueGrid[r].toList(), back.clueGrid[r].toList())
            assertEquals(level.answerGrid[r].toList(), back.answerGrid[r].toList())
        }
    }

    @Test
    fun `JSON field names match server contract and unknown keys are tolerated`() {
        val dto = LevelDto(
            id = -11, name = "每日一题 2026-09-08", difficulty = "MEDIUM", rows = 2, cols = 2,
            clueGrid = listOf(listOf(2, -1), listOf(-1, 1)),
            answerGrid = listOf(listOf(1, 0), listOf(0, 1)),
        )
        val json = SharedJson.encodeToString(LevelDto.serializer(), dto)
        assertNotNull(json)
        // 服务器返回可能夹带本客户端不认识的字段 → 必须能被忽略
        val withExtras = json.trimEnd('}') + ""","extra":true,"solves":3}"""
        val parsed = SharedJson.decodeFromString<LevelDto>(withExtras)
        assertEquals(-11, parsed.id)
        assertEquals(listOf(listOf(2, -1), listOf(-1, 1)), parsed.clueGrid)
    }

    @Test
    fun `negative id Levels keep sparse -1 and dense 0 to 9 intact via JSON`() {
        val grid = listOf(
            listOf(0, 9, -1),
            listOf(-1, 5, 2),
            listOf(3, -1, 8),
        )
        val json = SharedJson.encodeToString(LevelDto.serializer(), LevelDto(
            id = -1, name = "x", difficulty = "EASY", rows = 3, cols = 3,
            clueGrid = grid, answerGrid = listOf(listOf(0, 1, 0), listOf(1, 0, 1), listOf(0, 0, 1)),
        ))
        val back = SharedJson.decodeFromString<LevelDto>(json)
        assertEquals(grid, back.clueGrid)
        val model = back.toModel()
        assertEquals(3, model.rows)
        assertEquals(9, model.clueGrid[0][1])
    }

    @Test
    fun `unknown difficulty string falls back to MEDIUM`() {
        val dto = SharedJson.decodeFromString<LevelDto>(
            """{"id":-2,"name":"n","difficulty":"IMPOSSIBLE","rows":1,"cols":1,
               |"clueGrid":[[-1]],"answerGrid":[[0]]}""".trimMargin()
        )
        assertEquals(Difficulty.MEDIUM, dto.toModel().difficulty)
    }
}
