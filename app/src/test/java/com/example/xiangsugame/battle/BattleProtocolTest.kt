package com.example.xiangsugame.battle

import com.example.xiangsugame.api.dto.LevelDto
import com.example.xiangsugame.api.dto.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对战帧协议:编码/解码往返 + 容错(与服务端房间协议同 schema 回归)。 */
class BattleProtocolTest {

    private fun sampleDto(id: Int) = LevelDto(
        id = id, name = "测试关$id", difficulty = "EASY", rows = 2, cols = 2,
        clueGrid = listOf(listOf(2, 2), listOf(2, 2)),
        answerGrid = listOf(listOf(1, 1), listOf(1, 1)),
    )

    @Test
    fun `no payload frame round trip`() {
        val raw = BattleProtocol.encode(BattleProtocol.UP_START)
        val (type, payload) = BattleProtocol.decode(raw)
        assertEquals(BattleProtocol.UP_START, type)
        assertNull(payload)
    }

    @Test
    fun `choosePuzzle frame keeps full level through json`() {
        val level = sampleDto(-9)
        val raw = BattleProtocol.encode(BattleProtocol.UP_CHOOSE_PUZZLE, BattleProtocol.ChoosePuzzlePayload(level))
        val (type, payload) = BattleProtocol.decode(raw)
        assertEquals(BattleProtocol.UP_CHOOSE_PUZZLE, type)
        val back = BattleProtocol.parse<BattleProtocol.ChoosePuzzlePayload>(payload)
        assertEquals(level, back?.level)
        assertEquals(-9, back!!.level.toModel().id)
    }

    @Test
    fun `roomState frame round trip with players`() {
        val st = BattleProtocol.RoomStatePayload(
            roomId = "XK9P", hostId = 2, phase = "lobby",
            players = listOf(
                BattleProtocol.RacePlayer(2, "阿强", ready = true),
                BattleProtocol.RacePlayer(5, "小刘"),
            ),
            puzzleName = "红心",
        )
        val raw = BattleProtocol.encode(BattleProtocol.DOWN_ROOM_STATE, st)
        val (type, payload) = BattleProtocol.decode(raw)
        val back = BattleProtocol.parse<BattleProtocol.RoomStatePayload>(payload)
        assertEquals("XK9P", back?.roomId)
        assertEquals(2, back?.players?.size)
        assertEquals("阿强", back?.players?.get(0)?.nickname)
    }

    @Test
    fun `finish frame grid survives`() {
        val raw = BattleProtocol.encode(
            BattleProtocol.UP_FINISH,
            BattleProtocol.FinishUpPayload(grid = listOf(listOf(1, 0), listOf(0, 1)), elapsedMs = 12_345L),
        )
        val back = BattleProtocol.parse<BattleProtocol.FinishUpPayload>(BattleProtocol.decode(raw).second)
        assertEquals(listOf(listOf(1, 0), listOf(0, 1)), back?.grid)
        assertEquals(12_345L, back?.elapsedMs)
    }

    @Test
    fun `garbage and unknown-type frames are tolerated`() {
        val (type, _) = BattleProtocol.decode("not json at all")
        assertEquals("", type)
        val (t2, _) = BattleProtocol.decode("""{"type":"whatever","payload":{"a":1}}""")
        assertEquals("whatever", t2)
        // 未知负载字段被忽略
        val st = BattleProtocol.parse<BattleProtocol.RoomStatePayload>(
            BattleProtocol.decode("""{"type":"roomState","payload":{"roomId":"AB12","extra":1}}""").second,
        )
        assertTrue(st != null)
    }
}
