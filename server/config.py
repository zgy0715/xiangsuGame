#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端配置(环境变量可覆盖)。

微信登录接入 seam:
- MOCK_WECHAT=True(默认): /api/auth/login 收到的 code 按 sha1 派生 openid,
  整个登录流程与真实 code2session 同构,仅缺微信侧校验 —— 课程演示/局域网环境使用。
- MOCK_WECHAT=False 时:填入真实 WX_APPID / WX_SECRET 后,同一端点改为调用
  微信官方 jscode2session 校验 code 并取 openid,客户端零改动。
"""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

# SQLite 数据库文件位置(演示可整体拷贝迁移)
DB_PATH = os.environ.get("XIANGSU_DB", str(BASE_DIR / "xiangsu.db"))

# 登录令牌有效期(天)
TOKEN_TTL_DAYS = int(os.environ.get("XIANGSU_TOKEN_TTL_DAYS", "30"))

# ---------------- 微信小程序登录 ----------------
MOCK_WECHAT = os.environ.get("XIANGSU_MOCK_WECHAT", "1") != "0"
WX_APPID = os.environ.get("XIANGSU_WX_APPID", "")
WX_SECRET = os.environ.get("XIANGSU_WX_SECRET", "")

# ---------------- 每日一题(东八区日历日) ----------------
TIMEZONE = os.environ.get("XIANGSU_TZ", "Asia/Shanghai")

# ---------------- 题库各难度保底数量(首次请求或后台预热时补齐) ----------------
BANK_TARGET = {"EASY": 12, "MEDIUM": 10, "HARD": 8}
