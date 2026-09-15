#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate sparse clue positions for the NEW pattern answers, guaranteeing unique solution.

求解器/生成逻辑已抽取到 server/puzzle_engine.py(与 FastAPI 服务端共用),
本文件保留: 图案绘制定义(与 Levels.kt 一致) + Kotlin setOf 字面量输出 + __main__。
运行方式不变: python tools/gen_clues.py [name...]
"""
import os
import sys
import time

sys.path.insert(0, os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server")))
from puzzle_engine import (  # noqa: E402
    art,
    new_grid,
    fill_rect,
    carve_rect,
    fill_ellipse,
    carve_ellipse,
    fill_triangle,
    clues_from_answer,
    Solver,
    is_unique,
    greedy_clues,
)

# ---------------- Pattern answers (must match Levels.kt exactly) ----------------

def heart():
    return art(
        ".XX....XX.", "XXXX..XXXX", "XXXX..XXXX", "XXXXXXXXXX", "XXXXXXXXXX",
        "XXXXXXXXXX", ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX....")

def smiley():
    return art(
        ".XXXXXXXX.", "XXXXXXXXXX", "XX..XX..XX", "XX..XX..XX", "XXXXXXXXXX",
        "XXXXXXXXXX", "XX......XX", "XXX....XXX", "XXXX..XXXX", ".XXXXXXXX.")

def butterfly():
    return art(
        "....X..X....", ".....XX.....", ".....XX.....", "XXX..XX..XXX", "XXXX.XX.XXXX",
        "XXXX.XX.XXXX", "XXX..XX..XXX", ".XX..XX..XX.", "..XX.XX.XX..", "...X.XX.X...",
        "...X.XX.X...", ".....XX.....")

def apple():
    return art(
        "......XX......", "......XX......", "......XX......", ".....XXXX.....",
        "...XXXXXXXX...", "..XXXXXXXXXX..", ".XXXXXXXXXXXX.", "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX", ".XXXXXXXXXXXX.", "..XXXXXXXXXX..", "...XXXXXXXX...",
        "....XXXXXX....", ".....XXXX.....")

def house():
    return art(
        ".......XX.......", "......XXXX......", ".....XXXXXX.....", "....XXXXXXXX....", "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..", ".XXXXXXXXXXXXXX.", "XXXXXXXXXXXXXXXX", "XXXXXXXXXXXXXXXX", "XXX...XXXX...XXX",
        "XXX...XXXX...XXX", "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX",
        "XXXXXXX..XXXXXXX")

def fish():
    return art(
        "................", "................", "................", ".....XXXXXXXX...", "..X..XXXXXXXXX..",
        ".XXX.XXXXXXXXXXX", "XXXX.XXXXXXXXXXX", "XXXXXXXXXXXX.XXX", "XXXXXXXXXXXX.XXX", "XXXX.XXXXXXXXXXX",
        ".XXX.XXXXXXXXXXX", "..X..XXXXXXXXX..", ".....XXXXXXXX...", "................", "................",
        "................")

def star():
    return art(
        "....................", ".........XX.........", "........XXXX........",
        ".......XXXXXX.......", "......XXXXXXXX......", ".....XXXXXXXXXX.....",
        "....XXXXXXXXXXXX....", "XXXXXXXXXXXXXXXXXXXX", "XXXX..XXXXXXXX..XXXX",
        "XX.....XXXXXX.....XX", "XX......XXXX......XX", "XX.....XXXXXX.....XX",
        "..XXXXXXXXXXXXXXXX..", "...XXXX......XXXX...", "....XXX......XXX....",
        ".....XX......XX.....", ".....XX......XX.....", "......X......X......",
        "....................", "....................")

def crown():
    return art(
        "....................", "....................", "....................", ".........XX.........",
        ".........XX.........", "......X.XXXX.X......", "......X.XXXX.X......", "....X.X.XXXX.X.X....",
        "....XXXXXXXXXXXX....", "....XXXXXXXXXXXX....", "...XXXXXXXXXXXXXX...", "...XXXXXXXXXXXXXX...",
        "...XXXXXXXXXXXXXX...", "...XXXXXXXXXXXXXX...", "..XXXXXXXXXXXXXXXX..", "..XXXXXXXXXXXXXXXX..",
        "...XXXXXXXXXXXXXX...", "...XXX..X..X..XXX...", "...XXX..X..X..XXX...", "...XXXXXXXXXXXXXX...")

def moon():
    g = new_grid(24, 24)
    fill_ellipse(g, 12, 12, 10, 10, strict=True)
    carve_ellipse(g, 16, 12, 7.7, 7.7, strict=True)
    return g

def rocket():
    g = new_grid(28, 28)
    fill_rect(g, 9, 9, 20, 18)
    fill_triangle(g, 1, 13, 9, 9, 18)
    carve_rect(g, 12, 12, 15, 15)
    fill_triangle(g, 19, 9, 24, 4, 10)
    fill_triangle(g, 19, 18, 24, 17, 23)
    fill_triangle(g, 21, 13, 27, 11, 16)
    return g

PATTERNS = {
    "heart": heart, "smiley": smiley, "butterfly": butterfly, "apple": apple,
    "house": house, "fish": fish, "star": star, "crown": crown,
    "moon": moon, "rocket": rocket,
}


# ---------------- Kotlin output ----------------

def to_kotlin_set(name, positions):
    sorted_p = sorted(positions)
    line = [f"({r} to {c})" for (r, c) in sorted_p]
    # chunk into lines of ~9 entries
    parts = [", ".join(line[i:i + 9]) for i in range(0, len(line), 9)]
    body = (",\n        ".join(parts)) if len(parts) > 1 else (", ".join(line))
    return f"""    /** {name} */
    val {name}: Set<Pair<Int, Int>> = setOf(
        {body},
    )"""


if __name__ == "__main__":
    which = sys.argv[1:] if len(sys.argv) > 1 else None
    names = which if which else list(PATTERNS.keys())
    for name in names:
        ans = PATTERNS[name]()
        print(f"--- {name} ({len(ans)}x{len(ans[0])}) ---")
        density = 0.30
        t0 = time.time()
        pos = greedy_clues(ans, target_density=density, time_limit=15)
        dt = time.time() - t0
        print(f"  generated {len(pos)} clues in {dt:.1f}s; verifying uniqueness...")
        assert is_unique(ans, pos), f"{name}: generated set NOT unique!"
        print(f"  VERIFIED unique. density={len(pos)/(len(ans)*len(ans[0])):.3f}")
        print(to_kotlin_set(name, pos))
        print()
