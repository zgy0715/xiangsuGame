#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端产题器:随机画布 → 贪心稀疏提示 → 唯一解校验 → 关卡 JSON。

难度策略(常量可调):
  EASY   8~12 格, target_density 0.34
  MEDIUM 14~16 格, target_density 0.30
  HARD   18~20 格, target_density 0.27
time_limit 是单次唯一性校验的秒数上限;贪心整体预算 = time_limit × 20。
"""
import random

import puzzle_engine as pe
from puzzle_art import random_answer

DIFFICULTY_CFG = {
    "EASY":   {"size": (8, 12),  "density": 0.34, "time_limit": 8},
    "MEDIUM": {"size": (14, 16), "density": 0.30, "time_limit": 10},
    "HARD":   {"size": (18, 20), "density": 0.27, "time_limit": 12},
}


def generate_level(seed_str, difficulty, name=None):
    """按确定性种子生成一关;多次内部重试(种子追加 #attempt)。

    返回关卡 dict:
      {name, difficulty, rows, cols, clueGrid:[[int]], answerGrid:[[int]]}
    每次产题都以"全部提示集合唯一 + 最终提示集合唯一"双保险校验;
    全部尝试失败抛 RuntimeError(调用方换种子重试)。
    """
    cfg = DIFFICULTY_CFG[difficulty]
    rng = random.Random(f"{seed_str}:size")
    size = rng.randint(*cfg["size"])
    last_error = None
    for attempt in range(8):
        s = f"{seed_str}#{attempt}"
        answer = random_answer(s, size)
        if answer is None:
            continue
        try:
            positions = pe.greedy_clues(answer, target_density=cfg["density"],
                                        time_limit=cfg["time_limit"])
        except (TimeoutError, RuntimeError) as e:
            last_error = e
            continue
        u = pe.is_unique(answer, positions, time_limit=cfg["time_limit"] * 3)
        if u is not True:
            last_error = RuntimeError(f"not unique after greedy (attempt {attempt})")
            continue
        return {
            "name": name or f"在线谜题 {size}x{size}",
            "difficulty": difficulty,
            "rows": size,
            "cols": size,
            "clueGrid": pe.clues_from_answer(answer, positions),
            "answerGrid": answer,
        }
    raise RuntimeError(f"generate_level failed for seed={seed_str}: {last_error}")


def verify_unique(level):
    """对外暴露的兜底校验(冒烟测试/预热脚本用):断言唯一解且解等于答案。"""
    answer = level["answerGrid"]
    clue = level["clueGrid"]
    positions = {(r, c) for r in range(len(clue)) for c in range(len(clue[0])) if clue[r][c] >= 0}
    rows, cols = len(answer), len(answer[0])
    sol = pe.Solver(pe.clues_from_answer(answer, positions), max_solutions=2, time_limit=60)
    try:
        sols = sol.solve()
    except TimeoutError:
        return False
    if len(sols) != 1:
        return False
    s = sols[0]
    return all(answer[r][c] == s[r][c] for r in range(rows) for c in range(cols))
