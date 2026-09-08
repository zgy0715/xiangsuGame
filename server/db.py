#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SQLite 访问层:短连接 + WAL;5 张表(用户/令牌/谜题/每日映射/通关记录)。

表设计(逻辑 → 物理 见作品说明书记档):
  users        账号(openid 唯一;mock 模式下 openid = 由 code 派生)
  tokens       Bearer 登录令牌,expires_at > now 有效
  puzzles      题目主表(内置 id 一律为负整数;客户端约定:正 id=内置关,负 id=网络关)
  daily        每日一题: date → puzzle_id 一一映射
  solve_records 通关记录(单人与对战共用;对战时长由服务端计时覆盖,不可自报)
"""
import datetime
import secrets
import sqlite3

from config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  user_id    INTEGER PRIMARY KEY AUTOINCREMENT,
  openid     TEXT UNIQUE NOT NULL,
  nickname   TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS tokens(
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(user_id),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);
CREATE TABLE IF NOT EXISTS puzzles(
  puzzle_id  INTEGER PRIMARY KEY,        -- 一律为负整数(客户端命名空间约定)
  name       TEXT NOT NULL,
  difficulty TEXT NOT NULL,              -- 'EASY' | 'MEDIUM' | 'HARD'
  rows       INT NOT NULL,
  cols       INT NOT NULL,
  clue_json  TEXT NOT NULL,
  answer_json TEXT NOT NULL,
  source     TEXT NOT NULL DEFAULT 'bank',  -- 'bank'(题库) | 'daily'(每日一题)
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS daily(
  daily_date TEXT PRIMARY KEY,
  puzzle_id  INTEGER NOT NULL REFERENCES puzzles(puzzle_id)
);
CREATE TABLE IF NOT EXISTS solve_records(
  record_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  puzzle_id   INTEGER NOT NULL,           -- 无外键:房主对战可选"内置关"(正 id,不在服务端 puzzles 表)
  user_id     INTEGER NOT NULL REFERENCES users(user_id),
  duration_ms INTEGER NOT NULL,
  source      TEXT NOT NULL DEFAULT 'single',  -- 'single'(单人) | 'race'(对战,服务器计时)
  finished_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_records_puzzle ON solve_records(puzzle_id);
CREATE INDEX IF NOT EXISTS idx_records_user ON solve_records(user_id);
"""


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db():
    with connect() as conn:
        conn.executescript(SCHEMA)
        # 一次性迁移:旧 schema 的 solve_records 带 puzzle_id 外键(会拒绝内置关对局成绩),
        # 检测到则重建该表去外键(仅丢失历史成绩,开发期可接受)
        fks = conn.execute(
            "SELECT 1 FROM pragma_foreign_key_list('solve_records') WHERE \"table\" = 'puzzles'"
        ).fetchone()
        if fks:
            conn.executescript(
                "DROP TABLE solve_records;"
                "CREATE TABLE solve_records("
                "  record_id   INTEGER PRIMARY KEY AUTOINCREMENT,"
                "  puzzle_id   INTEGER NOT NULL,"
                "  user_id     INTEGER NOT NULL REFERENCES users(user_id),"
                "  duration_ms INTEGER NOT NULL,"
                "  source      TEXT NOT NULL DEFAULT 'single',"
                "  finished_at TEXT NOT NULL DEFAULT (datetime('now'))"
                ");"
                "CREATE INDEX IF NOT EXISTS idx_records_puzzle ON solve_records(puzzle_id);"
                "CREATE INDEX IF NOT EXISTS idx_records_user ON solve_records(user_id);"
            )


def execute(sql, params=()):
    """写操作(自动提交)。返回 lastrowid / rowcount 由调用方取 cur。"""
    with connect() as conn:
        cur = conn.execute(sql, params)
        conn.commit()
        return cur


def query_one(sql, params=()):
    with connect() as conn:
        return conn.execute(sql, params).fetchone()


def query_all(sql, params=()):
    with connect() as conn:
        return conn.execute(sql, params).fetchall()


# ---------------- 用户 / 令牌 ----------------

def get_or_create_user(openid, nickname_hint=None):
    """返回 (user_id, is_new)。"""
    row = query_one("SELECT user_id, nickname FROM users WHERE openid = ?", (openid,))
    if row:
        return row["user_id"], False
    with connect() as conn:
        cur = conn.execute("INSERT INTO users(openid, nickname) VALUES(?, ?)",
                           (openid, nickname_hint or "新玩家"))
        user_id = cur.lastrowid
        if not nickname_hint:
            # 默认昵称带上编号,便于排行榜区分
            conn.execute("UPDATE users SET nickname = ? WHERE user_id = ?",
                         (f"玩家{user_id}", user_id))
        conn.commit()
    return user_id, True


def set_nickname(user_id, nickname):
    execute("UPDATE users SET nickname = ? WHERE user_id = ?", (nickname.strip()[:20], user_id))


def create_token(user_id, ttl_days):
    token = secrets.token_hex(24)
    expires = (datetime.datetime.utcnow() + datetime.timedelta(days=ttl_days)).strftime("%Y-%m-%d %H:%M:%S")
    execute("INSERT INTO tokens(token, user_id, expires_at) VALUES(?, ?, ?)",
            (token, user_id, expires))
    return token


def user_by_token(token):
    return query_one(
        "SELECT t.token, u.user_id, u.nickname FROM tokens t "
        "JOIN users u ON u.user_id = t.user_id "
        "WHERE t.token = ? AND t.expires_at > datetime('now')", (token,))


def delete_token(token):
    execute("DELETE FROM tokens WHERE token = ?", (token,))


# ---------------- 谜题 ----------------

def next_puzzle_id(conn):
    row = conn.execute("SELECT MIN(puzzle_id) AS m FROM puzzles").fetchone()
    return (row["m"] if row and row["m"] is not None else 0) - 1


def insert_puzzle(conn, puzzle_id, level, source):
    """level 为 generator.generate_level 产物(dict)。"""
    import json
    conn.execute(
        "INSERT INTO puzzles(puzzle_id, name, difficulty, rows, cols, clue_json, answer_json, source) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
        (puzzle_id, level["name"], level["difficulty"], level["rows"], level["cols"],
         json.dumps(level["clueGrid"], ensure_ascii=False),
         json.dumps(level["answerGrid"], ensure_ascii=False), source))
