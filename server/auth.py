#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""认证模块:无用户名密码 —— 小程序风格一键登录(code → openid → token)。

默认 MOCK_WECHAT:code 不请求微信,sha1 派生 openid(客户端每次生成随机 code,
同一设备稳定得到同一账号)。配置见 config.py:关闭 mock 并填真实 AppID/Secret 后,
同一端点无缝切换为官方 jscode2session,客户端零改动。
"""
import hashlib
import json
import urllib.parse
import urllib.request

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel

import db
from config import MOCK_WECHAT, TOKEN_TTL_DAYS, WX_APPID, WX_SECRET

router = APIRouter(prefix="/api/auth", tags=["auth"])


class LoginRequest(BaseModel):
    code: str = ""
    nickname: str | None = None


class AuthError(Exception):
    pass


def _mock_openid(code):
    # 演示模式:任何非空 code 都派生稳定 openid(与微信真实 openid 的用途等价)
    if not code:
        raise AuthError("code 为空")
    digest = hashlib.sha1(f"xiangsu-mock|{code}".encode("utf-8")).hexdigest()
    return "mock_" + digest[:24]


def _wx_openid(code):
    """真实微信小程序登录(jscode2session)。需 config.WX_APPID/WX_SECRET。"""
    params = urllib.parse.urlencode({
        "appid": WX_APPID, "secret": WX_SECRET,
        "js_code": code, "grant_type": "authorization_code",
    })
    try:
        with urllib.request.urlopen(
                f"https://api.weixin.qq.com/sns/jscode2session?{params}", timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        raise AuthError(f"微信服务不可达: {e}")
    if "openid" not in body:
        raise AuthError("微信校验失败: " + body.get("errmsg", "unknown"))
    return body["openid"]


def code_to_openid(code):
    return _mock_openid(code) if MOCK_WECHAT else _wx_openid(code)


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


@router.post("/login")
def login(req: LoginRequest):
    try:
        openid = code_to_openid(req.code)
    except AuthError as e:
        raise HTTPException(status_code=400, detail={"error": str(e)})
    user_id, is_new = db.get_or_create_user(openid, req.nickname)
    nickname = db.query_one("SELECT nickname FROM users WHERE user_id = ?", (user_id,))["nickname"]
    token = db.create_token(user_id, TOKEN_TTL_DAYS)
    return {"userId": user_id, "token": token, "nickname": nickname, "isNew": is_new}


@router.get("/me")
def me(user=Depends(current_user)):
    return {"userId": user["user_id"], "nickname": user["nickname"]}


@router.post("/logout")
def logout(user=Depends(current_user)):
    db.delete_token(user["token"])
    return {"ok": True}
