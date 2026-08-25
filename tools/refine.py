#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Refine rocket / moon / castle and confirm final choices."""
import math

def new_grid(rows, cols):
    return [[0] * cols for _ in range(rows)]

def fill_rect(g, r0, c0, r1, c1):
    R, C = len(g), len(g[0])
    for r in range(max(0, r0), min(R, r1 + 1)):
        for c in range(max(0, c0), min(C, c1 + 1)):
            g[r][c] = 1

def carve_rect(g, r0, c0, r1, c1):
    R, C = len(g), len(g[0])
    for r in range(max(0, r0), min(R, r1 + 1)):
        for c in range(max(0, c0), min(C, c1 + 1)):
            g[r][c] = 0

def fill_ellipse(g, cx, cy, rx, ry, strict=False):
    R, C = len(g), len(g[0])
    for r in range(R):
        for c in range(C):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if (dx * dx + dy * dy < 1.0) if strict else (dx * dx + dy * dy <= 1.0):
                g[r][c] = 1

def carve_ellipse(g, cx, cy, rx, ry, strict=False):
    R, C = len(g), len(g[0])
    for r in range(R):
        for c in range(C):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if (dx * dx + dy * dy < 1.0) if strict else (dx * dx + dy * dy <= 1.0):
                g[r][c] = 0

def carve_diamond(g, r, c):
    for dr, dc in [(0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)]:
        nr, nc = r + dr, c + dc
        if 0 <= nr < len(g) and 0 <= nc < len(g[0]):
            g[nr][nc] = 0

def fill_triangle(g, ar, ac, br, bc0, bc1):
    R, C = len(g), len(g[0])
    for r in range(ar, br + 1):
        t = (r - ar) / (br - ar) if br > ar else 1.0
        left = int(ac + (bc0 - ac) * t)
        right = int(ac + (bc1 - ac) * t)
        for c in range(left, right + 1):
            if 0 <= r < R and 0 <= c < C:
                g[r][c] = 1

def show(name, g, scale=1):
    print(f"===== {name}  {len(g)}x{len(g[0])}  filled={sum(sum(r) for r in g)}")
    step = max(1, scale)
    rows_out = g[::step]
    for row in rows_out:
        cells = row[::step] if step > 1 else row
        print("".join("██" if v else "  " for v in cells))
    print()

# ---- ROCKET candidates ----
def rocket(version):
    g = new_grid(28, 28)
    if version == "A":  # current
        fill_rect(g, 9, 9, 20, 18)
        fill_triangle(g, 1, 13, 9, 9, 18)
        carve_rect(g, 12, 12, 15, 15)
        fill_triangle(g, 19, 9, 24, 4, 10)
        fill_triangle(g, 19, 18, 24, 17, 23)
        fill_triangle(g, 21, 13, 27, 11, 16)
    elif version == "B":  # round window, sharper nose, cleaner flame
        fill_rect(g, 9, 9, 19, 18)          # body 11 rows
        fill_triangle(g, 1, 13, 9, 9, 18)   # nose
        carve_rect(g, 13, 13, 15, 15)       # 3x3 round-ish window
        carve_rect(g, 14, 14, 14, 14)       # single-pixel center → hollow ring? no: makes plus
        fill_triangle(g, 19, 9, 24, 3, 9)   # left fin (swept)
        fill_triangle(g, 19, 18, 24, 18, 24)  # right fin
        fill_triangle(g, 21, 13, 26, 10, 16)  # flame
    elif version == "C":  # keep A but smaller window + centered flame
        fill_rect(g, 9, 9, 20, 18)
        fill_triangle(g, 1, 13, 9, 9, 18)
        carve_rect(g, 13, 13, 15, 15)       # 3x3 window
        fill_triangle(g, 19, 9, 24, 4, 10)
        fill_triangle(g, 19, 18, 24, 17, 23)
        fill_triangle(g, 22, 13, 27, 11, 16)
    return g

show("ROCKET-B", rocket("B"))

# ---- MOON final: full moon + craters, strict ellipse ----
def moon_final(strict):
    g = new_grid(24, 24)
    fill_ellipse(g, 12, 12, 10, 10, strict=strict)
    for (r, c) in [(6, 6), (8, 12), (10, 17), (14, 8), (16, 15), (18, 11)]:
        carve_diamond(g, r, c)
    return g
show("MOON-D-STRICT (no top nub)", moon_final(strict=True))

# ---- CASTLE scaled ----
def castle64():
    g = new_grid(64, 64)
    fill_rect(g, 44, 6, 56, 57)
    for c in range(6, 58, 7):
        fill_rect(g, 40, c, 43, min(c + 4, 57))
    fill_rect(g, 28, 27, 56, 36)
    fill_triangle(g, 20, 31, 28, 25, 38)
    fill_rect(g, 16, 31, 19, 31)
    fill_rect(g, 16, 32, 16, 34)
    fill_rect(g, 28, 7, 56, 15)
    fill_triangle(g, 18, 11, 28, 5, 17)
    fill_rect(g, 14, 11, 17, 11)
    fill_rect(g, 14, 12, 14, 14)
    fill_rect(g, 28, 48, 56, 56)
    fill_triangle(g, 18, 52, 28, 46, 58)
    fill_rect(g, 14, 52, 17, 52)
    fill_rect(g, 14, 49, 14, 51)
    carve_rect(g, 48, 28, 56, 35)
    carve_rect(g, 44, 30, 47, 33)
    carve_rect(g, 46, 12, 48, 14)
    carve_rect(g, 46, 19, 48, 21)
    carve_rect(g, 46, 42, 48, 44)
    carve_rect(g, 46, 49, 48, 51)
    carve_rect(g, 34, 29, 37, 32)
    carve_rect(g, 38, 10, 40, 12)
    carve_rect(g, 38, 51, 40, 53)
    return g

show("CASTLE 64x64 @ 2x downscale", castle64(), scale=2)
