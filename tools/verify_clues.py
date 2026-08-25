#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check whether the EXISTING SparseClues.kt positions still give a UNIQUE solution
for the NEW answers. Only levels that fail here need clue regeneration."""
import re
import sys
import time
sys.path.insert(0, ".")
import gen_clues as gc


def parse_existing(path):
    src = open(path, encoding="utf-8").read()
    # 每个关卡块形如：val name: Set<Pair<Int, Int>> = setOf( ...换行+4空格缩进的" )" 结束
    blocks = re.findall(r"val (\w+): Set<Pair<Int, Int>> = setOf\((.*?)\n    \)", src, re.S)
    result = {}
    for name, body in blocks:
        pos = set()
        for m in re.finditer(r"\((\d+) to (\d+)\)", body):
            pos.add((int(m.group(1)), int(m.group(2))))
        result[name] = pos
    return result


def check_unique(answer, positions, time_limit=60):
    clues = gc.clues_from_answer(answer, positions)
    sol = gc.Solver(clues, max_solutions=2, time_limit=time_limit)
    try:
        sols = sol.solve()
    except TimeoutError:
        return None
    if len(sols) != 1:
        return False
    # also confirm the unique solution == answer
    s = sols[0]
    rows, cols = len(answer), len(answer[0])
    return all(answer[r][c] == s[r][c] for r in range(rows) for c in range(cols))


def main():
    existing = parse_existing("app/src/main/java/com/example/xiangsugame/model/SparseClues.kt")
    for name, fn in gc.PATTERNS.items():
        if name not in existing:
            print(f"{name}: NO EXISTING CLUES, skip")
            continue
        ans = fn()
        pos = existing[name]
        t0 = time.time()
        u = check_unique(ans, pos, time_limit=90)
        dt = time.time() - t0
        status = "OK (unique, keep)" if u is True else ("TIMEOUT" if u is None else "NOT-UNIQUE (regenerate)")
        print(f"{name:10s} clues={len(pos):4d}  {status}  ({dt:.1f}s)")


if __name__ == "__main__":
    main()
