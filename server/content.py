#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题目内容层(FastAPI 无关,便于冒烟测试直连):题库保底、每日一题确定性生成。

- ensure_bank:  按难度补足题库数量(生成即入库,带唯一解校验)
- ensure_daily: 某日历日首次请求时确定性生成并入库;此后同日期直接读库
  (同一天全服同题;种子是确定性的,但**产题过程有时间预算**(贪心去提示与
  唯一解校验都有秒级上限),机器快慢/负载不同可能落到不同的重试种子,
  因此"丢库重建能还原同一道题"并不严格成立 —— 真正的持久化保证是 SQLite 里的
  puzzles/daily 两张表,重建请从备份恢复,不要指望重新生成出同一题。
"""
import datetime
import secrets
import threading
import zoneinfo

import db
import generator
from config import BANK_TARGET, TIMEZONE

_lock = threading.Lock()  # 生成/入库串行化,防启动预热与请求并发重复生成

VALID_DIFFICULTIES = ("EASY", "MEDIUM", "HARD")


def normalize_difficulty(value):
    """难度必须是大写三档之一;以前传 'easy' 会让 generator 抛 KeyError 变成 500。"""
    if value is None or value == "":
        return None
    text = str(value).strip().upper()
    if text not in VALID_DIFFICULTIES:
        raise ValueError(f"非法难度: {value}")
    return text


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


def _daily_row(conn, day):
    return conn.execute(
        "SELECT p.* FROM daily d JOIN puzzles p ON p.puzzle_id = d.puzzle_id "
        "WHERE d.daily_date = ?", (day,)).fetchone()


def ensure_daily(conn, day, difficulty=None):
    """返回该日关卡 row(必要时现场生成)。difficulty 为 None 时按星期轮换。

    产题放在锁外:HARD 的贪心预算是 time_limit×20 秒,而 is_unique 还会再花时间,
    以前整段持全局锁生成,会把所有 /bank、/daily 请求压在锁上(FastAPI 同步端点
    靠线程池,堆满后整站不可用)。生成后回锁内二次检查,避免同一日期被生成两道。
    """
    with _lock:
        row = _daily_row(conn, day)
        if row:
            return row
    diff = normalize_difficulty(difficulty) or difficulty_for_date(day)
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
    with _lock:
        row = _daily_row(conn, day)  # 二次检查:并发请求可能已经生成好了
        if row:
            return row
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
    """把某难度题库补足到 target(BANK_TARGET 默认);产题在锁外,入库在锁内。

    以前 `while 数量不足: 持锁产题并入库`,单题最坏十几秒,冷启动预热或首个
    在线关请求会让整站接口一起等锁;现在只把"计数 + 写库"放进锁里。
    """
    difficulty = normalize_difficulty(difficulty)
    target = int(target or BANK_TARGET.get(difficulty, 10))
    for _ in range(max(1, target) * 4):  # 上限保护:生成一直失败时不要无限循环
        with _lock:
            if bank_count(conn, difficulty) >= target:
                return
        level = None
        for _k in range(3):
            try:
                level = generator.generate_level(secrets.token_hex(8), difficulty)
                break
            except RuntimeError:
                continue
        if level is None:
            raise RuntimeError(f"bank generation failed for {difficulty}")
        with _lock:
            if bank_count(conn, difficulty) >= target:
                return
            pid = db.next_puzzle_id(conn)
            level["name"] = f"像素方块 {abs(pid)}"
            db.insert_puzzle(conn, pid, level, source="bank")
            conn.commit()


def bank_rows(conn, difficulty=None, offset=0, limit=20):
    difficulty = normalize_difficulty(difficulty)
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
