#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按钮可用性审计:找出会"按了没反应"的按钮写法。

背景(真机上真实踩过的坑):
  ArkUI 的 `enabled(false)` 完全不响应点击 —— 没有回调、没有 toast、没有视觉反馈,
  用户看到的就是"这个按钮坏了"。所以我方约定:
    **除非有明显的视觉理由(倒计时/加载中),否则不要用 enabled 关掉按钮;
     无效时应当可点 + 给出提示。**

本脚本扫描 harmony/ets 下的 .ets 文件,报告:
  [E1] 用了 .enabled(...) 的按钮(需人工确认禁用理由是否对用户可见)
  [E2] Button 缺失 .onClick(点了必然没反应)
  [E3] 非 Button 组件挂了 .onClick(可点但不像按钮,可能点不中)
  [E4] 相邻按钮的点击热区过小(<32vp 高)或一排超过 4 个(手机上挤)

用法:python tools/audit_buttons.py
"""
import glob
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
UI_DIRS = [os.path.join(ROOT, "harmony", "ets", "ui"),
           os.path.join(ROOT, "harmony", "ets", "pages")]

BUTTON_START = re.compile(r"\bButton\s*\(")
ENABLED = re.compile(r"\.enabled\s*\(")
ONCLICK = re.compile(r"\.onClick\s*\(")
HEIGHT = re.compile(r"\.height\s*\(\s*(\d+)")
NON_BUTTON_CLICK = re.compile(r"^\s*\.onClick\s*\(")


def collect_button_blocks(lines):
    """把每个 Button(...) 到下一个 Button / 文件末尾之间当成一个块。"""
    blocks = []
    starts = [i for i, ln in enumerate(lines) if BUTTON_START.search(ln)]
    for idx, start in enumerate(starts):
        end = starts[idx + 1] if idx + 1 < len(starts) else len(lines)
        blocks.append((start + 1, lines[start:end]))  # 1-based 行号
    return blocks


def audit_file(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    findings = []

    for line_no, block in collect_button_blocks(lines):
        text = "\n".join(block)
        label_match = re.search(r"Button\s*\(([^)]{0,40})", block[0])
        label = label_match.group(1).strip() if label_match else "?"
        if ENABLED.search(text):
            cond = ENABLED.search(text).start()
            snippet = text[cond:cond + 60].split("\n")[0]
            findings.append(("E1", line_no, f"Button({label}) {snippet}"))
        if not ONCLICK.search(text):
            findings.append(("E2", line_no, f"Button({label}) 没有 onClick —— 点了必然没反应"))

    # 一排里的按钮数量:粗略统计同一 Row 内 Button 的个数
    row_idx = 0
    while row_idx < len(lines):
        if re.match(r"^\s*Row\(\)\s*\{", lines[row_idx]):
            depth = 1
            j = row_idx + 1
            count = 0
            min_height = 999
            while j < len(lines) and depth > 0:
                depth += lines[j].count("{") - lines[j].count("}")
                if BUTTON_START.search(lines[j]):
                    count += 1
                hm = HEIGHT.search(lines[j])
                if hm:
                    min_height = min(min_height, int(hm.group(1)))
                j += 1
            if count >= 5:
                findings.append(("E4", row_idx + 1,
                                 f"同一行有 {count} 个按钮 —— 手机上每个约 {320 // count}vp,标签会被截断"))
            elif count > 0 and min_height < 32:
                findings.append(("E4", row_idx + 1,
                                 f"按钮高度只有 {min_height}vp(<32),点击热区偏小"))
            row_idx = j
        else:
            row_idx += 1

    # 非 Button 上的 onClick
    for i, ln in enumerate(lines):
        if NON_BUTTON_CLICK.match(ln):
            prev = ""
            for k in range(i - 1, max(-1, i - 8), -1):
                cand = lines[k].strip()
                if cand and not cand.startswith("."):
                    prev = cand
                    break
            if prev and not prev.startswith(("Text(", "Row(", "Column(", "Stack(")):
                continue
            findings.append(("E3", i + 1, f"{prev[:40]} 上挂了 onClick(非 Button,注意热区)"))
    return findings


def main():
    files = []
    for d in UI_DIRS:
        files.extend(sorted(glob.glob(os.path.join(d, "*.ets"))))
    total = {"E1": 0, "E2": 0, "E3": 0, "E4": 0}
    for path in files:
        findings = audit_file(path)
        if not findings:
            continue
        print(f"\n=== {os.path.relpath(path, ROOT)} ===")
        for code, line_no, msg in sorted(findings):
            total[code] += 1
            print(f"  [{code}] L{line_no}: {msg}")
    print("\n" + "=" * 70)
    print(f"汇总:E1(enabled 禁用)={total['E1']}  E2(无 onClick)={total['E2']}  "
          f"E3(非按钮可点)={total['E3']}  E4(热区/数量)={total['E4']}")
    print("E1 需要人工确认:禁用理由对用户是否可见(倒计时/加载中=可以;其它=应改成可点+提示)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
