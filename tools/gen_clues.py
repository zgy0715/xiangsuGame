#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate sparse clue positions for the NEW pattern answers, guaranteeing unique solution.
Ports PuzzleSolver.kt + PuzzleGenerator.kt + greedy clue removal.
Outputs Kotlin `setOf(...)` literals ready to paste into SparseClues.kt.
"""
import math
import time
import sys


# ---------------- drawing primitives (port of Levels.kt) ----------------

def new_grid(rows, cols):
    return [[0] * cols for _ in range(rows)]

def art(*rows):
    return [[1 if ch == 'X' else 0 for ch in row] for row in rows]

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

def carve_diamond(g, r, c):
    for dr, dc in [(0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)]:
        nr, nc = r + dr, c + dc
        if 0 <= nr < len(g) and 0 <= nc < len(g[0]):
            g[nr][nc] = 0

def fill_triangle(g, ar, ac, br, bc0, bc1):
    # matches Levels.kt fillTriangle: int() truncation
    for r in range(ar, br + 1):
        t = (r - ar) / (br - ar) if br > ar else 1.0
        l = int(ac + (bc0 - ac) * t)
        ri = int(ac + (bc1 - ac) * t)
        for c in range(l, ri + 1):
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


# ---------------- Pattern answers (must match Levels.kt exactly) ----------------

def heart():
    return art(
        ".XX....XX.", "XXXX..XXXX", "XXXX..XXXX", "XXXXXXXXXX", "XXXXXXXXXX",
        "XXXXXXXXXX", ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX....")

def smiley():
    return art(
        ".XXXXXXXX.", "XXXXXXXXXX", "XX..XX..XX", "XX..XX..XX", "XXXXXXXXXX",
        "XXXXXXXXXX", "XXXX..XXXX", "XXX....XXX", "XX......XX", ".XXXXXXXX.")

def butterfly():
    return art(
        "....X..X....", ".....XX.....", ".....XX.....", "XXX..XX..XXX", "XXXX.XX.XXXX",
        "XXXX.XX.XXXX", "XXX..XX..XXX", ".XX..XX..XX.", "..XX.XX.XX..", "...X.XX.X...",
        "...X.XX.X...", ".....XX.....")

def apple():
    return art(
        "..............", "......XX......", ".....XXXX.....", ".XXXXX..XXXXX.", ".XXXXX..XXXXX.",
        "XXXXXXXXXXXXXX", "XXXXXXXXXXXXXX", ".XXXXXXXXXXXX.", ".XXXXXXXXXXXX.", "..XXXXXXXXXX..",
        "..XXXXXXXXXX..", "...XXXXXXXX...", "....XXXXXX....", ".....XXXX.....")

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
    g = new_grid(20, 20)
    cx, cy, outer = 9.5, 10.4, 9.5
    verts = star_vertices(cx, cy, outer, outer * 0.42)
    for r in range(20):
        for c in range(20):
            if point_in_polygon(c + 0.5, r + 0.5, verts):
                g[r][c] = 1
    return g

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
    for (r, c) in [(6, 6), (8, 12), (10, 17), (14, 8), (16, 15), (18, 11)]:
        carve_diamond(g, r, c)
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

def castle():
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


PATTERNS = {
    "heart": heart, "smiley": smiley, "butterfly": butterfly, "apple": apple,
    "house": house, "fish": fish, "star": star, "crown": crown,
    "moon": moon, "rocket": rocket, "castle": castle,
}


# ---------------- Clue computation (PuzzleGenerator port) ----------------

def clues_from_answer(answer, positions=None):
    rows, cols = len(answer), len(answer[0])
    clues = [[-1] * cols for _ in range(rows)]
    for r in range(rows):
        for c in range(cols):
            if positions is not None and (r, c) not in positions:
                continue
            count = 0
            for dr in (-1, 0, 1):
                for dc in (-1, 0, 1):
                    nr, nc = r + dr, c + dc
                    if 0 <= nr < rows and 0 <= nc < cols and answer[nr][nc] == 1:
                        count += 1
            clues[r][c] = count
    return clues


# ---------------- Solver (PuzzleSolver port) ----------------

class Solver:
    def __init__(self, clues, max_solutions=2, time_limit=None):
        self.clues = clues
        self.rows = len(clues)
        self.cols = len(clues[0])
        self.max_solutions = max_solutions
        self.board = [[-1] * self.cols for _ in range(self.rows)]
        self.solutions = []
        self.deadline = (time.time() + time_limit) if time_limit else None

    def solve(self):
        self._dfs()
        return self.solutions

    def _timed_out(self):
        return self.deadline is not None and time.time() > self.deadline

    def _dfs(self):
        if len(self.solutions) >= self.max_solutions:
            return
        if self._timed_out():
            raise TimeoutError("solver timed out")

        propagated = []
        changed = True
        while changed:
            changed = False
            for r in range(self.rows):
                for c in range(self.cols):
                    if self.clues[r][c] < 0:
                        continue
                    target = self.clues[r][c]
                    filled = 0
                    unknown_cells = []
                    for dr in (-1, 0, 1):
                        for dc in (-1, 0, 1):
                            nr, nc = r + dr, c + dc
                            if 0 <= nr < self.rows and 0 <= nc < self.cols:
                                v = self.board[nr][nc]
                                if v == 1:
                                    filled += 1
                                elif v == -1:
                                    unknown_cells.append((nr, nc))
                    need = target - filled
                    if need < 0 or need > len(unknown_cells):
                        for (rr, cc) in propagated:
                            self.board[rr][cc] = -1
                        return
                    if need == 0 and unknown_cells:
                        for (nr, nc) in unknown_cells:
                            self.board[nr][nc] = 0
                            propagated.append((nr, nc))
                        changed = True
                    elif need == len(unknown_cells) and unknown_cells:
                        for (nr, nc) in unknown_cells:
                            self.board[nr][nc] = 1
                            propagated.append((nr, nc))
                        changed = True

        # branch
        nxt = None
        for r in range(self.rows):
            for c in range(self.cols):
                if self.board[r][c] == -1:
                    nxt = (r, c)
                    break
            if nxt:
                break

        if nxt is None:
            if self._is_valid():
                self.solutions.append([row[:] for row in self.board])
            for (rr, cc) in propagated:
                self.board[rr][cc] = -1
            return

        for v in (1, 0):
            self.board[nxt[0]][nxt[1]] = v
            self._dfs()
            self.board[nxt[0]][nxt[1]] = -1
            if len(self.solutions) >= self.max_solutions:
                break
        for (rr, cc) in propagated:
            self.board[rr][cc] = -1

    def _is_valid(self):
        for r in range(self.rows):
            for c in range(self.cols):
                if self.clues[r][c] < 0:
                    continue
                filled = 0
                for dr in (-1, 0, 1):
                    for dc in (-1, 0, 1):
                        nr, nc = r + dr, c + dc
                        if 0 <= nr < self.rows and 0 <= nc < self.cols and self.board[nr][nc] == 1:
                            filled += 1
                if filled != self.clues[r][c]:
                    return False
        return True


# ---------------- Greedy sparse-clue generation ----------------

def is_unique(answer, positions, time_limit=30):
    clues = clues_from_answer(answer, positions)
    sol = Solver(clues, max_solutions=2, time_limit=time_limit)
    try:
        solutions = sol.solve()
    except TimeoutError:
        return None  # timed out — unknown
    return len(solutions) == 1


def greedy_clues(answer, target_density=0.30, time_limit=30):
    """Greedily remove clue positions while keeping a unique solution.
    Keeps removing until target_density of cells remain (or no more can be removed)."""
    rows, cols = len(answer), len(answer[0])
    all_cells = [(r, c) for r in range(rows) for c in range(cols)]
    # Start from FULL clues (all cells) — guaranteed unique by construction check.
    positions = set(all_cells)
    # Verify full set is unique & matches answer
    if not is_unique(answer, positions, time_limit):
        raise RuntimeError("full-clue set is not unique!")
    # Remove in a fixed deterministic order (checkerboard first for even spread)
    order = sorted(all_cells, key=lambda p: (p[0] + p[1]) % 2)  # mix parity
    total = rows * cols
    target = max(8, int(total * target_density))
    start = time.time()
    checked = 0
    for (r, c) in order:
        if time.time() - start > time_limit * 20:
            print(f"    [warn] time budget exceeded, stopping early at {len(positions)} clues")
            break
        if len(positions) <= target:
            break
        if (r, c) not in positions:
            continue
        positions.remove((r, c))
        u = is_unique(answer, positions, time_limit=time_limit)
        checked += 1
        if u is None:
            positions.add((r, c))
            continue
        if not u:
            positions.add((r, c))  # removal broke uniqueness → restore
    return positions


# ---------------- Kotlin output ----------------

def to_kotlin_set(name, positions):
    sorted_p = sorted(positions)
    lines = []
    line = []
    for (r, c) in sorted_p:
        tok = f"({r} to {c})"
        line.append(tok)
    # chunk into lines of ~9 entries
    parts = []
    for i in range(0, len(line), 9):
        parts.append(", ".join(line[i:i+9]))
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
        if name in ("castle",):
            density = 0.9  # castle: keep dense so solver stays fast & unique
        t0 = time.time()
        pos = greedy_clues(ans, target_density=density, time_limit=15)
        dt = time.time() - t0
        print(f"  generated {len(pos)} clues in {dt:.1f}s; verifying uniqueness...")
        assert is_unique(ans, pos), f"{name}: generated set NOT unique!"
        print(f"  VERIFIED unique. density={len(pos)/(len(ans)*len(ans[0])):.3f}")
        print(to_kotlin_set(name, pos))
        print()
