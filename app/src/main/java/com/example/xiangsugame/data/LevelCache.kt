package com.example.xiangsugame.data

import android.content.Context
import com.example.xiangsugame.api.SharedJson
import com.example.xiangsugame.api.dto.LevelDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 在线关卡本地缓存(SharedPreferences,JSON 字符串):
 * key = "lv_<id>",上限 [MAX_ENTRIES] 条,按写入序淘汰最旧 —— 断网也能玩玩过的关。
 */
object LevelCache {

    private const val PREFS = "xiangsu_level_cache"
    private const val KEY_ORDER = "order"
    private const val KEY_PREFIX = "lv_"
    private const val MAX_ENTRIES = 30

    private val json: Json get() = SharedJson

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun get(id: Int): LevelDto? {
        val raw = prefs.getString(KEY_PREFIX + id, null) ?: return null
        return runCatching { json.decodeFromString<LevelDto>(raw) }.getOrNull()
    }

    fun put(level: LevelDto) {
        val raw = json.encodeToString(level)
        val editor = prefs.edit().putString(KEY_PREFIX + level.id, raw)
        val order = prefs.getString(KEY_ORDER, null) ?: ""
        val ids = order.split(",").mapNotNull { it.toIntOrNull() }.toMutableList()
        ids.removeAll { it == level.id } // 重复写入只更新不重复排队
        ids.add(0, level.id)
        while (ids.size > MAX_ENTRIES) {
            val evict = ids.removeAt(ids.lastIndex)
            editor.remove(KEY_PREFIX + evict)
        }
        editor.putString(KEY_ORDER, ids.joinToString(",")).apply()
    }
}
