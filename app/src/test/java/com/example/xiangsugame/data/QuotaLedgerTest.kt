package com.example.xiangsugame.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 每日登录奖励规则(纯逻辑,不依赖 Android)。 */
class QuotaLedgerTest {

    @Test
    fun `cross-day login grants +2 and advances last bonus day`() {
        val r = QuotaLedger.applyDailyBonus(fillUsed = 0, lastBonusDay = "2026-09-07", today = "2026-09-08")
        assertEquals(2, r.usedAfter)
        assertEquals("2026-09-08", r.lastBonusDay)
        assertEquals(2, r.granted)
    }

    @Test
    fun `same-day login does not grant twice`() {
        val r = QuotaLedger.applyDailyBonus(fillUsed = 2, lastBonusDay = "2026-09-08", today = "2026-09-08")
        assertEquals(2, r.usedAfter)
        assertEquals(0, r.granted)
    }

    @Test
    fun `first ever login grants +2`() {
        val r = QuotaLedger.applyDailyBonus(fillUsed = 0, lastBonusDay = null, today = "2026-09-08")
        assertEquals(2, r.granted)
    }

    @Test
    fun `bonus stops at the quota cap of 6`() {
        // 已有 5 次可扣额度(used=1),跨天最多补到 6
        val r = QuotaLedger.applyDailyBonus(fillUsed = 1, lastBonusDay = "2026-09-07", today = "2026-09-08")
        assertEquals(3, r.usedAfter)
        assertEquals(2, r.granted)
        // used=5 满额时跨天也不再发(与旧版"满额幂等"一致)
        val r2 = QuotaLedger.applyDailyBonus(fillUsed = 6, lastBonusDay = "2026-09-07", today = "2026-09-08")
        assertEquals(6, r2.usedAfter)
        assertEquals(0, r2.granted)
        assertEquals("2026-09-08", r2.lastBonusDay)
    }

    @Test
    fun `useFill decreases remaining within bounds`() {
        var used = 0
        for (i in 0 until 6) {
            if (used < QuotaLedger.FILL_QUOTA_MAX) used++
        }
        assertEquals(6, used)
    }
}
