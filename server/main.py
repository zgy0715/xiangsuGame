#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""像素填空 服务端入口。

运行(仓库根目录):
  cd server
  pip install -r requirements.txt
  python -m uvicorn main:app --host 0.0.0.0 --port 8000

局域网演示:手机与电脑同一 Wi-Fi,电脑防火墙放行 8000 端口,
App「设置」里把服务器地址填成 http://<电脑局域网IP>:8000。
登录为「邮箱 + 6 位验证码」:配置 XIANGSU_SMTP_* 后真实发信,未配置则验证码打印在
控制台(见 config.py / mailer.py)。
启动即建表,后台线程预热题库(EASY/MEDIUM/HARD 各保底若干题)。
"""
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

import content
import db
import mailer
from auth import router as auth_router
from config import BANK_TARGET, CORS_ORIGINS
from leaderboard import router as leaderboard_router
from puzzles import router as puzzles_router
from rooms import router as rooms_router


def warm_bank():
    """启动预热:前台不阻塞,首次 GET 也会惰性补齐(见 puzzles.py)。"""
    print("[warm] 预热题库中...")
    conn = db.connect()
    try:
        for difficulty, target in BANK_TARGET.items():
            try:
                content.ensure_bank(conn, difficulty, target)
                print(f"[warm] {difficulty} 保底 {target} 题就绪")
            except RuntimeError as e:
                print(f"[warm] {difficulty} 预热失败(将惰性重试): {e}")
    finally:
        conn.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    db.purge_expired_otps()  # 清理历史验证码记录
    threading.Thread(target=warm_bank, daemon=True).start()
    yield


app = FastAPI(title="像素填空 · 康思游戏服务端", version="1.0", lifespan=lifespan)

# CORS 默认关闭(原生 App 不需要)。以前写死 allow_origins=["*"],
# 任意网页都能跨域调用 /api/auth/send-code 这类匿名端点并读取响应。
if CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=CORS_ORIGINS, allow_methods=["*"], allow_headers=["*"],
    )

app.include_router(auth_router)
app.include_router(puzzles_router)
app.include_router(leaderboard_router)
app.include_router(rooms_router)


@app.get("/")
def root():
    return {"name": "xiangsu-game-server",
            "message": "像素填空服务端在线",
            "docs": "/docs",
            "login": "email-otp",
            "smtp": mailer.smtp_configured()}
