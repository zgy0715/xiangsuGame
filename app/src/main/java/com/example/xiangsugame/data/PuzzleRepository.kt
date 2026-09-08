package com.example.xiangsugame.data

import android.content.Context
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.DailyLevelResponse
import com.example.xiangsugame.api.dto.toDto
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.model.Level

/**
 * 在线关卡仓库:内存 LRU → 本地缓存 → 网络(成功后回写两级缓存)。
 * 断网时玩过的关仍可玩;未缓存的新关返回失败并带可展示文案。
 */
object PuzzleRepository {

    private val memory = object : LinkedHashMap<Int, Level>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Level>?): Boolean = size > 16
    }

    fun init(context: Context) {
        LevelCache.init(context)
    }

    /** 取完整在线关卡(含答案)。id 为负的服务端题号;每日题按 id 天然隔离(日期轮换即换 id)。 */
    suspend fun fetchPuzzle(id: Int, forceRefresh: Boolean = false): Result<Level> {
        memory[id]?.let { return Result.success(it) }
        if (!forceRefresh) {
            LevelCache.get(id)?.toModel()?.let { cached ->
                memory[id] = cached
                return Result.success(cached)
            }
        }
        val level = ApiClient.safe { ApiClient.api().puzzle(id) }.map { it.toModel() }
        level.onSuccess { l ->
            memory[id] = l
            runCatching { LevelCache.put(l.toDto()) }
        }
        return level
    }

    /** 每日一题:携带日期(同一日历日全服同题);失败返回未连接服务器文案。 */
    suspend fun fetchDaily(date: String? = null): Result<DailyLevelResponse> =
        ApiClient.safe { ApiClient.api().daily(date) }

    fun rememberLevel(id: Int): Level? = memory[id]
}
