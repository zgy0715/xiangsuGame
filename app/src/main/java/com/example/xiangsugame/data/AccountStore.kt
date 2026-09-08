package com.example.xiangsugame.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 账号进度:按 userId 分片的 SharedPreferences 存储(替代旧版全局 GameProgress)。
 *
 * - 内置关(正 id):通关集合 + 顺序解锁;登录奖励改"每日 +2"(见 [QuotaLedger]);
 * - 在线关(负 id):永不进入解锁链,markCompleted 对非正 id 直接忽略;
 * - 同设备多账号互不干扰(prefs 文件名带 userId)。
 */
class AccountStore(context: Context, val userId: Int) {

    private val prefs = context.getSharedPreferences("xiangsu_account_$userId", Context.MODE_PRIVATE)

    var completedLevels by mutableStateOf(loadCompleted())
        private set

    /** 一键补齐已使用次数(0..MAX)。 */
    var fillQuotaUsed by mutableStateOf(loadFillUsed())
        private set

    var lastBonusDay by mutableStateOf(prefs.getString(KEY_BONUS_DAY, null))
        private set

    val fillQuotaRemaining: Int get() = QuotaLedger.FILL_QUOTA_MAX - fillQuotaUsed

    private fun loadCompleted(): Set<Int> =
        prefs.getStringSet(KEY_COMPLETED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it > 0 }   // 在线关(负 id)不写入通关集
            ?.toSet()
            ?: emptySet()

    private fun loadFillUsed(): Int =
        prefs.getInt(KEY_FILL_USED, 0).coerceIn(0, QuotaLedger.FILL_QUOTA_MAX)

    /** 标记内置关通关(幂等)。在线关通关由 [recordOnlineSolved] 单独记录。 */
    fun markCompleted(levelId: Int) {
        if (levelId <= 0 || levelId in completedLevels) return
        completedLevels = completedLevels + levelId
        prefs.edit()
            .putStringSet(KEY_COMPLETED, completedLevels.map { it.toString() }.toSet())
            .apply()
    }

    /** 已通关的在线关 id(负 id;仅用于"玩过"标记,不参与解锁)。 */
    fun recordOnlineSolved(onlineId: Int) {
        if (onlineId >= 0) return
        onlineSolved = onlineSolved + onlineId
        prefs.edit()
            .putStringSet(KEY_ONLINE_SOLVED, onlineSolved.map { it.toString() }.toSet())
            .apply()
    }

    var onlineSolved by mutableStateOf(loadOnlineSolved())
        private set

    private fun loadOnlineSolved(): Set<Int> =
        prefs.getStringSet(KEY_ONLINE_SOLVED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it < 0 }
            ?.toSet()
            ?: emptySet()

    /** 顺序解锁:第 1 关恒解锁,其余要求前一关已通关(与内置关表强相关)。 */
    fun isUnlocked(levelId: Int): Boolean =
        levelId > 0 && (levelId == 1 || (levelId - 1) in completedLevels)

    fun pruneCompleted(validLevelIds: Set<Int>) {
        val orphans = completedLevels - validLevelIds
        if (orphans.isEmpty()) return
        completedLevels = completedLevels - orphans
        prefs.edit()
            .putStringSet(KEY_COMPLETED, completedLevels.map { it.toString() }.toSet())
            .apply()
    }

    /** 每日登录奖励:跨天才 +2(QuotaLedger),同日幂等。 */
    fun grantDailyBonusIfNewDay(): Boolean {
        val result = QuotaLedger.applyDailyBonus(fillQuotaUsed, lastBonusDay, Dates.todayIso())
        if (result.granted <= 0 && result.lastBonusDay == lastBonusDay) return false
        fillQuotaUsed = result.usedAfter
        lastBonusDay = result.lastBonusDay
        prefs.edit()
            .putInt(KEY_FILL_USED, fillQuotaUsed)
            .putString(KEY_BONUS_DAY, lastBonusDay)
            .apply()
        return true
    }

    /** 消耗一次一键补齐额度;返回是否成功(额度用尽按钮应已灰化)。 */
    fun useFill(): Boolean {
        if (fillQuotaRemaining <= 0) return false
        fillQuotaUsed++
        prefs.edit().putInt(KEY_FILL_USED, fillQuotaUsed).apply()
        return true
    }

    private companion object {
        const val KEY_COMPLETED = "completed_levels"
        const val KEY_ONLINE_SOLVED = "online_solved"
        const val KEY_FILL_USED = "fill_quota_used"
        const val KEY_BONUS_DAY = "last_bonus_day"
    }
}
