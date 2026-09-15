#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""认证模块:邮箱 + 6 位验证码登录(无用户名密码)。

流程(两步,客户端与服务端契约见 api/dto/Dtos.kt):
  1) POST /api/auth/send-code  {email}
     → 校验邮箱格式 → 限流(重发间隔/每小时/每天)→ 生成 6 位码 →
       只存 sha256 哈希(db.email_otps)→ SMTP 发信(失败回退控制台)→ 返回有效期
  2) POST /api/auth/login      {email, code}
     → 取该邮箱最新验证码 → 校验未过期/未使用/未超失败次数 → 比对哈希 →
       消费该码(单次使用)→ get_or_create_user → 签发 Bearer token(30 天)

安全性:验证码不落明文、10 分钟过期、用后即焚、错误 5 次作废、发送频率受限;
AppSecret 之类的第三方密钥不再需要——整个登录链路自持,可离线自测。
"""
import hashlib
import re
import secrets
from datetime import datetime

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

import db
import mailer
from config import (OTP_MAX_ATTEMPTS, OTP_MAX_SENDS_PER_DAY, OTP_MAX_SENDS_PER_HOUR,
                    OTP_RESEND_INTERVAL_SECONDS, OTP_TTL_SECONDS, TOKEN_TTL_DAYS,
                    ECHO_OTP_CODE)

router = APIRouter(prefix="/api/auth", tags=["auth"])

# 邮箱校验:宽松但足够挡掉明显非法输入(真正的可达性由"收到验证码"证明)
EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$")

# 验证码固定 6 位十进制
CODE_RE = re.compile(r"^\d{6}$")


def normalize_email(raw: str) -> str:
    """邮箱规范化:去首尾空白 + 转小写。非法则抛 400。"""
    email = (raw or "").strip().lower()
    if len(email) > 254 or not EMAIL_RE.match(email):
        raise HTTPException(status_code=400, detail={"error": "邮箱格式不正确,请检查后重试"})
    local, _, domain = email.partition("@")
    if len(local) > 64 or domain.startswith(".") or domain.endswith(".") or ".." in email:
        raise HTTPException(status_code=400, detail={"error": "邮箱格式不正确,请检查后重试"})
    return email


def generate_code() -> str:
    """6 位数字验证码(cryptographic RNG,首位可以是 0)。"""
    return f"{secrets.randbelow(1_000_000):06d}"


def hash_code(email: str, code: str) -> str:
    """验证码哈希:sha256(email|code),库中不留明文。"""
    return hashlib.sha256(f"{email}|{code}".encode("utf-8")).hexdigest()


def _parse_utc(value: str) -> datetime:
    return datetime.strptime(value, "%Y-%m-%d %H:%M:%S")


def _seconds_since_send(email: str):
    """距上次发码的秒数;从未发过返回 None。"""
    last = db.last_otp_sent_at(email)
    if not last:
        return None
    return (datetime.utcnow() - _parse_utc(last)).total_seconds()


def _bearer_token(authorization):
    if not authorization or not authorization.startswith("Bearer "):
        return None
    return authorization[len("Bearer "):].strip() or None


def current_user(authorization: str | None = Header(default=None)):
    """依赖注入:校验 Bearer token,失败抛 401。"""
    token = _bearer_token(authorization)
    row = db.user_by_token(token) if token else None
    if row is None:
        raise HTTPException(status_code=401, detail={"error": "未登录或登录已过期"})
    return row


def current_user_optional(authorization: str | None = Header(default=None)):
    """可选登录:排行榜公开数据不强制登录,只有 myBest 需要身份。"""
    token = _bearer_token(authorization)
    return db.user_by_token(token) if token else None


# ---------------- 第一步:发送验证码 ----------------

class SendCodeRequest(BaseModel):
    email: str = ""


class SendCodeResponse(BaseModel):
    email: str
    expiresInSeconds: int
    resendAfterSeconds: int
    delivered: bool          # True = SMTP 真发成功;False = 回退控制台
    devCode: str | None = None  # 仅 XIANGSU_ECHO_CODE=1 时返回(演示用)


@router.post("/send-code", response_model=SendCodeResponse)
async def send_code(req: SendCodeRequest):
    email = normalize_email(req.email)

    # —— 限流:重发间隔 → 每小时 → 每天 ——
    since = _seconds_since_send(email)
    if since is not None and since < OTP_RESEND_INTERVAL_SECONDS:
        wait = int(OTP_RESEND_INTERVAL_SECONDS - since) + 1
        raise HTTPException(status_code=429, detail={
            "error": f"发送太频繁,请 {wait} 秒后再试", "retryAfterSeconds": wait})
    if db.count_otps_since(email, 3600) >= OTP_MAX_SENDS_PER_HOUR:
        raise HTTPException(status_code=429,
                            detail={"error": "本邮箱 1 小时内发送次数过多,请稍后再试"})
    if db.count_otps_since(email, 86400) >= OTP_MAX_SENDS_PER_DAY:
        raise HTTPException(status_code=429,
                            detail={"error": "本邮箱今日发送次数已达上限,请明天再试"})

    code = generate_code()
    db.insert_otp(email, hash_code(email, code), OTP_TTL_SECONDS)

    # 发信是阻塞 IO:放线程池,失败自动回退控制台(见 mailer)
    delivered, error = await run_in_threadpool(
        mailer.send_code_email, email, code, max(1, OTP_TTL_SECONDS // 60))

    # devCode 回显策略:
    #   - 显式开启 XIANGSU_ECHO_CODE=1:始终回显(演示/答辩用)
    #   - SMTP 未配置或发信失败(delivered != smtp):自动回显,保证"没配邮箱授权码也能登录"
    #     —— 解决"邮箱没开 SMTP 服务就压根登不上"的可用性问题
    #   - SMTP 真发成功:不回显,正常走邮箱收码
    actually_delivered = (delivered == mailer.DELIVERY_SMTP)
    show_dev_code = ECHO_OTP_CODE or not actually_delivered

    return SendCodeResponse(
        email=email,
        expiresInSeconds=OTP_TTL_SECONDS,
        resendAfterSeconds=OTP_RESEND_INTERVAL_SECONDS,
        delivered=actually_delivered,
        devCode=code if show_dev_code else None,
    )


# ---------------- 第二步:验证码登录 ----------------

class LoginRequest(BaseModel):
    email: str = ""
    code: str = ""
    nickname: str | None = None


@router.post("/login")
def login(req: LoginRequest):
    email = normalize_email(req.email)
    code = (req.code or "").strip()
    if not CODE_RE.match(code):
        raise HTTPException(status_code=400, detail={"error": "请输入 6 位数字验证码"})

    otp = db.latest_otp(email)
    if otp is None:
        raise HTTPException(status_code=400, detail={"error": "请先获取验证码"})
    if otp["used"]:
        raise HTTPException(status_code=400, detail={"error": "验证码已使用,请重新获取"})
    if otp["attempts"] >= OTP_MAX_ATTEMPTS:
        raise HTTPException(status_code=400, detail={"error": "错误次数过多,请重新获取验证码"})
    if datetime.utcnow() > _parse_utc(otp["expires_at"]):
        raise HTTPException(status_code=400, detail={"error": "验证码已过期,请重新获取"})

    submitted = hash_code(email, code)
    if not secrets.compare_digest(otp["code_hash"], submitted):
        # 用户填的很可能是上一封邮件的旧码(邮件到达有延迟,重发后常见):明确提示,且不计错误次数
        old = db.otp_by_hash(email, submitted)
        if old is not None:
            raise HTTPException(status_code=400,
                                detail={"error": "该验证码已失效,请使用最新收到的验证码"})
        db.bump_otp_attempts(otp["otp_id"])
        left = OTP_MAX_ATTEMPTS - otp["attempts"] - 1
        msg = "验证码错误" if left > 0 else "验证码错误次数过多,请重新获取"
        raise HTTPException(status_code=400, detail={"error": msg})

    # 单次使用:并发提交时只有一方 rowcount=1,另一方按"已使用"拒绝
    if db.consume_otp(otp["otp_id"]) != 1:
        raise HTTPException(status_code=400, detail={"error": "验证码已使用,请重新获取"})

    nickname_hint = normalize_nickname(req.nickname) if (req.nickname or "").strip() else None
    user_id, is_new = db.get_or_create_user(email, nickname_hint)
    nickname = db.query_one("SELECT nickname FROM users WHERE user_id = ?", (user_id,))["nickname"]
    token = db.create_token(user_id, TOKEN_TTL_DAYS)
    return {"userId": user_id, "token": token, "nickname": nickname, "isNew": is_new}


@router.get("/me")
def me(user=Depends(current_user)):
    return {"userId": user["user_id"], "nickname": user["nickname"]}


class NicknameRequest(BaseModel):
    nickname: str = ""


def normalize_nickname(raw: str) -> str:
    """昵称校验:去首尾空白,非空且不超过 20 个字符。返回规范化结果,非法抛 400。"""
    name = (raw or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail={"error": "昵称不能为空"})
    return name[:20]


@router.post("/nickname")
def change_nickname(req: NicknameRequest, user=Depends(current_user)):
    """修改昵称:仅真实登录用户可改(游客为离线身份,不经本端点)。"""
    name = normalize_nickname(req.nickname)
    db.set_nickname(user["user_id"], name)
    return {"nickname": name}


@router.post("/logout")
def logout(user=Depends(current_user)):
    db.delete_token(user["token"])
    return {"ok": True}
