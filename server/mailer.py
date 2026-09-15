#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""邮件发送:SMTP 真发 + 控制台兜底。

设计要点:
- SMTP 未配置(或发信抛异常)时 **不阻断登录**:验证码照常入库,同时打印到服务端
  控制台,开发期与答辩现场即使邮箱进垃圾箱/网络不通也能继续登录;
- 用标准库 smtplib,不引入额外依赖(requirements.txt 只需 fastapi + uvicorn);
- 发信是阻塞 IO,调用方必须在工作线程里执行(见 auth.py 用 run_in_threadpool),避免卡住事件循环。
"""
import smtplib
import ssl
from email.header import Header
from email.mime.text import MIMEText
from email.utils import formataddr

from config import (SMTP_FROM, SMTP_HOST, SMTP_PASS, SMTP_PORT, SMTP_SENDER_NAME,
                    SMTP_SSL, SMTP_USER)

# 发信结果:("smtp", None) 真实发出;("console", 失败原因) 回退到控制台
DELIVERY_SMTP = "smtp"
DELIVERY_CONSOLE = "console"


def smtp_configured() -> bool:
    """是否具备真实发信条件(主机 + 账号 + 授权码三者齐全)。"""
    return bool(SMTP_HOST and SMTP_USER and SMTP_PASS)


def _compose(email: str, code: str, ttl_minutes: int) -> MIMEText:
    text = (
        f"【像素填空】登录验证码\n\n"
        f"你的验证码是:{code}\n\n"
        f"有效期 {ttl_minutes} 分钟,仅可使用一次。\n"
        f"请勿把验证码告知他人;若非本人操作,忽略本邮件即可。\n"
    )
    msg = MIMEText(text, "plain", "utf-8")
    msg["Subject"] = Header("【像素填空】登录验证码", "utf-8")
    msg["From"] = formataddr((str(Header(SMTP_SENDER_NAME, "utf-8")), SMTP_FROM))
    msg["To"] = email
    return msg


def send_code_email(email: str, code: str, ttl_minutes: int = 10):
    """发送验证码邮件。返回 (方式, 错误信息);方式为 smtp 表示真实发出。"""
    if not smtp_configured():
        _print_code(email, code, "未配置 SMTP")
        return DELIVERY_CONSOLE, "未配置 SMTP"

    msg = _compose(email, code, ttl_minutes)
    try:
        if SMTP_SSL:
            context = ssl.create_default_context()
            with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15, context=context) as s:
                s.login(SMTP_USER, SMTP_PASS)
                s.sendmail(SMTP_FROM, [email], msg.as_string())
        else:
            with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15) as s:
                s.ehlo()
                s.starttls(context=ssl.create_default_context())
                s.login(SMTP_USER, SMTP_PASS)
                s.sendmail(SMTP_FROM, [email], msg.as_string())
    except Exception as e:  # 网络/授权码错误/被拦截等:一律回退控制台,保证登录可用
        reason = f"{type(e).__name__}: {e}"
        _print_code(email, code, f"SMTP 发信失败({reason})")
        return DELIVERY_CONSOLE, reason
    print(f"[mail] 验证码已发送至 {email}", flush=True)
    return DELIVERY_SMTP, None


def _print_code(email: str, code: str, why: str) -> None:
    """控制台兜底:醒目打印验证码,便于开发与现场演示。"""
    print("\n" + "=" * 46, flush=True)
    print(f"[mail-fallback] {why},改用控制台输出验证码", flush=True)
    print(f"[mail-fallback] 邮箱: {email}", flush=True)
    print(f"[mail-fallback] 验证码: {code}", flush=True)
    print("=" * 46 + "\n", flush=True)
