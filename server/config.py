#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端配置(环境变量可覆盖)。

登录方式:邮箱 + 6 位验证码(无用户名密码)。
  1) 客户端 POST /api/auth/send-code  {email}    → 服务端生成 6 位码,存哈希,SMTP 发信;
  2) 客户端 POST /api/auth/login      {email, code} → 校验并消费该码,签发 Bearer token。

两种发信模式(见 mailer.py):
  - XIANGSU_SMTP_HOST 已配置 → 真实 SMTP 发信(QQ邮箱/163 等,使用「授权码」而非登录密码);
  - 未配置或发信失败       → 回退到控制台打印验证码(开发/演示兜底,登录依然可用)。
XIANGSU_ECHO_CODE=1 时接口额外返回 devCode 字段,便于局域网真机演示(默认关闭)。
"""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

# SQLite 数据库文件位置(演示可整体拷贝迁移)
DB_PATH = os.environ.get("XIANGSU_DB", str(BASE_DIR / "xiangsu.db"))

# 登录令牌有效期(天)
TOKEN_TTL_DAYS = int(os.environ.get("XIANGSU_TOKEN_TTL_DAYS", "30"))

# ---------------- 邮箱验证码 ----------------
# 验证码有效期(秒):默认 10 分钟
OTP_TTL_SECONDS = int(os.environ.get("XIANGSU_OTP_TTL_SECONDS", "600"))
# 同一邮箱两次发送的最小间隔(秒)
OTP_RESEND_INTERVAL_SECONDS = int(os.environ.get("XIANGSU_OTP_RESEND_SECONDS", "60"))
# 同一邮箱每小时最多发送次数 / 每天最多发送次数
OTP_MAX_SENDS_PER_HOUR = int(os.environ.get("XIANGSU_OTP_MAX_SENDS_HOUR", "5"))
OTP_MAX_SENDS_PER_DAY = int(os.environ.get("XIANGSU_OTP_MAX_SENDS_DAY", "15"))
# 单个验证码最多允许校验失败次数(超过即作废,防暴力猜码)
OTP_MAX_ATTEMPTS = int(os.environ.get("XIANGSU_OTP_MAX_ATTEMPTS", "5"))
# 1 = 接口响应里带上 devCode(仅演示用,生产务必保持 0)
ECHO_OTP_CODE = os.environ.get("XIANGSU_ECHO_CODE", "0") == "1"

# ---------------- SMTP 发信(QQ邮箱示例)----------------
# QQ邮箱:SMTP_HOST=smtp.qq.com, SMTP_PORT=465, SMTP_SSL=1, SMTP_USER=你的QQ邮箱, SMTP_PASS=授权码
# 163邮箱:SMTP_HOST=smtp.163.com, SMTP_PORT=465, SMTP_SSL=1, SMTP_USER=你的163邮箱, SMTP_PASS=授权码
SMTP_HOST = os.environ.get("XIANGSU_SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("XIANGSU_SMTP_PORT", "465"))
SMTP_SSL = os.environ.get("XIANGSU_SMTP_SSL", "1") != "0"
SMTP_USER = os.environ.get("XIANGSU_SMTP_USER", "")
SMTP_PASS = os.environ.get("XIANGSU_SMTP_PASS", "")
SMTP_FROM = os.environ.get("XIANGSU_SMTP_FROM", "") or SMTP_USER
SMTP_SENDER_NAME = os.environ.get("XIANGSU_SMTP_NAME", "像素填空")

# ---------------- 每日一题(东八区日历日) ----------------
TIMEZONE = os.environ.get("XIANGSU_TZ", "Asia/Shanghai")

# ---------------- 题库各难度保底数量(首次请求或后台预热时补齐) ----------------
BANK_TARGET = {"EASY": 12, "MEDIUM": 10, "HARD": 8}
