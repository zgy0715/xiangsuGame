package com.example.xiangsugame.battle

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.example.xiangsugame.auth.AuthManager

/**
 * 蓝牙对局身份 —— 与登录账号解耦。
 *
 * 蓝牙是双机直连(RFCOMM),没有服务器、也没有账号体系,但整套竞速状态机是**按 userId 认人**的
 * (`players.firstOrNull { it.userId != myUserId }` 找对手、`p.userId == mySession.myUserId` 标"我")。
 * 以前蓝牙直接用登录 userId 当身份,于是有两类"连上了却打不了":
 *  1. 两台手机都是游客:userId 都是 -99 → 互相被当成同一个人 → 永远找不到"对手",界面一直停在
 *     「正在等待对方连接…」,而其实蓝牙早就连上了;
 *  2. 两台手机登录同一个邮箱账号:userId 相同 → 顶部两条进度条会同时标成"(你)"、结算也会认错人。
 *
 * 现在:登录用户仍用服务端 id;游客(或未登录)用本机 ANDROID_ID 派生的**稳定负值** ——
 * 两台设备天然不同,又不会与服务端的正数 id 冲突,也不依赖任何权限(ANDROID_ID 无需申请)。
 */
object BattleIdentity {

    /** 本机在蓝牙对局里的身份 id。 */
    fun localUserId(context: Context): Int {
        val session = AuthManager.session
        if (session != null && session.userId > 0) return session.userId  // 登录用户:沿用服务端 id
        return guestId(context)
    }

    /**
     * 游客身份:由 ANDROID_ID 哈希成负数。
     * 同一台设备每次都一样(重连/重开对局身份不变),不同设备几乎不可能相同。
     */
    private fun guestId(context: Context): Int {
        val raw = runCatching { androidId(context) }.getOrNull().orEmpty()
        val seed = if (raw.isBlank()) "xiangsu-guest-unknown" else "xiangsu-guest-$raw"
        var id = seed.hashCode()
        if (id >= 0) id = -id
        // 避开占位值,免得又撞上"未登录(-1)"或历史游客写法(-99)
        if (id == 0 || id == -1 || id == -99) id -= 4096
        return id
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /**
     * 对局里显示的昵称。
     * 两端都是游客时昵称都叫"游客",在玩家列表与结算里分不清谁是谁,这里补一个 3 位尾号。
     */
    fun localNickname(context: Context, id: Int): String {
        val session = AuthManager.session
        if (session != null && !AuthManager.isGuest && session.nickname.isNotBlank()) {
            return session.nickname
        }
        val suffix = (kotlin.math.abs(id.toLong()) % 900L + 100L).toInt()
        return "游客$suffix"
    }
}
