#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FINAL pattern designs to be written into Levels.kt. Render for final sign-off."""
import math

def new_grid(rows, cols):
    return [[0] * cols for _ in range(rows)]

def fill_rect(g, r0, c0, r1, c1):
    for r in range(max(0, r0), min(len(g), r1 + 1)):
        for c in range(max(0, c0), min(len(g[0]), c1 + 1)):
            g[r][c] = 1

def carve_rect(g, r0, c0, r1, c1):
    for r in range(max(0, r0), min(len(g), r1 + 1)):
        for c in range(max(0, c0), min(len(g[0]), c1 + 1)):
            g[r][c] = 0

def fill_ellipse(g, cx, cy, rx, ry, strict=False):
    for r in range(len(g)):
        for c in range(len(g[0])):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if (dx * dx + dy * dy < 1.0) if strict else (dx * dx + dy * dy <= 1.0):
                g[r][c] = 1

def carve_ellipse(g, cx, cy, rx, ry, strict=False):
    for r in range(len(g)):
        for c in range(len(g[0])):
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
    for r in range(ar, br + 1):
        t = (r - ar) / (br - ar) if br > ar else 1.0
        left = int(ac + (bc0 - ac) * t)
        right = int(ac + (bc1 - ac) * t)
        for c in range(left, right + 1):
            if 0 <= r < len(g) and 0 <= c < len(g[0]):
                g[r][c] = 1

def star_vertices(cx, cy, outer, inner):
    verts = []
    for k in range(5):
        oa = math.radians(-90 + k * 72)
        verts.append((cx + outer * math.cos(oa), cy + outer * math.sin(oa)))
        ia = math.radians(-90 + 36 + k * 72)
        verts.append((cx + inner * math.cos(ia), cy + inner * math.sin(ia)))
    return verts

def point_in_polygon(x, y, poly):
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside

def art(*rows):
    return [[1 if ch == 'X' else 0 for ch in row] for row in rows]

def show(name, g, scale=1):
    print(f"===== {name}  {len(g)}x{len(g[0])}  filled={sum(sum(r) for r in g)}")
    for r in range(0, len(g), scale):
        row = g[r]
        cells = [row[c] for c in range(0, len(row), scale)] if scale > 1 else row
        print("".join("██" if v else "  " for v in cells))
    print()

# ---------- 1 HEART ----------
show("1 红心", art(
    ".XX....XX.",
    "XXXX..XXXX",
    "XXXX..XXXX",
    "XXXXXXXXXX",
    "XXXXXXXXXX",
    "XXXXXXXXXX",
    ".XXXXXXXX.",
    "..XXXXXX..",
    "...XXXX...",
    "....XX....",
))

# ---------- 2 SMILEY (unchanged) ----------
show("2 笑脸", art(
    ".XXXXXXXX.",
    "XXXXXXXXXX",
    "XX..XX..XX",
    "XX..XX..XX",
    "XXXXXXXXXX",
    "XXXXXXXXXX",
    "XXXX..XXXX",
    "XXX....XXX",
    "XX......XX",
    ".XXXXXXXX.",
))

# ---------- 3 BUTTERFLY ----------
show("3 蝴蝶", art(
    "....X..X....",
    ".....XX.....",
    ".....XX.....",
    "XXX..XX..XXX",
    "XXXX.XX.XXXX",
    "XXXX.XX.XXXX",
    "XXX..XX..XXX",
    ".XX..XX..XX.",
    "..XX.XX.XX..",
    "...X.XX.X...",
    "...X.XX.X...",
    ".....XX.....",
))

# ---------- 4 APPLE ----------
show("4 苹果", art(
    "......XX......",
    "......XX......",
    "......XX......",
    ".....XXXX.....",
    "...XXXXXXXX...",
    "..XXXXXXXXXX..",
    ".XXXXXXXXXXXX.",
    "XXXXXXXXXXXXXX",
    "XXXXXXXXXXXXXX",
    ".XXXXXXXXXXXX.",
    "..XXXXXXXXXX..",
    "...XXXXXXXX...",
    "....XXXXXX....",
    ".....XXXX.....",
))

# ---------- 5 HOUSE ----------
show("5 小房子", art(
    ".......XX.......",
    "......XXXX......",
    ".....XXXXXX.....",
    "....XXXXXXXX....",
    "...XXXXXXXXXX...",
    "..XXXXXXXXXXXX..",
    ".XXXXXXXXXXXXXX.",
    "XXXXXXXXXXXXXXXX",
    "XXXXXXXXXXXXXXXX",
    "XXX...XXXX...XXX",
    "XXX...XXXX...XXX",
    "XXXXXXX..XXXXXXX",
    "XXXXXXX..XXXXXXX",
    "XXXXXXX..XXXXXXX",
    "XXXXXXX..XXXXXXX",
    "XXXXXXX..XXXXXXX",
))

# ---------- 6 FISH (unchanged) ----------
show("6 小鱼", art(
    "................",
    "................",
    "................",
    ".....XXXXXXXX...",
    "..X..XXXXXXXXX..",
    ".XXX.XXXXXXXXXXX",
    "XXXX.XXXXXXXXXXX",
    "XXXX.XXXXXXX.XXX",
    "XXXX.XXXXXXX.XXX",
    "XXXX.XXXXXXXXXXX",
    ".XXX.XXXXXXXXXXX",
    "..X..XXXXXXXXX..",
    ".....XXXXXXXX...",
    "................",
    "................",
    "................",
))

# ---------- 7 STAR (standard 5-point, symmetric) ----------
def solid_star():
    return art(
        "....................",
        ".........XX.........",
        "........XXXX........",
        ".......XXXXXX.......",
        "......XXXXXXXX......",
        ".....XXXXXXXXXX.....",
        "....XXXXXXXXXXXX....",
        "XXXXXXXXXXXXXXXXXXXX",
        "XXXX..XXXXXXXX..XXXX",
        "XX.....XXXXXX.....XX",
        "XX......XXXX......XX",
        "XX.....XXXXXX.....XX",
        "..XXXXXXXXXXXXXXXX..",
        "...XXXX......XXXX...",
        "....XXX......XXX....",
        ".....XX......XX.....",
        ".....XX......XX.....",
        "......X......X......",
        "....................",
        "....................",
    )
show("7 星星(标准五角星)", solid_star())

# ---------- 8 CROWN (unchanged) ----------
def crown():
    g = new_grid(20, 20)
    fill_rect(g, 15, 3, 19, 16)
    fill_triangle(g, 3, 9, 15, 6, 13)
    fill_triangle(g, 7, 4, 15, 2, 6)
    fill_triangle(g, 7, 16, 15, 14, 18)
    fill_triangle(g, 5, 6, 15, 4, 8)
    fill_triangle(g, 5, 14, 15, 12, 16)
    carve_rect(g, 17, 6, 18, 7)
    carve_rect(g, 17, 9, 18, 10)
    carve_rect(g, 17, 13, 18, 14)
    return g
show("8 皇冠", crown())

# ---------- 9 MOON (crescent) ----------
def moon():
    g = new_grid(24, 24)
    fill_ellipse(g, 12, 12, 10, 10, strict=True)
    carve_ellipse(g, 16, 12, 7.5, 7.5, strict=True)
    return g
show("9 月亮(月牙)", moon())

# ---------- 10 ROCKET ----------
def rocket():
    g = new_grid(28, 28)
    fill_rect(g, 9, 9, 20, 18)
    fill_triangle(g, 1, 13, 9, 9, 18)
    carve_ellipse(g, 14, 14, 2, 2, strict=True)   # round window
    fill_triangle(g, 19, 9, 24, 4, 10)
    fill_triangle(g, 19, 18, 24, 17, 23)
    fill_triangle(g, 21, 13, 27, 11, 16)
    return g
show("10 火箭", rocket())

