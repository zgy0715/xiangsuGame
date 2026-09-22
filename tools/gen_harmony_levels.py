#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Android 版的内置关卡数据生成到鸿蒙工程(ArkTS)。

为什么用脚本而不是手抄:
  10 个图案 + 稀疏提示如果手工翻译成 ArkTS,迟早和 Android 版漂移 ——
  本项目已经踩过这个坑(苹果/星星图案曾在 Levels.kt 与工具链副本之间漂移,
  导致提示集失效、关卡"推对了却判不过",见 作品说明书.md 9.3)。
  这里把"逐格一致"变成机械校验:任何一处对不上就直接报错退出。

数据来源(单向,只读):
  - 图案答案: tools/gen_clues.py 的 PATTERNS(与 Levels.kt 逐行一致,脚本会再校一次)
  - 关卡元数据: app/src/main/java/com/example/xiangsugame/model/Levels.kt
  - 提示位置: app/src/main/java/com/example/xiangsugame/model/SparseClues.kt

输出:
  harmony/ets/model/GeneratedLevels.ets   (纯数据,请勿手改;随后用
                                           python tools/sync_harmony.py 同步进 DevEco 工程)

自检(任一失败即退出码 1):
  1. 关卡数量、id、名称、难度、尺寸与 Levels.kt 一致
  2. ASCII 图案与 Levels.kt 内联 art(...) 逐行一致
  3. 每关"提示集 → 唯一解,且唯一解 = 图案答案"(复用 server/puzzle_engine.py 求解器)

用法:
  python tools/gen_harmony_levels.py                 # 生成到默认鸿蒙工程路径
  python tools/gen_harmony_levels.py --out <path>    # 自定义输出文件
  python tools/gen_harmony_levels.py --check         # 只校验不写文件
"""
import argparse
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
SERVER_DIR = os.path.join(ROOT, "server")
sys.path.insert(0, HERE)          # gen_clues
sys.path.insert(0, SERVER_DIR)    # puzzle_engine

import gen_clues as gc            # noqa: E402
import puzzle_engine as pe        # noqa: E402

LEVELS_KT = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "example", "xiangsugame", "model", "Levels.kt")
CLUES_KT = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "example", "xiangsugame", "model", "SparseClues.kt")
DEFAULT_OUT = os.path.normpath(os.path.join(
    ROOT, "harmony", "ets", "model", "GeneratedLevels.ets"))

Q = '"'


# ---------------- 1. 解析 Android 侧关卡定义 ----------------

def parse_levels_kt(path):
    """从 Levels.kt 解析关卡顺序与每个关卡的元数据。

    返回 [{order, fn, id, name, difficulty, clue_key, timed, art_rows|None}]
    """
    src = open(path, encoding="utf-8").read()

    # val all: List<Level> = listOf( heart(), smiley(), ... )   —— 匹配到独立成行的右括号
    m = re.search(r"val all: List<Level> = listOf\(([\s\S]*?)\n    \)", src)
    if not m:
        raise SystemExit("解析失败:Levels.kt 里找不到 val all = listOf(...)")
    order = re.findall(r"(\w+)\(\)", m.group(1))
    if not order:
        raise SystemExit("解析失败:val all 里没解析出关卡工厂函数")

    levels = []
    for idx, fn in enumerate(order):
        # private fun apple() = Level.fromAnswer( ... )   直到独立成行的右括号
        blk = re.search(
            r"private fun " + re.escape(fn) + r"\(\)\s*=\s*Level\.fromAnswer\(([\s\S]*?)\n    \)",
            src)
        if not blk:
            raise SystemExit(f"解析失败:找不到关卡工厂函数 {fn}()")
        body = blk.group(1)
        gid = re.search(r"id = (\d+)", body)
        gname = re.search(r"name = " + Q + r"([^" + Q + r"]+)" + Q, body)
        gdiff = re.search(r"difficulty = Difficulty\.(\w+)", body)
        gclue = re.search(r"cluePositions = SparseClues\.(\w+)", body)
        gtime = re.search(r"timedLimitSeconds = (\d+)", body)
        if not (gid and gname and gdiff and gclue):
            raise SystemExit(f"解析失败:{fn}() 缺少 id/name/difficulty/cluePositions")

        # 内联 ASCII 图案(answer = art( "....", ... ));程序化作画(moon24() 等)为 None
        art_rows = None
        art = re.search(r"answer = art\(([\s\S]*?)\n        \)", body)
        if art:
            art_rows = re.findall(Q + r"([.X]+)" + Q, art.group(1))

        levels.append({
            "order": idx + 1,
            "fn": fn,
            "id": int(gid.group(1)),
            "name": gname.group(1),
            "difficulty": gdiff.group(1),
            "clue_key": gclue.group(1),
            "timed": int(gtime.group(1)) if gtime else 0,
            "art_rows": art_rows,
        })
    return levels


def parse_sparse_clues(path):
    """从 SparseClues.kt 解析每关的提示坐标集合。"""
    src = open(path, encoding="utf-8").read()
    out = {}
    for name, body in re.findall(
            r"val (\w+): Set<Pair<Int, Int>> = setOf\(([\s\S]*?)\n    \)", src):
        out[name] = {(int(r), int(c)) for r, c in re.findall(r"\((\d+) to (\d+)\)", body)}
    return out


# ---------------- 2. 校验 ----------------

def grid_to_rows(grid):
    return ["".join("X" if v else "." for v in row) for row in grid]


def verify(levels, clues):
    """逐关校验并返回 (seeds, warnings)。任一硬性不一致直接 SystemExit。"""
    seeds, warnings = [], []
    for lv in levels:
        fn = lv["fn"]
        if fn not in gc.PATTERNS:
            raise SystemExit(f"关卡 {lv['name']} 的工厂函数 {fn}() 在 gen_clues.PATTERNS 里没有同名图案")
        grid = gc.PATTERNS[fn]()

        # (2) ASCII 图案与 Levels.kt 内联 art 逐行比对
        if lv["art_rows"] is not None:
            rows = grid_to_rows(grid)
            if rows != lv["art_rows"]:
                raise SystemExit(
                    f"图案不一致:关卡 {lv['name']}({fn})\n"
                    f"  Levels.kt : {lv['art_rows']}\n"
                    f"  gen_clues : {rows}\n"
                    "  → 请先把 tools/gen_clues.py 与 Levels.kt 对齐(见 作品说明书.md 9.3)")
        else:
            warnings.append(f"{lv['name']}({fn}):程序化作画,图案一致性依赖 puzzle_engine 原语")

        # (3) 提示集 → 唯一解,且唯一解 = 答案
        pos = clues.get(lv["clue_key"])
        if not pos:
            raise SystemExit(f"关卡 {lv['name']} 的提示集 SparseClues.{lv['clue_key']} 为空/未找到")
        rows_n, cols_n = len(grid), len(grid[0])
        for (r, c) in pos:
            if not (0 <= r < rows_n and 0 <= c < cols_n):
                raise SystemExit(f"关卡 {lv['name']} 的提示坐标越界:({r},{c}) 棋盘 {rows_n}×{cols_n}")

        clue_grid = pe.clues_from_answer(grid, pos)
        solver = pe.Solver(clue_grid, max_solutions=2, time_limit=30)
        t0 = time.time()
        solutions = solver.solve()
        dt = time.time() - t0
        if len(solutions) != 1:
            raise SystemExit(
                f"关卡 {lv['name']} 的提示集不唯一解(求得 {len(solutions)} 个解)"
                f" → 玩家可能推出与答案不同的合法解却判不过;请重跑 tools/gen_clues.py {fn}")
        if solutions[0] != grid:
            raise SystemExit(f"关卡 {lv['name']} 的唯一解与图案答案不一致")
        warnings.append(f"{lv['name']}:{len(pos)} 个提示,唯一解 ✔ ({dt:.2f}s)")

        seeds.append({
            "id": lv["id"], "name": lv["name"], "difficulty": lv["difficulty"],
            "grid": grid, "positions": sorted(pos), "timed": lv["timed"],
        })
    return seeds, warnings


# ---------------- 3. 输出 ArkTS ----------------

HEADER = """/*
 * 本文件由 tools/gen_harmony_levels.py 自动生成 —— 【请勿手改】。
 * 数据来源(Android 版,单向同步):
 *   app/src/main/java/com/example/xiangsugame/model/Levels.kt
 *   app/src/main/java/com/example/xiangsugame/model/SparseClues.kt
 * 生成时间:%s
 *
 * 生成时已逐关校验:图案与 Levels.kt 逐行一致、提示集唯一解、唯一解 = 图案答案。
 * Android 侧改了图案或提示后,重跑:
 *   python tools/gen_harmony_levels.py
 */

/** 单个内置关卡的数据种子(answerGrid 即答案画;cluePositions 为稀疏提示坐标 "行,列")。 */
export interface LevelSeed {
  id: number;
  name: string;
  /** 'EASY' | 'MEDIUM' | 'HARD' */
  difficulty: string;
  rows: number;
  cols: number;
  answerGrid: number[][];
  cluePositions: string[];
  /** 限时模式的时限(秒);0 表示该关不用于限时模式 */
  timedLimitSeconds: number;
}

export const LEVEL_SEEDS: LevelSeed[] = [
"""


def emit_ets(seeds):
    parts = [HEADER % time.strftime("%Y-%m-%d %H:%M:%S")]
    for s in seeds:
        rows = ",\n".join(
            "      [" + ", ".join(str(v) for v in row) + "]" for row in s["grid"])
        # 提示坐标每行 8 个,避免超长行
        items = [Q + f"{r},{c}" + Q for (r, c) in s["positions"]]
        lines = [", ".join(items[i:i + 8]) for i in range(0, len(items), 8)]
        pos_lines = ",\n      ".join(lines)
        parts.append(f"""  {{
    id: {s['id']},
    name: {Q}{s['name']}{Q},
    difficulty: {Q}{s['difficulty']}{Q},
    rows: {len(s['grid'])},
    cols: {len(s['grid'][0])},
    answerGrid: [
{rows}
    ],
    cluePositions: [
      {pos_lines}
    ],
    timedLimitSeconds: {s['timed']},
  }},
""")
    parts.append("];\n")
    return "".join(parts)


# ---------------- main ----------------

def main():
    ap = argparse.ArgumentParser(description="生成鸿蒙版关卡数据(ArkTS)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出 .ets 路径")
    ap.add_argument("--check", action="store_true", help="只校验,不写文件")
    args = ap.parse_args()

    levels = parse_levels_kt(LEVELS_KT)
    clues = parse_sparse_clues(CLUES_KT)
    print(f"解析到 {len(levels)} 个内置关卡,{len(clues)} 组提示集")

    seeds, warnings = verify(levels, clues)
    for w in warnings:
        print("  [ok] " + w)

    ids = [s["id"] for s in seeds]
    assert ids == sorted(ids) == list(range(1, len(seeds) + 1)), f"关卡 id 不是连续的 1..N: {ids}"
    assert len(seeds) == 10, f"预期 10 关,实际 {len(seeds)}"

    if args.check:
        print("仅校验模式:全部通过 ✔")
        return

    text = emit_ets(seeds)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"已生成 {args.out}  ({len(text)} 字节, {len(seeds)} 关, "
          f"提示总数 {sum(len(s['positions']) for s in seeds)})")


if __name__ == "__main__":
    main()
