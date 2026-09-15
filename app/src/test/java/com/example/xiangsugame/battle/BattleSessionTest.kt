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
        assertFalse(s.someoneFinished)
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

        // 对手先完成 → 我不再被冻结,继续做自己的盘面;名次实时可见
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_FINISHED,
            BattleProtocol.FinishPayload(userId = opp, rank = 1, durationMs = 4000L)))
        assertTrue(s.someoneFinished)
        assertFalse(s.awaitingMyResult) // 我还没提交
        assertEquals(1, s.finishedPlayers.size)
        assertEquals(opp, s.finishedPlayers.first().userId)
        assertEquals(1, s.racingCount) // 还有 1 人(我)在做

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
        assertFalse(s.someoneFinished)   // 结算后不再显示"有人完成"横幅
        assertFalse(s.awaitingMyResult)
    }

    /**
     * 关键回归:多人对局中"第一个人完成"不等于"本局结束"。
     * 先完成者的名次对所有人可见,其余人**不被冻结**,可以继续做完,
     * 直到服务器在全员完成后下发 result 才进入结算。
     */
    @Test
    fun `first finisher in multiplayer does not freeze the others`() {
        val t = FakeTransport()
        val s = BattleSession(t, BattleRole.GUEST, myUserId = meId)
        s.open()
        val p1 = 11
        val p2 = 12
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_ROOM_STATE,
            BattleProtocol.RoomStatePayload(
                roomId = "R4", hostId = p1, phase = "racing",
                players = listOf(
                    BattleProtocol.RacePlayer(p1, "甲"),
                    BattleProtocol.RacePlayer(p2, "乙"),
                    BattleProtocol.RacePlayer(meId, "我"),
                ),
            )))
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_RACE_START,
            BattleProtocol.RaceStartPayload(serverStartMs = 0L, level = levelDto())))
        assertEquals(3, s.racingCount)

        // 甲先完成 → 名次播报,我仍可继续(不冻结、不退出)
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_FINISHED,
            BattleProtocol.FinishPayload(userId = p1, rank = 1, durationMs = 3000L)))
        assertTrue(s.someoneFinished)
        assertEquals(1, s.finishedPlayers.first().finishedRank)
        assertEquals("甲", s.finishedPlayers.first().nickname)
        assertEquals(2, s.racingCount)          // 乙 和我都还在做
        assertEquals(BattlePhase.RACING, s.phase) // 仍是竞速阶段,没有提前结算

        // 我也完成 → 服务器还没结算,应保持棋盘可见等待剩余玩家
        s.localFinish(arrayOf(intArrayOf(1, 1), intArrayOf(1, 1)), elapsedSeconds = 5)
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_FINISHED,
            BattleProtocol.FinishPayload(userId = meId, rank = 2, durationMs = 5000L)))
        assertEquals(BattlePhase.RACING, s.phase)
        assertTrue(s.awaitingMyResult)  // 已完成但乙还在做
        assertEquals(1, s.racingCount)

        // 乙最后完成 → 服务器下发权威排行榜(含每人的用时)
        t.push(BattleProtocol.encode(BattleProtocol.DOWN_RESULT, BattleProtocol.ResultPayload(
            entries = listOf(
                BattleProtocol.ResultEntry(p1, "甲", 1, 3000L),
                BattleProtocol.ResultEntry(meId, "我", 2, 5000L),
                BattleProtocol.ResultEntry(p2, "乙", 3, 9500L),
            ),
        )))
        assertEquals(BattlePhase.SETTLED, s.phase)
        assertEquals(3, s.resultEntries.size)
        assertEquals(listOf(1, 2, 3), s.resultEntries.map { it.rank })
        assertEquals(9500L, s.resultEntries.last().durationMs)
        assertFalse(s.someoneFinished)
        assertFalse(s.awaitingMyResult)
    }

    @Test
    fun `duplicate localFinish sends only once`() {        val t = FakeTransport()
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
