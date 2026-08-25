package com.example.xiangsugame.model

/**
 * 登录身份（双身份权限系统）：
 *  - 玩家（PLAYER）：仅按关卡顺序解锁，无任何特权；
 *  - 管理员（ADMIN）：全局解锁特权，可直接开启全部常规关 + 隐藏关。
 */
enum class UserRole { PLAYER, ADMIN }
