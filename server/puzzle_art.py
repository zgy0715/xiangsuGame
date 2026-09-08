#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""随机像素画生成器:用程序化几何形状(矩形/椭圆/三角形)叠出"图案感"答案,
与内置手绘图案互不干扰。seedable —— 同一种子必得同一画布。

仅依赖 puzzle_engine 的绘制原语(纯标准库)。
"""
import random

from puzzle_engine import fill_ellipse, fill_rect, fill_triangle, new_grid

_FILL_RANGE = (0.28, 0.62)  # 期望涂黑占比(过稀/过满都放弃重画)
_MAX_ATTEMPTS = 60


def random_answer(seed, size):
    """按确定性种子生成 size×size 的 0/1 答案网格;失败(占比始终不达标)返回 None。"""
    rng = random.Random(f"{seed}:shape")
    for _ in range(_MAX_ATTEMPTS):
        g = new_grid(size, size)
        shapes = rng.randint(3, 7)
        # 画布四周留白,避免贴边造成提示不可辨识
        margin = max(1, size // 7)
        for _ in range(shapes):
            kind = rng.choice(("rect", "ellipse", "triangle"))
            if kind == "rect":
                r0 = rng.randint(margin, size - margin - 1)
                c0 = rng.randint(margin, size - margin - 1)
                r1 = min(size - 1, r0 + rng.randint(1, max(1, (size - margin * 2) // 3)))
                c1 = min(size - 1, c0 + rng.randint(1, max(1, (size - margin * 2) // 3)))
                fill_rect(g, r0, c0, r1, c1)
            elif kind == "ellipse":
                fill_ellipse(g,
                             rng.uniform(margin, size - margin - 1),
                             rng.uniform(margin, size - margin - 1),
                             rng.uniform(1.5, size * 0.35),
                             rng.uniform(1.5, size * 0.35))
            else:  # triangle
                ar = rng.randint(margin, max(margin, size - margin - 4))
                br = min(size - 1, ar + rng.randint(2, max(2, size // 3)))
                ac = rng.randint(margin, size - margin - 1)
                half = rng.randint(1, max(1, (size - margin) // 4))
                fill_triangle(g, ar, ac, br, ac - half, ac + half)
        filled = sum(sum(row) for row in g)
        ratio = filled / (size * size)
        if _FILL_RANGE[0] <= ratio <= _FILL_RANGE[1]:
            return g
    return None
