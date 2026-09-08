#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端冒烟测试(纯 assert,无 pytest 依赖):
  cd server && python smoke_test.py
覆盖:生成器唯一解 → 每日一题确定性 → 题库保底 → 登录/令牌 → 排行榜计算闭环。
使用临时数据库,不污染正式库。generator 对 HARD 较慢,默认只测 EASY/MEDIUM。
"""
import os
import sys
import tempfile

tmp_db = os.path.join(tempfile.mkdtemp(prefix="xiangsu_smoke_"), "smoke.db")
os.environ["XIANGSU_DB"] = tmp_db

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import db  # noqa: E402
import content  # noqa: E402
import generator  # noqa: E402
from auth import _mock_openid  # noqa: E402
from leaderboard import compute_board, compute_my_best  # noqa: E402


def main():
    db.init_db()

    # 1) 生成器:难度与尺寸合法 + 唯一解等于答案
    for diff in ("EASY", "MEDIUM"):
        level = generator.generate_level(f"smoke-{diff}", diff, name=f"测试-{diff}")
        rows = level["rows"]
        assert len(level["answerGrid"]) == rows and len(level["clueGrid"]) == rows
        assert all(len(r) == rows for r in level["answerGrid"])
        assert generator.verify_unique(level), f"{diff} 生成关卡未通过唯一解校验"
        print(f"[ok] generate {diff}: {rows}x{rows}")

    # 2) 每日一题:同日期两次请求同一道题;难度按星期轮换
    conn = db.connect()
    day = "2026-09-07"  # 星期一(weekday 0) → EASY
    assert content.difficulty_for_date(day) == "EASY"
    assert content.difficulty_for_date("2026-09-08") == "MEDIUM"  # 星期二
    row1 = content.ensure_daily(conn, day)
    row2 = content.ensure_daily(conn, day)
    assert row1["puzzle_id"] == row2["puzzle_id"], "同日期每日一题必须一致"
    assert row1["puzzle_id"] < 0, "网络关卡 id 必须为负"
    level = content.row_to_level(row1)
    assert generator.verify_unique(level), "每日一题未通过唯一解校验"
    print(f"[ok] daily {day} → id={row1['puzzle_id']} {row1['difficulty']}")

    # 3) 题库保底
    content.ensure_bank(conn, "EASY", target=3)
    assert content.bank_count(conn, "EASY") >= 3
    rows, total = content.bank_rows(conn, "EASY", 0, 10)
    assert total >= 3 and len(rows) >= 3
    # bank 列表元信息不含超大答案,取整题接口再验唯一性
    full = content.row_to_level(content.puzzle_row(conn, rows[0]["puzzle_id"]))
    assert generator.verify_unique(full)
    print(f"[ok] bank EASY: {total} 题,抽检唯一性通过")

    # 4) 登录/令牌
    uid1, new1 = db.get_or_create_user(_mock_openid("device-a"), "小A")
    uid2, _ = db.get_or_create_user(_mock_openid("device-b"), "小B")
    assert new1
    tok1 = db.create_token(uid1, 30)
    assert db.user_by_token(tok1)["user_id"] == uid1
    assert db.user_by_token("bogus") is None
    print(f"[ok] auth: user{uid1} / user{uid2}")

    # 5) 排行榜闭环:先提交慢成绩,再提交快成绩,取最优且名次正确
    pid = row1["puzzle_id"]
    for uid, dur in ((uid1, 90_000), (uid1, 70_000), (uid2, 80_000)):
        conn.execute("INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) "
                     "VALUES(?, ?, ?, 'single')", (pid, uid, dur))
    conn.commit()
    entries = compute_board(conn, pid, "", 10)
    assert [e["userId"] for e in entries] == [uid1, uid2]
    assert entries[0]["durationMs"] == 70_000 and entries[0]["rank"] == 1
    assert entries[1]["durationMs"] == 80_000
    mine = compute_my_best(conn, pid, uid1, "")
    assert mine == {"rank": 1, "durationMs": 70_000}
    assert compute_my_best(conn, pid, 99999, "") is None
    print(f"[ok] leaderboard: {entries}")

    conn.close()
    for suffix in ("", "-wal", "-shm"):
        try:
            os.remove(tmp_db + suffix)
        except OSError:
            pass  # Windows WAL 文件偶发占用,忽略
    print("\n全部冒烟测试通过 ✔")


if __name__ == "__main__":
    main()
