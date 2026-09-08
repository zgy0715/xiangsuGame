#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题目内容层(FastAPI 无关,便于冒烟测试直连):题库保底、每日一题确定性生成。

- ensure_bank:  按难度补足题库数量(生成即入库,带唯一解校验)
- ensure_daily: 某日历日首次请求时确定性生成并入库;此后同日期直接读库
  (同一天全服同题;种子确定性保证即便丢库重建也能还原同一道题)
"""
import datetime
import secrets
import threading
import zoneinfo

import db
import generator
from config import BANK_TARGET, TIMEZONE

_lock = threading.Lock()  # 生成/入库串行化,防启动预热与请求并发重复生成


def today_cn():
    return datetime.datetime.now(zoneinfo.ZoneInfo(TIMEZONE)).strftime("%Y-%m-%d")


def difficulty_for_date(day):
    """难度按星期轮换(EASY/MEDIUM/HARD 三段循环),同一日期永远同一难度。"""
    weekday = datetime.date.fromisoformat(day).weekday()  # 0..6
    return ("EASY", "MEDIUM", "HARD")[weekday % 3]


def row_to_level(row):
    """puzzles 表行 → 完整关卡 JSON(与客户端 LevelDto 字段一一对应)。"""
    import json
    return {
        "id": row["puzzle_id"],
        "name": row["name"],
        "difficulty": row["difficulty"],
        "rows": row["rows"],
        "cols": row["cols"],
        "clueGrid": json.loads(row["clue_json"]),
        "answerGrid": json.loads(row["answer_json"]),
        "timedLimitSeconds": None,
    }


def puzzle_row(conn, puzzle_id):
    return conn.execute("SELECT * FROM puzzles WHERE puzzle_id = ?", (puzzle_id,)).fetchone()


def ensure_daily(conn, day, difficulty=None):
    """返回该日关卡 row(必要时现场生成)。difficulty 为 None 时按星期轮换。"""
    with _lock:
        row = conn.execute(
            "SELECT p.* FROM daily d JOIN puzzles p ON p.puzzle_id = d.puzzle_id "
            "WHERE d.daily_date = ?", (day,)).fetchone()
        if row:
            return row
        diff = difficulty or difficulty_for_date(day)
        level = None
        for k in range(6):
            seed = f"xiangsu-daily-{day}-{k}"
            try:
                level = generator.generate_level(seed, diff, name=f"每日一题 {day}")
                break
            except RuntimeError:
                continue
        if level is None:
            raise RuntimeError(f"daily generation failed for {day}")
        puzzle_id = db.next_puzzle_id(conn)
        db.insert_puzzle(conn, puzzle_id, level, source="daily")
        conn.execute("INSERT INTO daily(daily_date, puzzle_id) VALUES(?, ?)", (day, puzzle_id))
        conn.commit()
        return conn.execute("SELECT * FROM puzzles WHERE puzzle_id = ?", (puzzle_id,)).fetchone()


def bank_count(conn, difficulty):
    return conn.execute(
        "SELECT COUNT(*) AS n FROM puzzles WHERE source = 'bank' AND difficulty = ?",
        (difficulty,)).fetchone()["n"]


def ensure_bank(conn, difficulty, target=None):
    """把某难度题库补足到 target(BANK_TARGET 默认);并发安全。"""
    with _lock:
        target = target or BANK_TARGET.get(difficulty, 10)
        while bank_count(conn, difficulty) < target:
            pid = db.next_puzzle_id(conn)
            level = None
            for k in range(3):
                try:
                    level = generator.generate_level(
                        secrets.token_hex(8), difficulty, name=f"像素方块 {abs(pid)}")
                    break
                except RuntimeError:
                    continue
            if level is None:
                raise RuntimeError(f"bank generation failed for {difficulty}")
            db.insert_puzzle(conn, pid, level, source="bank")
            conn.commit()


def bank_rows(conn, difficulty=None, offset=0, limit=20):
    sql = ("SELECT p.*, (SELECT COUNT(*) FROM solve_records s WHERE s.puzzle_id = p.puzzle_id) AS solves "
           "FROM puzzles p WHERE p.source = 'bank'")
    params = []
    if difficulty:
        sql += " AND p.difficulty = ?"
        params.append(difficulty)
    sql += " ORDER BY p.created_at DESC, p.puzzle_id ASC LIMIT ? OFFSET ?"
    params += [limit, offset]
    return conn.execute(sql, params).fetchall(), (conn.execute(
        "SELECT COUNT(*) AS n FROM puzzles WHERE source='bank'" +
        (" AND difficulty = ?" if difficulty else ""),
        [difficulty] if difficulty else []).fetchone()["n"])
