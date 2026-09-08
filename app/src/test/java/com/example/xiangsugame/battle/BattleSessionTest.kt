package com.example.xiangsugame.battle

import com.example.xiangsugame.api.dto.LevelDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对战会话状态机:不依赖网络,注入 FakeTransport 驱动帧事件。 */
class BattleSessionTest {

    private class FakeTransport : BattleTransport {
        val sent = mutableListOf<String>()
        var listener: BattleTransport.Listener? = null

        override fun connect(listener: BattleTransport.Listener) {
            this.listener = listener
        }

        override fun send(text: String) {
            sent += text
        }

        override fun close() {}

        fun push(raw: String) = listener?.onFrame(raw)
    }

    private val meId = 7
    private fun levelDto() = LevelDto(
        id = -55, name = "竞速题", difficulty = "MEDIUM", rows = 2, cols = 2,
        clueGrid = listOf(listOf(2, 2), listOf(2, 2)),
        answerGrid = listOf(listOf(1, 1), listOf(1, 1)),
    )

    @Test
    fun `lobby roomState enters lobby and tracks players`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.GUEST, myUserId = meId)
        s.open()
        t.push(BattleProtocol.encode(
            BattleProtocol.DOWN_ROOM_STATE,
            BattleProtocol.RoomStatePayload(
                roomId = "XK9P", hostId = 3, phase = "lobby",
                players = listOf(
                    BattleProtocol.RacePlayer(3, "房主"),
                    BattleProtocol.RacePlayer(meId, "我"),
                ),
            ),
        ))
        assertEquals(BattlePhase.LOBBY, s.phase)
        assertEquals(2, s.players.size)
        assertEquals(3, s.hostId)
        assertFalse(s.pausedForMe)
    }

    @Test
    fun `raceStart flips to racing and materializes level`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.HOST, myUserId = meId)
        s.open()
        t.push(BattleProtocol.encode(
            BattleProtocol.DOWN_RACE_START,
            BattleProtocol.RaceStartPayload(serverStartMs = 1_700_000_000_000L, level = levelDto()),
        ))
        assertEquals(BattlePhase.RACING, s.phase)
        assertNotNull(s.raceLevel)
        assertEquals(-55, s.raceLevel!!.id)
        assertEquals(2, s.raceLevel!!.rows)
    }

    @Test
    fun `opponent progress and finish update players, then result settles`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.GUEST, myUserId = meId)
        s.open()
        val opp = 3
        fun roomState() = BattleProtocol.RoomStatePayload(
            roomId = "R1", hostId = opp, phase = "racing",
            players = listOf(
                BattleProtocol.RacePlayer(opp, "对手"),
                BattleProtocol.RacePlayer(meId, "我"),
            ),
        )
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_ROOM_STATE, roomState()))
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_RACE_START,
            BattleProtocol.RaceStartPayload(serverStartMs = 0L, level = levelDto())))
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_PROGRESS,
            BattleProtocol.ProgressPayload(userId = opp, filled = 3, elapsedMs = 4000L)))
        assertEquals(3, s.players.first { it.userId == opp }.filled)

        // 对手先完成 → 我(未提交)被冻结
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_FINISHED,
            BattleProtocol.FinishPayload(userId = opp, rank = 1, durationMs = 4000L)))
        assertTrue(s.pausedForMe)

        // 我完成并提交
        s.localFinish(arrayOf(intArrayOf(1, 1), intArrayOf(1, 1)), elapsedSeconds = 8)
        val finishFrame = t.sent.last()
        assertTrue(finishFrame.contains("\"type\":\"finish\""))
        assertTrue(s.mySubmitted)

        // 服务器结算
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_RESULT, BattleProtocol.ResultPayload(
            entries = listOf(
                BattleProtocol.ResultEntry(opp, "对手", 1, 4000L),
                BattleProtocol.ResultEntry(meId, "我", 2, 8100L),
            ),
        )))
        assertEquals(BattlePhase.SETTLED, s.phase)
        assertEquals(2, s.myFinalRank)
        assertEquals(2, s.resultEntries.size)
        assertFalse(s.pausedForMe) // 结算后不再"冻结"遮罩
    }

    @Test
    fun `duplicate localFinish sends only once`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.HOST, myUserId = meId)
        s.open()
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_RACE_START,
            BattleProtocol.RaceStartPayload(0L, levelDto())))
        s.localFinish(arrayOf(intArrayOf(1, 1), intArrayOf(1, 1)), 3)
        s.localFinish(arrayOf(intArrayOf(1, 1), intArrayOf(1, 1)), 5)
        assertEquals(1, t.sent.count { it.contains("\"type\":\"finish\"") })
    }

    @Test
    fun `server error surfaces as notice`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.HOST, myUserId = meId)
        s.open()
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_ERROR, BattleProtocol.ErrorPayload("棋盘与答案不一致")))
        assertEquals("棋盘与答案不一致", s.notice)
        s.consumeNotice()
        assertNull(s.notice)
    }

    @Test
    fun `onClosed reason sets fatal and closed phase`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.GUEST, myUserId = meId)
        s.open()
        t.listener?.onClosed("房主已离开,房间解散")
        assertEquals(BattlePhase.CLOSED, s.phase)
        assertEquals("房主已离开,房间解散", s.fatal)
    }
}
