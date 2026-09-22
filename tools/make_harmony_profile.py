#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成鸿蒙签名用的 Provision Profile。

设计原则:**默认直接沿用 DevEco SDK 模板里自带的那条证书链**。
`UnsgnedDebugProfileTemplate.json` 的 `bundle-info.development-certificate`
本来就是一条完整的、与签名器格式完全匹配的证书链,比自己现签的更可靠:
  · 自签证书常因链顺序/格式问题被签名器拒(Illegal base64 character 20 之类);
  · 模板证书是 DevEco 自动签名链路里验证过的。

本脚本只做三件事:换包名、刷新有效期、(可选)替换证书。

用法:
  python tools/make_harmony_profile.py --template <模板.json> --bundle <包名> --out <输出.json>
  # 想换成自己的证书链再加:--cert <证书链.cer>
"""
import argparse
import base64
import json
import os
import re
import sys
import time

B64_OK = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")


def load_cert_flat(path):
    """读证书链文件,压成单行 PEM(段间用换行转义连接,行内无空白)。

    证书链里第二、三段常带缩进空格;原样写进 JSON 会让签名器报
    `Illegal base64 character 20`(20 = 空格)。
    """
    with open(path, "r", encoding="utf-8-sig") as f:
        text = f.read()
    parts = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("-----BEGIN") or line.startswith("-----END"):
            parts.append(line)
        else:
            parts.append("".join(line.split()))
    return "\n".join(parts)


def check_cert(value):
    """检查内嵌证书:段数与每段 base64 是否合法。"""
    problems = []
    blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
        value, re.S)
    if not blocks:
        problems.append("没有解析出证书段")
    for i, body in enumerate(blocks):
        compact = "".join(body.split())
        bad = sorted(set(compact) - B64_OK)
        if bad:
            problems.append(f"第{i + 1}段 base64 含非法字符 {bad}")
            continue
        try:
            base64.b64decode(compact, validate=True)
        except Exception as e:  # noqa: BLE001
            problems.append(f"第{i + 1}段 base64 解码失败: {e}")
    return len(blocks), problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", required=True)
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--type", default="debug", choices=["debug", "release"])
    ap.add_argument("--cert", default="", help="留空则沿用模板自带的证书链(推荐)")
    args = ap.parse_args()

    with open(args.template, "r", encoding="utf-8-sig") as f:
        profile = json.load(f)

    if args.cert:
        cert = load_cert_flat(args.cert)
        profile["bundle-info"]["development-certificate"] = cert
        if args.type == "release":
            profile["bundle-info"]["distribution-certificate"] = cert
        src = f"外部证书 {os.path.basename(args.cert)}"
    else:
        cert = profile["bundle-info"].get("development-certificate", "")
        src = "模板自带证书(推荐)"

    profile["bundle-info"]["bundle-name"] = args.bundle
    now = int(time.time())
    profile["validity"]["not-before"] = now - 3600
    profile["validity"]["not-after"] = now + 3600 * 24 * 365 * 10

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(profile, ensure_ascii=False, indent=2))

    blocks, problems = check_cert(cert)
    print(f"[profile] 证书来源: {src}")
    print(f"[profile] 包名={args.bundle} 证书段数={blocks} 长度={len(cert)}")
    if problems:
        for p in problems:
            print(f"[profile] 问题: {p}", file=sys.stderr)
        return 1
    print("[profile] 证书自检通过")
    print(f"[profile] 已写出 {os.path.basename(args.out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
