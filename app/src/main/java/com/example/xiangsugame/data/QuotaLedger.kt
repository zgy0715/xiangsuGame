package com.example.xiangsugame.data

/**
 * 一键补齐额度规则(纯 Kotlin,可 JVM 单测,不碰 SharedPreferences):
 *
 * 旧版"每次登录 +2、上限 6"在自动续登后会退化成形同虚设,故改为**每日登录奖励**:
 * 每次(跨天)登录 +[LOGIN_BONUS],未用的额度可跨日累积,上限 [FILL_QUOTA_MAX]。
 * 同日重复登录不重复发放(由 lastBonusDay 判断)。
 */
object QuotaLedger {

    const val FILL_QUOTA_MAX = 6
    const val LOGIN_BONUS = 2

    data class DailyGrant(val usedAfter: Int, val lastBonusDay: String, val granted: Int)

    /**
     * @param fillUsed 已使用额度(0..MAX)
     * @param lastBonusDay 上次发奖的日历日(null = 从未发过)
     * @param today 今天(yyyy-MM-dd)
     */
    fun applyDailyBonus(fillUsed: Int, lastBonusDay: String?, today: String): DailyGrant {
        if (lastBonusDay != null && lastBonusDay >= today) {
            return DailyGrant(fillUsed, lastBonusDay, granted = 0) // 同日不重复发
        }
        val granted = minOf(LOGIN_BONUS, FILL_QUOTA_MAX - fillUsed).coerceAtLeast(0)
        return DailyGrant(fillUsed + granted, today, granted)
    }
}
