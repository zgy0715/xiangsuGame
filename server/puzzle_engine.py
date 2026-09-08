#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""共享谜题引擎(纯标准库,不依赖 FastAPI)。

从 tools/gen_clues.py 抽取而来,服务端(server/)与关卡工具链(tools/)共同使用:
- 绘制原语: new_grid / art / fill_rect / carve_rect / fill_ellipse / carve_ellipse / fill_triangle
- 提示计算: clues_from_answer(PuzzleGenerator.kt 移植)
- 求解器:   Solver / is_unique(PuzzleSolver.kt 移植,回溯+约束传播,最多 2 解用于唯一性判定)
- 稀疏提示: greedy_clues(贪心删提示并保持唯一解)

tools/gen_clues.py 与 tools/verify_clues.py 通过 sys.path 指向本文件导入,
保证"图案改一处,工具链与服务端产题行为一致"。
"""
import time

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

def carve_ellipse(g, cx, cy, rx, ry, strict=False):
    for r in range(len(g)):
        for c in range(len(g[0])):
            dx = (c - cx) / rx
            dy = (r - cy) / ry
            if (dx * dx + dy * dy < 1.0) if strict else (dx * dx + dy * dy <= 1.0):
                g[r][c] = 0

def fill_triangle(g, ar, ac, br, bc0, bc1):
    # matches Levels.kt fillTriangle: int() truncation
    for r in range(ar, br + 1):
        t = (r - ar) / (br - ar) if br > ar else 1.0
        l = int(ac + (bc0 - ac) * t)
        ri = int(ac + (bc1 - ac) * t)
        for c in range(l, ri + 1):
            if 0 <= r < len(g) and 0 <= c < len(g[0]):
                g[r][c] = 1


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
    """唯一解判定:超时返回 None(未知),唯一返回 True,否则 False。"""
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
        if u is None:
            positions.add((r, c))
            continue
        if not u:
            positions.add((r, c))  # removal broke uniqueness → restore
    return positions
