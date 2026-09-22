#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把仓库里托管的鸿蒙源码同步进 DevEco 工程。

为什么要有这一步:
  ArkTS 源码放在本仓库的 harmony/ 下(可 review、可提交、可 diff),
  DevEco 工程(DEVxiangs)只是构建工作区。这样"移植成果"跟安卓版在同一个仓库里,
  不会出现"代码只存在于 IDE 工程里、谁也不知道改了什么"的情况。

同步内容:
  harmony/ets/**          →  <DEVxiangs>/entry/src/main/ets/**
  harmony/resources/**    →  <DEVxiangs>/entry/src/main/resources/**
  harmony/scripts/**      →  <DEVxiangs>/scripts/**(一键 bat:编译/安装/自检/日志)
  harmony/config/*.json5  →  <DEVxiangs>/ 与 <DEVxiangs>/entry/src/main/ 下的同名配置文件
                             (文件名以 module. 开头的进 entry/src/main,其余进工程根)

只增改不删除:工程里 DevEco 自己生成的文件(EntryAbility.ets、Index.ets 等)保持不动。

用法:
  python tools/sync_harmony.py                 # 同步到默认工程路径
  python tools/sync_harmony.py --dst <路径>    # 自定义工程根
  python tools/sync_harmony.py --check         # 只报告差异,不写文件
"""
import argparse
import filecmp
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
SRC_ROOT = os.path.join(ROOT, "harmony")
DEFAULT_DST = os.path.normpath(os.path.join(ROOT, "..", "DEVxiangs"))

# 源目录 → 目标相对路径
TREE_MAP = [
    ("ets", os.path.join("entry", "src", "main", "ets")),
    ("resources", os.path.join("entry", "src", "main", "resources")),
    ("test", os.path.join("entry", "src", "test")),
    # 鸿蒙工程的一键脚本(build/run/check/日志),放到工程根的 scripts/ 下
    ("scripts", "scripts"),
]


def normalize_encoding(path):
    """同步后的编码兜底(Windows PowerShell 5.1 的坑):

    - `.ps1` 必须是 **UTF-8 with BOM** —— 否则 PS 5.1 按系统 ANSI(GBK)解析,
      中文与破折号会直接造成语法错误;
    - `.bat` 反过来必须是 **无 BOM 的纯 ASCII/UTF-8** —— cmd.exe 见到 BOM 会把
      第一条命令读坏(`锘?echo`)。

    这样"改脚本忘了加 BOM"不会变成一个小时后才发现的诡异报错。
    """
    ext = os.path.splitext(path)[1].lower()
    if ext not in (".ps1", ".bat", ".cmd"):
        return
    with open(path, "rb") as f:
        data = f.read()
    has_bom = data.startswith(b"\xef\xbb\xbf")
    if ext == ".ps1" and not has_bom:
        with open(path, "wb") as f:
            f.write(b"\xef\xbb\xbf" + data)
    elif ext in (".bat", ".cmd") and has_bom:
        with open(path, "wb") as f:
            f.write(data[3:])


def sync_tree(src_dir, dst_dir, check, stats):
    if not os.path.isdir(src_dir):
        return
    for dirpath, _dirnames, filenames in os.walk(src_dir):
        rel = os.path.relpath(dirpath, src_dir)
        target_dir = dst_dir if rel == "." else os.path.join(dst_dir, rel)
        for name in filenames:
            src_file = os.path.join(dirpath, name)
            dst_file = os.path.join(target_dir, name)
            rel_show = os.path.relpath(dst_file, os.path.dirname(SRC_ROOT))
            if os.path.isfile(dst_file) and filecmp.cmp(src_file, dst_file, shallow=False):
                stats["same"] += 1
                continue
            existed = os.path.isfile(dst_file)
            if check:
                print(("  ~ 待更新 " if existed else "  + 待新增 ") + rel_show)
                stats["changed" if existed else "added"] += 1
                continue
            os.makedirs(target_dir, exist_ok=True)
            shutil.copy2(src_file, dst_file)
            normalize_encoding(dst_file)
            stats["changed" if existed else "added"] += 1
            print(("  ~ 已更新 " if existed else "  + 已新增 ") + rel_show)


def sync_config(dst_root, check, stats):
    cfg_dir = os.path.join(SRC_ROOT, "config")
    if not os.path.isdir(cfg_dir):
        return
    for name in sorted(os.listdir(cfg_dir)):
        if not name.endswith(".json5"):
            continue
        src_file = os.path.join(cfg_dir, name)
        # module.* 进 entry/src/main,其余进工程根
        if name.startswith("module."):
            dst_file = os.path.join(dst_root, "entry", "src", "main", name)
        else:
            dst_file = os.path.join(dst_root, name)
        rel_show = os.path.relpath(dst_file, os.path.dirname(SRC_ROOT))
        if os.path.isfile(dst_file) and filecmp.cmp(src_file, dst_file, shallow=False):
            stats["same"] += 1
            continue
        existed = os.path.isfile(dst_file)
        if check:
            print(("  ~ 待更新 " if existed else "  + 待新增 ") + rel_show)
            stats["changed" if existed else "added"] += 1
            continue
        os.makedirs(os.path.dirname(dst_file), exist_ok=True)
        shutil.copy2(src_file, dst_file)
        stats["changed" if existed else "added"] += 1
        print(("  ~ 已更新 " if existed else "  + 已新增 ") + rel_show)


def main():
    ap = argparse.ArgumentParser(description="同步 harmony/ 到 DevEco 工程")
    ap.add_argument("--dst", default=DEFAULT_DST, help="DevEco 工程根目录")
    ap.add_argument("--check", action="store_true", help="只报告差异,不写文件")
    args = ap.parse_args()

    if not os.path.isdir(args.dst):
        print(f"目标工程不存在:{args.dst}", file=sys.stderr)
        return 1
    if not os.path.isdir(SRC_ROOT):
        print(f"源目录不存在:{SRC_ROOT}", file=sys.stderr)
        return 1

    print(f"源:{SRC_ROOT}")
    print(f"目标:{args.dst}")
    stats = {"added": 0, "changed": 0, "same": 0}
    for sub, dst_rel in TREE_MAP:
        sync_tree(os.path.join(SRC_ROOT, sub), os.path.join(args.dst, dst_rel), args.check, stats)
    sync_config(args.dst, args.check, stats)

    verb = "待同步" if args.check else "已同步"
    print(f"{verb}:新增 {stats['added']},更新 {stats['changed']},未变 {stats['same']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
