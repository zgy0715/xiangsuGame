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

安全性:验证码不落明文、10 分钟过期、用后即焚、错误 5 次作废、发送频率受限
(按邮箱 + 按 IP 双维度);AppSecret 之类的第三方密钥不再需要——整个登录链路自持。

⚠️ XIANGSU_ECHO_CODE=1 会把验证码写进 HTTP 响应(仅限本机/局域网演示,生产务必关闭)。
"""
import hashlib
import re
import secrets
import threading
import time
from datetime import datetime

from fastapi import APIRouter, Depends, Header, HTTPException, Request
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

# 昵称最大长度(按码点计)
NICKNAME_MAX_CHARS = 20
# 控制字符、零宽字符、BiDi 覆盖符:会把排行榜/房间列表的排版搞乱,直接剔除
NICKNAME_FORBIDDEN_RE = re.compile(
    "[\x00-\x1f\x7f\u200b-\u200f\u2028\u2029\u202a-\u202e\u2066-\u2069\ufeff]")

# —— 发信频率:IP 维度滑动窗口 ——
# 只按邮箱限流时,攻击者可以拿一份邮箱字典逐个发信,把 SMTP 配额耗光
# (配额一凉,发信失败 → 又可能触发控制台兜底路径)。进程内计数足够单 worker 部署。
IP_WINDOW_SECONDS = 3600
IP_MAX_SENDS_PER_HOUR = 20
_ip_sends = {}
_ip_lock = threading.Lock()


def _ip_throttled(ip: str) -> bool:
    """记录一次发信并判断该 IP 是否已超限(True = 超限,拒绝)。"""
    now = time.time()
    with _ip_lock:
        stamps = [t for t in _ip_sends.get(ip, []) if now - t < IP_WINDOW_SECONDS]
        if len(stamps) >= IP_MAX_SENDS_PER_HOUR:
            _ip_sends[ip] = stamps
            return True
        stamps.append(now)
        _ip_sends[ip] = stamps
        if len(_ip_sends) > 10000:  # 防字典无限增长
            for key in [k for k, v in _ip_sends.items() if not v]:
                _ip_sends.pop(key, None)
        return False


# —— 登录失败节流(邮箱维度滑动窗口)——
# 单个验证码只允许错 5 次,但攻击者每 60 秒重新发码就能刷新配额,连续猜码没有
# 全局兜底(6 位码空间虽大,纵深防御不该只靠它)。
LOGIN_FAIL_WINDOW_SECONDS = 900
LOGIN_FAIL_MAX = 20
_login_fails = {}
_login_lock = threading.Lock()


def _login_blocked(email: str) -> bool:
    now = time.time()
    with _login_lock:
        stamps = [t for t in _login_fails.get(email, []) if now - t < LOGIN_FAIL_WINDOW_SECONDS]
        _login_fails[email] = stamps
        return len(stamps) >= LOGIN_FAIL_MAX


def _record_login_failure(email: str):
    now = time.time()
    with _login_lock:
        stamps = [t for t in _login_fails.get(email, []) if now - t < LOGIN_FAIL_WINDOW_SECONDS]
        stamps.append(now)
        _login_fails[email] = stamps
        if len(_login_fails) > 10000:
            for key in [k for k, v in _login_fails.items() if not v]:
                _login_fails.pop(key, None)


def _clear_login_failures(email: str):
    with _login_lock:
        _login_fails.pop(email, None)


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
async def send_code(req: SendCodeRequest, request: Request):
    email = normalize_email(req.email)

    # IP 维度限流(见 _ip_throttled):挡"拿邮箱字典刷信"这种绕过邮箱限流的用法
    client_ip = (request.client.host if request.client else "") or "unknown"
    if _ip_throttled(client_ip):
        raise HTTPException(status_code=429,
                            detail={"error": "当前网络发信过于频繁,请稍后再试"})

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

    # devCode 回显策略(安全默认):
    #   - 只有显式开启 XIANGSU_ECHO_CODE=1 才回显(本机/局域网演示、答辩现场用);
    #   - SMTP 未配置或发信失败**不回显**,只返回 delivered=False,由客户端提示
    #     "未配置发信,请联系管理员"。
    # 这里以前写的是 `ECHO_OTP_CODE or not actually_delivered`:默认部署根本没配 SMTP,
    # 于是每次发码都把 6 位验证码直接放进 HTTP 响应 —— 知道邮箱就能登录任何账号;
    # SMTP 偶发失败(限流/超时)也会随机走到这条泄露分支。
    show_dev_code = ECHO_OTP_CODE
    # delivered=False 仍然要如实返回:客户端据此提示"服务端没配置发信/发信失败",
    # 但**不再**把验证码本身交出去。
    actually_delivered = (delivered == mailer.DELIVERY_SMTP)

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
    """登录入口:先做邮箱维度失败节流,再进实际校验。

    所有失败路径(Exception)都计入节流,成功则清零 —— 否则攻击者可以靠
    "每 60 秒重新发码" 反复刷新每码 5 次的配额。
    """
    email = normalize_email(req.email)
    if _login_blocked(email):
        raise HTTPException(status_code=429,
                            detail={"error": "尝试次数过多,请稍后再试"})
    try:
        result = _login_inner(req)
    except HTTPException:
        _record_login_failure(email)
        raise
    _clear_login_failures(email)
    return result


def _login_inner(req: LoginRequest):
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
    """昵称校验:剔除控制字符/零宽字符/BiDi 覆盖符,压缩空白,非空且不超过 20 个字符。

    以前只做 strip + 截断,于是换行、零宽字符、RTL 覆盖符都能落库并原样出现在
    排行榜与对战房间里(可以伪造出"多行昵称",或让整行文字显示方向错乱)。
    """
    text = NICKNAME_FORBIDDEN_RE.sub("", raw or "")
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        raise HTTPException(status_code=400, detail={"error": "昵称不能为空"})
    if len(text) > NICKNAME_MAX_CHARS:
        text = text[:NICKNAME_MAX_CHARS].strip()
        if not text:
            raise HTTPException(status_code=400, detail={"error": "昵称不能为空"})
    return text


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
