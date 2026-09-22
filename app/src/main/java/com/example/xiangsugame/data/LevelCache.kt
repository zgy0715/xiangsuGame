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

    // 不用 lateinit:进程刚起、AppGraph 还没 init 时若命中缓存读取,
    // lateinit 会抛 UninitializedPropertyAccessException 直接崩;未初始化时按"没缓存"处理即可。
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun get(id: Int): LevelDto? {
        val p = prefs ?: return null
        val raw = p.getString(KEY_PREFIX + id, null) ?: return null
        return runCatching { json.decodeFromString<LevelDto>(raw) }.getOrNull()
    }

    fun put(level: LevelDto) {
        val p = prefs ?: return
        val raw = json.encodeToString(level)
        val editor = p.edit().putString(KEY_PREFIX + level.id, raw)
        val order = p.getString(KEY_ORDER, null) ?: ""
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
