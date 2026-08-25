package com.example.xiangsugame

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 关卡进度：已通关关卡集合 + 顺序解锁判定 + 一键补齐全局额度，用 SharedPreferences 持久化。
 *
 * 设计成 Compose 可观察 —— completedLevels / fillQuotaUsed 是 State，
 * 通关后首页关卡网格会立即重组反映最新进度（下一关解锁、当前关打勾），
 * 一键补齐次数用尽后游戏页按钮立即灰化。
 *
 * 顺序解锁规则（关卡制）：第 1 关恒解锁，其余关卡要求前一关已通关；
 * 隐藏关（id=11）依赖线性规则自动实现"需先通关全部 10 个常规关"。
 *
 * 一键补齐额度：全游戏仅 2 次，所有关卡共用，不支持单关重置、不支持刷新，
 * 次数持久化，重启不恢复。
 */
class GameProgress(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已通关的关卡 id 集合。 */
    var completedLevels by mutableStateOf(load())
        private set

    /** 一键补齐已使用的次数（0..2）。 */
    var fillQuotaUsed by mutableStateOf(loadFillUsed())
        private set

    /** 一键补齐剩余可用次数。 */
    val fillQuotaRemaining: Int get() = FILL_QUOTA_TOTAL - fillQuotaUsed

    private fun load(): Set<Int> =
        prefs.getStringSet(KEY_COMPLETED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    private fun loadFillUsed(): Int =
        prefs.getInt(KEY_FILL_USED, 0).coerceIn(0, FILL_QUOTA_TOTAL)

    /** 标记某关通关并持久化（幂等：重复通关不重复写）。 */
    fun markCompleted(levelId: Int) {
        if (levelId in completedLevels) return
        completedLevels = completedLevels + levelId
        prefs.edit()
            .putStringSet(KEY_COMPLETED, completedLevels.map { it.toString() }.toSet())
            .apply()
    }

    /** 关卡是否解锁：第 1 关恒解锁，其余要求前一关已通关。 */
    fun isUnlocked(levelId: Int): Boolean = levelId == 1 || (levelId - 1) in completedLevels

    /**
     * 消耗一次一键补齐额度。额度用尽返回 false（按钮已灰化，正常不会走到）。
     * @return 是否成功扣减（即本次可以使用一键补齐）
     */
    fun useFill(): Boolean {
        if (fillQuotaRemaining <= 0) return false
        fillQuotaUsed++
        prefs.edit()
            .putInt(KEY_FILL_USED, fillQuotaUsed)
            .apply()
        return true
    }

    private companion object {
        const val PREFS_NAME = "xiangsu_progress"
        const val KEY_COMPLETED = "completed_levels"
        const val KEY_FILL_USED = "fill_quota_used"
        const val FILL_QUOTA_TOTAL = 2
    }
}
