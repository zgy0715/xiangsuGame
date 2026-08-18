package com.example.xiangsugame

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 关卡进度：已通关关卡集合 + 顺序解锁判定，用 SharedPreferences 持久化。
 *
 * 设计成 Compose 可观察 —— completedLevels 是 State，
 * 通关后首页关卡网格会立即重组反映最新进度（下一关解锁、当前关打勾）。
 *
 * 顺序解锁规则（关卡制）：第 1 关恒解锁，其余关卡要求前一关已通关。
 */
class GameProgress(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已通关的关卡 id 集合。 */
    var completedLevels by mutableStateOf(load())
        private set

    private fun load(): Set<Int> =
        prefs.getStringSet(KEY_COMPLETED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

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

    private companion object {
        const val PREFS_NAME = "xiangsu_progress"
        const val KEY_COMPLETED = "completed_levels"
    }
}
