#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SQLite 访问层:短连接 + WAL;6 张表(用户/令牌/邮箱验证码/谜题/每日映射/通关记录)。

表设计(逻辑 → 物理 见作品说明书记档):
  users        账号(email 唯一;openid 列保留并同步写入 email,兼容历史库)
  tokens       Bearer 登录令牌,expires_at > now 有效
  email_otps   邮箱验证码(只存 sha256 哈希;单次使用 + 过期 + 失败计数)
  puzzles      题目主表(内置 id 一律为负整数;客户端约定:正 id=内置关,负 id=网络关)
  daily        每日一题: date → puzzle_id 一一映射
  solve_records 通关记录(单人与对战共用;对战时长由服务端计时覆盖,不可自报)
"""
import datetime
import secrets
import sqlite3
from contextlib import contextmanager

from config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  user_id    INTEGER PRIMARY KEY AUTOINCREMENT,
  openid     TEXT UNIQUE NOT NULL,        -- 历史列:与 email 同步写入,兼容旧库
  email      TEXT,                        -- 登录身份:规范化邮箱(唯一索引见下)
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
CREATE TABLE IF NOT EXISTS email_otps(
  otp_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  email      TEXT NOT NULL,               -- 规范化(小写去空白)后的邮箱
  code_hash  TEXT NOT NULL,               -- sha256(email|code),不存明文
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  expires_at TEXT NOT NULL,
  used       INTEGER NOT NULL DEFAULT 0,  -- 1 = 已消费,不可再用
  attempts   INTEGER NOT NULL DEFAULT 0   -- 校验失败次数,超过阈值即作废
);
CREATE INDEX IF NOT EXISTS idx_otps_email ON email_otps(email, created_at);
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


@contextmanager
def session():
    """短连接会话:正常结束提交,异常回滚,**最后一定 close()**。

    注意:`with sqlite3.connect(...)` 只负责提交/回滚事务,并**不关闭连接**;
    以前全部依赖 CPython 引用计数在函数返回时顺手回收,连接句柄与 WAL 会一直挂着。
    """
    conn = connect()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db():
    conn = connect()
    try:
        conn.executescript(SCHEMA)
        # 迁移:登录方式由"微信式 code"改为"邮箱验证码"后,users 增加 email 列。
        # 旧库(openid 存的是 mock_/微信 openid)把 openid 回填为 email 占位,
        # 保证唯一约束成立且老账号数据不丢;这些账号需用同一邮箱重新登录认领。
        # 注意顺序:email 列必须先补齐,才能建唯一索引(旧库没有该列)。
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(users)").fetchall()}
        if "email" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN email TEXT")
            conn.execute("UPDATE users SET email = openid WHERE email IS NULL")
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email)")
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
        conn.commit()
    finally:
        conn.close()


def execute(sql, params=()):
    """写操作(自动提交)。返回 lastrowid / rowcount 由调用方取 cur。"""
    with session() as conn:
        return conn.execute(sql, params)


def query_one(sql, params=()):
    with session() as conn:
        return conn.execute(sql, params).fetchone()


def query_all(sql, params=()):
    with session() as conn:
        return conn.execute(sql, params).fetchall()


# ---------------- 用户 / 令牌 ----------------

def get_or_create_user(email, nickname_hint=None):
    """按邮箱取账号,不存在则创建。返回 (user_id, is_new)。

    openid 列与 email 同步写入,既满足历史 NOT NULL/UNIQUE 约束,又让新旧库行为一致。
    并发兜底:两个请求同时为同一新邮箱登录时,靠唯一索引 + IntegrityError 复用同一账号,
    而不是让第二个请求 500(以前是 SELECT 后直接 INSERT,存在 check-then-insert 竞态)。
    """
    row = query_one("SELECT user_id, nickname FROM users WHERE email = ?", (email,))
    if row:
        return row["user_id"], False
    try:
        with session() as conn:
            cur = conn.execute(
                "INSERT INTO users(openid, email, nickname) VALUES(?, ?, ?) "
                "ON CONFLICT(email) DO NOTHING",
                (email, email, nickname_hint or "新玩家"))
            if cur.rowcount == 0:
                existing = conn.execute(
                    "SELECT user_id FROM users WHERE email = ?", (email,)).fetchone()
                if existing:
                    return existing["user_id"], False
            user_id = cur.lastrowid
            if not nickname_hint:
                # 默认昵称带上编号,便于排行榜区分
                conn.execute("UPDATE users SET nickname = ? WHERE user_id = ?",
                             (f"玩家{user_id}", user_id))
    except sqlite3.IntegrityError:
        row = query_one("SELECT user_id FROM users WHERE email = ?", (email,))
        if row:
            return row["user_id"], False
        raise
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


# ---------------- 邮箱验证码 ----------------

def insert_otp(email, code_hash, ttl_seconds):
    """写入一条验证码记录;在**同一个事务**里把该邮箱未消费的旧码一并作废。

    以前"作废旧码"与"插入新码"是两次独立提交,两个并发发码请求会互相作废:
    A 插入的码可能被 B 顺手置为已用,用户刚收到的码立刻失效。
    """
    with session() as conn:
        conn.execute("UPDATE email_otps SET used = 1 WHERE email = ? AND used = 0", (email,))
        conn.execute(
            "INSERT INTO email_otps(email, code_hash, expires_at) "
            "VALUES(?, ?, datetime('now', ?))",
            (email, code_hash, f"+{int(ttl_seconds)} seconds"))


def latest_otp(email):
    """该邮箱最新一条验证码记录(过期/已用/超次由调用方判定)。"""
    return query_one(
        "SELECT * FROM email_otps WHERE email = ? ORDER BY otp_id DESC LIMIT 1", (email,))


def otp_by_hash(email, code_hash):
    """按邮箱 + 码哈希精确查找(用于识别"用户填的是上一封邮件的旧码")。"""
    return query_one(
        "SELECT * FROM email_otps WHERE email = ? AND code_hash = ? "
        "ORDER BY otp_id DESC LIMIT 1", (email, code_hash))


def bump_otp_attempts(otp_id):
    execute("UPDATE email_otps SET attempts = attempts + 1 WHERE otp_id = ?", (otp_id,))


def consume_otp(otp_id):
    """消费验证码(置 used=1),返回受影响行数;并发下只有一方能得到 1。"""
    return execute("UPDATE email_otps SET used = 1 WHERE otp_id = ? AND used = 0",
                   (otp_id,)).rowcount


def count_otps_since(email, seconds):
    """限流用:该邮箱最近 seconds 秒内发出的验证码条数。"""
    row = query_one(
        "SELECT COUNT(*) AS n FROM email_otps "
        "WHERE email = ? AND created_at > datetime('now', ?)",
        (email, f"-{int(seconds)} seconds"))
    return row["n"] if row else 0


def last_otp_sent_at(email):
    """限流用:该邮箱最近一次发码时间(UTC 字符串),无记录返回 None。"""
    row = query_one("SELECT MAX(created_at) AS t FROM email_otps WHERE email = ?", (email,))
    return row["t"] if row and row["t"] else None


def purge_expired_otps(days=7):
    """清理历史验证码记录(保留 7 天便于排查),返回删除行数。"""
    return execute("DELETE FROM email_otps WHERE created_at < datetime('now', ?)",
                   (f"-{int(days)} days",)).rowcount


# ---------------- 谜题 ----------------

def next_puzzle_id(conn):
    """分配一个不会与现有题目/历史成绩撞车的负 id。

    ⚠️ 必须留在 Int32 范围内:客户端 `LevelDto.id` 是 Kotlin `Int`(上限 21 亿),
    超出会让 kotlinx-serialization 解析溢出、整个接口在客户端侧失败 ——
    曾经用"时间戳毫秒"派生 id(≈-1.79e12),直接把每日一题打成了"离线"。

    取值方式:puzzles 与 solve_records 两边的 MIN(puzzle_id) 再减一。
    旧实现只看 puzzles.MIN:puzzles 表被清空后 id 会从 -1 重新发放,
    而 solve_records 里的历史负 id 还在,历史成绩会张冠李戴挂到新题上。
    """
    row = conn.execute(
        "SELECT MIN(lo) AS m FROM ("
        "  SELECT MIN(puzzle_id) AS lo FROM puzzles"
        "  UNION ALL"
        "  SELECT MIN(puzzle_id) AS lo FROM solve_records)").fetchone()
    smallest = row["m"] if row and row["m"] is not None else 0
    return smallest - 1 if smallest < 0 else -1


def insert_puzzle(conn, puzzle_id, level, source):
    """level 为 generator.generate_level 产物(dict)。"""
    import json
    conn.execute(
        "INSERT INTO puzzles(puzzle_id, name, difficulty, rows, cols, clue_json, answer_json, source) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
        (puzzle_id, level["name"], level["difficulty"], level["rows"], level["cols"],
         json.dumps(level["clueGrid"], ensure_ascii=False),
         json.dumps(level["answerGrid"], ensure_ascii=False), source))
