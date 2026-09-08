package com.example.xiangsugame.api

import kotlinx.serialization.json.Json

/**
 * 全局共享的 JSON 实例:HTTP / WebSocket / 本地关卡缓存三处共用同一配置,
 * 保证编解码行为一致。ignoreUnknownKeys 让客户端容忍服务端新增字段。
 */
val SharedJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
