#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render all game patterns to text so they can be visually verified.
Ports the exact same algorithms as Levels.kt so we SEE what the player sees.
"""
import math

# ---------- port of the drawing primitives from Levels.kt ----------

def new_grid(rows, cols):
    return [[0] * cols for _ in range(rows)]

def fill_rect(g, r0, c0, r1, c1):
    R, C = len(g), len(g[0])
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            if 0 <= r < R and 0 <= c < C:
                g[r][c] = 1

def carve_rect(g, r0, c0, r1, c1):
    R, C = len(g), len(g[0])
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            if 0 <= r < R and 0 <= c < C:
                g[r][c] = 0

def fill_ellipse(g, cx, cy, rx, ry):
    R, C = len(g), len(g[0])
    for r in range(R):
        for c in range(C):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if dx * dx + dy * dy <= 1.0:
                g[r][c] = 1

def carve_ellipse(g, cx, cy, rx, ry):
    R, C = len(g), len(g[0])
    for r in range(R):
        for c in range(C):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if dx * dx + dy * dy <= 1.0:
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

def star20():
    g = new_grid(20, 20)
    cx, cy, outer = 9.5, 9.5, 9.5
    inner = outer * 0.36
    outer_verts = star_vertices(cx, cy, outer, inner)
    inner_verts = star_vertices(cx, cy, outer * 0.55, outer * 0.55 * 0.36)
    for r in range(20):
        for c in range(20):
            if point_in_polygon(c + 0.5, r + 0.5, outer_verts) and not point_in_polygon(c + 0.5, r + 0.5, inner_verts):
                g[r][c] = 1
    return g

def crown20():
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

def moon24():
    g = new_grid(24, 24)
    fill_ellipse(g, 12, 12, 10, 10)
    carve_ellipse(g, 17, 11, 7, 7)
    carve_diamond(g, 8, 5)
    carve_diamond(g, 11, 8)
    carve_diamond(g, 9, 13)
    return g

def rocket28():
    g = new_grid(28, 28)
    fill_rect(g, 9, 9, 20, 18)
    fill_triangle(g, 1, 13, 9, 9, 18)
    carve_rect(g, 12, 12, 15, 15)
    fill_triangle(g, 19, 9, 24, 4, 10)
    fill_triangle(g, 19, 18, 24, 17, 23)
    fill_triangle(g, 21, 13, 27, 11, 16)
    return g

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

def art(*rows):
    return [[1 if ch == 'X' else 0 for ch in row] for row in rows]

PATTERNS = {
    "1 红心 heart 10x10": art(
        "..XX..XX..",
        ".XXXX.XXXX",
        "XXXXXXXXXX",
        "XXXXXXXXXX",
        "XXXXXXXXXX",
        "XXXXXXXXXX",
        ".XXXXXXXX.",
        "..XXXXXX..",
        "...XXXX...",
        "....XX....",
    ),
    "2 笑脸 smiley 10x10": art(
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
    ),
    "3 蝴蝶 butterfly 12x12": art(
        "....X..X....",
        ".....XX.....",
        ".....XX.....",
        "XXXX.XX.XXXX",
        "XXXX.XX.XXXX",
        "XXX..XX..XXX",
        "XXX..XX..XXX",
        ".XXX.XX.XXX.",
        "..XX.XX.XX..",
        "...X.XX.X...",
        "...X.XX.X...",
        ".....XX.....",
    ),
    "4 苹果 apple 14x14": art(
        "..............",
        "......XX......",
        "......XX.XX...",
        ".XXXXX..XXXXX.",
        "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX",
        ".XXXXXXXXXXXX.",
        ".XXXXXXXXXXXX.",
        "..XXXXXXXXXX..",
        "..XXXXXXXXXX..",
        "...XXXXXXXX...",
        "...XXXXXXXX...",
        "....XXXXXX....",
        ".....XXXX.....",
    ),
    "5 小房子 house 16x16": art(
        "....XXXXXXXXXX..",
        "...XXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        "XXXXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXXXX",
        "XXX...XXXX...XXX",
        "XXX...XXXX...XXX",
        "XXX...XXXX...XXX",
        "XXXXXXX..XXXXXXX",
        "XXXXXXX..XXXXXXX",
        "XXXXXXX..XXXXXXX",
        "XXXXXXX..XXXXXXX",
        "XXXXXXX..XXXXXXX",
    ),
    "6 小鱼 fish 16x16": art(
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
    ),
    "7 星星 star 20x20": star20(),
    "8 皇冠 crown 20x20": crown20(),
    "9 月亮 moon 24x24": moon24(),
    "10 火箭 rocket 28x28": rocket28(),
}

def render(g, double=True):
    """Render with a cell aspect ratio. double=True -> 2 cols per cell (like a real screen, square-ish pixels)."""
    w = 2 if double else 1
    lines = []
    for row in g:
        lines.append("".join("██" if v else "  " for v in row) if double
                     else "".join("█" if v else "." for v in row))
    return "\n".join(lines)

if __name__ == "__main__":
    for name, g in PATTERNS.items():
        print("=" * 60)
        print(f"[{name}]  rows={len(g)} cols={len(g[0])} filled={sum(sum(r) for r in g)}")
        print(render(g, double=True))
        print()
