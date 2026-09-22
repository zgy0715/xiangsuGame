#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""机械比对安卓(Kotlin)与鸿蒙(ArkTS)两端的符号清单,找出疑似缺失的实现。

用途:排查"鸿蒙端相对安卓端少实现了什么"。只做**符号级**对比(函数/常量/属性),
不做语义判断 —— 输出给人看,再由人确认。

用法:python tools/compare_platforms.py
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ANDROID = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "xiangsugame")
HARMONY = os.path.join(ROOT, "harmony", "ets")

# ---- 文件配对(相对各自根目录);键是安卓路径,值是鸿蒙路径 ----
PAIRS = [
    ("api/ApiClient.kt", "api/HttpClient.ets"),
    ("api/GameApi.kt", "api/GameApi.ets"),
    ("api/dto/Dtos.kt", "api/Dtos.ets"),
    ("AppGraph.kt", "AppGraph.ets"),
    ("auth/AuthManager.kt", "auth/AuthManager.ets"),
    ("auth/LoginRules.kt", "auth/LoginRules.ets"),
    ("battle/BattleProtocol.kt", "battle/BattleProtocol.ets"),
    ("battle/BattleSession.kt", "battle/BattleSession.ets"),
    ("battle/BattleTransport.kt", "battle/BattleTransport.ets"),
    ("battle/NetworkBattleTransport.kt", "battle/NetworkBattleTransport.ets"),
    ("data/AccountStore.kt", "data/AccountStore.ets"),
    ("data/Dates.kt", "data/Dates.ets"),
    ("data/LevelCache.kt", "data/LevelCache.ets"),
    ("data/LocalSettings.kt", "data/LocalSettings.ets"),
    ("data/PuzzleRepository.kt", "data/PuzzleRepository.ets"),
    ("data/QuotaLedger.kt", "data/QuotaLedger.ets"),
    ("model/CellState.kt", "model/CellState.ets"),
    ("model/GameBoard.kt", "model/GameBoard.ets"),
    ("model/GameMode.kt", "model/GameMode.ets"),
    ("model/Level.kt", "model/Level.ets"),
    ("model/PuzzleGenerator.kt", "model/PuzzleGenerator.ets"),
    ("model/PuzzleSolver.kt", "model/PuzzleSolver.ets"),
    ("model/Validator.kt", "model/Validator.ets"),
    ("sensor/ShakeDetector.kt", "sensor/ShakeDetector.ets"),
    ("service/BackgroundMusicManager.kt", "service/BackgroundMusicManager.ets"),
    ("service/SoundEffectManager.kt", "service/SoundEffectManager.ets"),
    ("receiver/NetworkMonitor.kt", "service/NetworkMonitor.ets"),
    ("ui/GameScreen.kt", "ui/GameScreen.ets"),
    ("ui/HomeScreen.kt", "ui/HomeScreen.ets"),
    ("ui/HallScreen.kt", "ui/HallScreen.ets"),
    ("ui/LeaderboardScreen.kt", "ui/LeaderboardScreen.ets"),
    ("ui/LoginScreen.kt", "ui/LoginScreen.ets"),
    ("ui/SettingsScreen.kt", "ui/SettingsScreen.ets"),
    ("ui/BattleHomeScreen.kt", "ui/BattleHomeScreen.ets"),
    ("ui/BattleHomeScreen.kt", "ui/BattleHomeScreen.ets"),
]

KOTLIN_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:private |internal |public |protected |override |suspend |inline |open |abstract |"
    r"const |lateinit |var |val |fun |companion object )*"
    r"(?:fun\s+(\w+)|(?:val|var)\s+(\w+)|const\s+val\s+(\w+))"
)
KOTLIN_CLASS = re.compile(r"^\s*(?:data |sealed |abstract |open |enum |internal |private )*"
                          r"(?:class|object|interface|enum class)\s+(\w+)")
KOTLIN_CONST = re.compile(r"^\s*(?:private |internal |public )?const val\s+(\w+)")

ARK_DECL = re.compile(
    r"^\s*(?:private |public |protected |readonly |static |async |export )*"
    r"(?:async\s+)?(?:get\s+)?(?:(\w+)\s*\(|(?:(\w+)\s*:)|(?:(\w+)\s*=)|"
    r"static\s+readonly\s+(\w+))"
)
ARK_FUNC = re.compile(r"^\s*(?:private |public |protected |static |async |export )*"
                      r"(?:async\s+)?(\w+)\s*\(")
ARK_CONST = re.compile(r"^\s*(?:export\s+)?(?:static\s+)?readonly\s+(\w+)\s*:")
ARK_ENUM_MEMBER = re.compile(r"^\s*(\w+)\s*=\s*\d+")

# 噪音:两端通用的小工具名、生命周期、装饰器生成物,不参与比对
NOISE = {
    "get", "set", "toString", "hashCode", "equals", "copy", "component1", "component2",
    "values", "valueOf", "entries", "name", "ordinal", "compareTo", "invoke",
    "aboutToAppear", "aboutToDisappear", "onPageShow", "onPageHide", "build", "constructor",
}


def kotlin_symbols(path):
    funcs, consts, classes = set(), set(), set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = KOTLIN_CLASS.match(line)
            if m:
                classes.add(m.group(1))
            mc = KOTLIN_CONST.match(line)
            if mc:
                consts.add(mc.group(1))
            m = KOTLIN_DECL.match(line)
            if m:
                if m.group(1):
                    funcs.add(m.group(1))
                if m.group(2):
                    consts.add(m.group(2))
                if m.group(3):
                    consts.add(m.group(3))
    return funcs, consts, classes


def ark_symbols(path):
    funcs, consts, classes = set(), set(), set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue
            m = re.match(r"^\s*(?:export\s+)?(?:abstract\s+)?(?:class|interface|enum)\s+(\w+)", line)
            if m:
                classes.add(m.group(1))
            m = ARK_CONST.match(line)
            if m:
                consts.add(m.group(1))
            m = ARK_FUNC.match(line)
            if m:
                nm = m.group(1)
                if nm not in ("if", "for", "while", "switch", "return", "catch", "function"):
                    funcs.add(nm)
    return funcs, consts, classes


def lower_first(name):
    return name[0].lower() + name[1:] if name else name


def main():
    print("=" * 78)
    print("安卓(Kotlin) vs 鸿蒙(ArkTS) 符号清单比对")
    print("=" * 78)
    total_gaps = 0
    for kt_rel, ets_rel in sorted(set(PAIRS)):
        kt_path = os.path.join(ANDROID, kt_rel)
        ets_path = os.path.join(HARMONY, ets_rel)
        if not os.path.isfile(kt_path) or not os.path.isfile(ets_path):
            print(f"\n!! 缺少文件: {kt_rel if not os.path.isfile(kt_path) else ets_rel}")
            continue
        kf, kc, kcls = kotlin_symbols(kt_path)
        af, ac, acls = ark_symbols(ets_path)
        # ArkTS 侧名字大小写/前缀可能不同,做一次宽松匹配
        def missing(names, pool):
            out = []
            for n in sorted(names):
                if n in NOISE or len(n) <= 2:
                    continue
                cands = {n, lower_first(n)}
                if not (cands & pool):
                    out.append(n)
            return out

        miss_f = missing(kf, af)
        miss_c = missing(kc, ac)
        miss_cls = missing(kcls, acls)
        if miss_f or miss_c or miss_cls:
            total_gaps += 1
            print(f"\n--- {kt_rel}  ↔  {ets_rel} ---")
            if miss_cls:
                print(f"  类/对象未找到: {', '.join(miss_cls)}")
            if miss_f:
                print(f"  函数未找到  : {', '.join(miss_f)}")
            if miss_c:
                print(f"  常量/属性未找到: {', '.join(miss_c)}")
    print(f"\n共 {total_gaps} 个文件对存在符号级差异(需人工确认是否真缺实现)。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
