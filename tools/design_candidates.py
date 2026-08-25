#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Candidate designs for each level pattern, rendered for visual comparison."""
import math
from render_patterns import new_grid, fill_rect, carve_rect, fill_ellipse, carve_ellipse, \
    carve_diamond, fill_triangle, star_vertices, point_in_polygon

def art(*rows):
    return [[1 if ch == 'X' else 0 for ch in row] for row in rows]

def render(g):
    return "\n".join("".join("██" if v else "  " for v in row) for row in g)

def show(name, g):
    print(f"===== {name}  {len(g)}x{len(g[0])}  filled={sum(sum(r) for r in g)}")
    print(render(g))
    print()

# ---------- HEART ----------
show("HEART-A (V-notch, 3 solid rows)",
     art(".XX....XX.", "XXXX..XXXX", "XXXXXXXXXX", "XXXXXXXXXX", "XXXXXXXXXX",
         "XXXXXXXXXX", ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX...."))
show("HEART-B (notch 2 rows, wider lobes)",
     art(".XX....XX.", "XXXX..XXXX", "XXXX..XXXX", "XXXXXXXXXX", "XXXXXXXXXX",
         "XXXXXXXXXX", ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX...."))
show("HEART-C (V-notch, point at bottom)",
     art(".XX....XX.", "XXXX..XXXX", "XXXXXXXXXX", "XXXXXXXXXX", "XXXXXXXXXX",
         ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX....", ".........."))

# ---------- BUTTERFLY ----------
show("BUTTERFLY-A (current)",
     art("....X..X....", ".....XX.....", ".....XX.....", "XXXX.XX.XXXX", "XXXX.XX.XXXX",
         "XXX..XX..XXX", "XXX..XX..XXX", ".XXX.XX.XXX.", "..XX.XX.XX..", "...X.XX.X...",
         "...X.XX.X...", ".....XX....."))
show("BUTTERFLY-B (rounder wings)",
     art("...XX..XX...", "..XXXX.XXXX.", ".XXX.XX.XXX.", "XXXX.XX.XXXX", "XXXX.XX.XXXX",
         "XXX..XX..XXX", "XX...XX...XX", "XX...XX...XX", ".XX..XX..XX.", "..X.XX.XX.X.",
         "..X.XX.XX.X.", "....XXXX...."))

# ---------- APPLE ----------
show("APPLE-A (dimple + stem, round bottom)",
     art("..............", "......XX......", ".....XXXX.....", ".XXXXX..XXXXX.",
         ".XXXXX..XXXXX.", "XXXXXXXXXXXXXX", "XXXXXXXXXXXXXX", ".XXXXXXXXXXXX.",
         ".XXXXXXXXXXXX.", "..XXXXXXXXXX..", "..XXXXXXXXXX..", "...XXXXXXXX...",
         "....XXXXXX....", ".....XXXX....."))
show("APPLE-B (with leaf)",
     art("..............", ".......XX.....", ".....XX.XX....", "...XXXX..XX...",
         "..XXXX...XX...", ".XXXX....XXXX..", ".XXXX....XXXX..", "XXXXXXXXXXXXXX",
         "XXXXXXXXXXXXXX", ".XXXXXXXXXXXX.", ".XXXXXXXXXXXX.", "..XXXXXXXXXX..",
         "...XXXXXXXX...", "....XXXXXX...."))

# ---------- HOUSE ----------
show("HOUSE-A (centered triangle roof)",
     art(".......XX.......", "......XXXX......", ".....XXXXXX.....", "....XXXXXXXX....",
         "...XXXXXXXXXX...", "..XXXXXXXXXXXX..", ".XXXXXXXXXXXXXX.", "XXXXXXXXXXXXXXXX",
         "XXXXXXXXXXXXXXXX", "XXX...XXXX...XXX", "XXX...XXXX...XXX", "XXXXXXX..XXXXXXX",
         "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX", "XXXXXXX..XXXXXXX"))

# ---------- FISH ----------
show("FISH-A (current)",
     art("................", "................", "................", ".....XXXXXXXX...",
         "..X..XXXXXXXXX..", ".XXX.XXXXXXXXXXX", "XXXX.XXXXXXXXXXX", "XXXX.XXXXXXX.XXX",
         "XXXX.XXXXXXX.XXX", "XXXX.XXXXXXXXXXX", ".XXX.XXXXXXXXXXX", "..X..XXXXXXXXX..",
         ".....XXXXXXXX...", "................", "................", "................"))
show("FISH-B (bigger, rounder)",
     art("................", "................", "................", ".....XXXXXXXXX..",
         "....XXXXXXXXXXX.", "...XXXXXXXXXXXXX", "..XXXXXXXXXXXXXX", ".XXX.XXXXXXXXXXX",
         ".XXX.XXXXXXXXXXX", "..XXXXXXXXXXXXXX", "...XXXXXXXXXXXXX", "....XXXXXXXXXXX.",
         ".....XXXXXXXXX..", "................", "................", "................"))

# ---------- STAR ----------
def solid_star20(inner_ratio):
    g = new_grid(20, 20)
    cx, cy, outer = 9.5, 9.5, 9.5
    verts = star_vertices(cx, cy, outer, outer * inner_ratio)
    for r in range(20):
        for c in range(20):
            if point_in_polygon(c + 0.5, r + 0.5, verts):
                g[r][c] = 1
    return g
show("STAR-A (current ring)", [
    [1 if point_in_polygon(c + 0.5, r + 0.5, star_vertices(9.5, 9.5, 9.5, 9.5*0.36))
         and not point_in_polygon(c + 0.5, r + 0.5, star_vertices(9.5, 9.5, 9.5*0.55, 9.5*0.55*0.36))
     else 0 for c in range(20)] for r in range(20)])
show("STAR-B (solid 0.5)", solid_star20(0.5))
show("STAR-C (solid 0.42)", solid_star20(0.42))

# ---------- MOON ----------
def moon(outer_cy, carve_cx, carve_rx, carve_ry):
    g = new_grid(24, 24)
    fill_ellipse(g, 12, outer_cy, 10, 10)
    carve_ellipse(g, carve_cx, outer_cy, carve_rx, carve_ry)
    return g
show("MOON-A (carve 15,12 r8)", moon(12, 15, 8, 8))
show("MOON-B (carve 16,12 r7.5)", moon(12, 16, 7.5, 7.5))

def moon_with_craters(outer_cx, outer_cy, outer_r, carve_cx, carve_cy, carve_r):
    g = new_grid(24, 24)
    fill_ellipse(g, outer_cx, outer_cy, outer_r, outer_r)
    if carve_r > 0:
        carve_ellipse(g, carve_cx, carve_cy, carve_r, carve_r)
    return g

def craters(g, pts):
    for (r, c) in pts:
        carve_diamond(g, r, c)
    return g

show("MOON-C (thin crescent r9 / carve r8 off2)",
     craters(moon_with_craters(12, 12, 9, 14, 12, 8), [(7, 5), (10, 9)]))
show("MOON-D (full moon + craters)",
     craters(moon_with_craters(12, 12, 10, 0, 0, 0),
             [(6, 6), (8, 12), (10, 17), (14, 8), (16, 15), (18, 11)]))

# ---------- ROCKET ----------
def rocket():
    g = new_grid(28, 28)
    fill_rect(g, 9, 9, 20, 18)
    fill_triangle(g, 1, 13, 9, 9, 18)
    carve_rect(g, 12, 12, 15, 15)
    fill_triangle(g, 19, 9, 24, 4, 10)
    fill_triangle(g, 19, 18, 24, 17, 23)
    fill_triangle(g, 21, 13, 27, 11, 16)
    return g
show("ROCKET-A (current)", rocket())

# ---------- CASTLE ----------
from render_patterns import castle64
show("CASTLE (64x64, scaled rows/cols 1:1)", castle64())
