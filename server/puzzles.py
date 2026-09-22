#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""谜题路由:题库列表 / 每日一题 / 按 id 取整题(含答案,客户端本地校验用)。"""
import datetime

from fastapi import APIRouter, HTTPException, Query

import content
import db
from config import BANK_TARGET

router = APIRouter(prefix="/api/puzzles", tags=["puzzles"])

# 每日一题只允许查"服务器今天 ±1 天"。
# 以前 date 参数完全不校验:循环换日期就能让服务端现场产题并永久入库
# (HARD 单题最慢十几秒 → 可 DoS + 撑爆数据库),非法日期串还会让
# fromisoformat 抛 ValueError 变成 500。
DAILY_DATE_WINDOW_DAYS = 1


def _meta(row):
    return {
        "id": row["puzzle_id"],
        "name": row["name"],
        "difficulty": row["difficulty"],
        "rows": row["rows"],
        "cols": row["cols"],
        "timedLimitSeconds": None,
        "solves": row["solves"] if "solves" in row.keys() else None,
    }


def _validate_daily_date(date):
    """校验并归一化 date 参数(缺省 = 服务器(东八区)今日)。"""
    if date is None or date == "":
        return content.today_cn()
    try:
        day = datetime.date.fromisoformat(date)
    except ValueError:
        raise HTTPException(status_code=400, detail={"error": "日期格式应为 YYYY-MM-DD"})
    today = datetime.date.fromisoformat(content.today_cn())
    if abs((day - today).days) > DAILY_DATE_WINDOW_DAYS:
        raise HTTPException(status_code=400,
                            detail={"error": "只能查询最近一天的每日一题"})
    return day.isoformat()


def _ensure_difficulties(difficulty):
    conn = db.connect()
    try:
        wanted = [difficulty] if difficulty else list(BANK_TARGET)
        for d in wanted:
            content.ensure_bank(conn, d)
    finally:
        conn.close()


@router.get("/bank")
def bank(difficulty: str | None = None,
         offset: int = Query(0, ge=0),
         limit: int = Query(20, ge=1, le=100)):
    try:
        difficulty = content.normalize_difficulty(difficulty)  # 非法难度 → 400,而不是 KeyError 500
    except ValueError as e:
        raise HTTPException(status_code=400, detail={"error": str(e)})
    try:
        _ensure_difficulties(difficulty)
    except RuntimeError as e:
        raise HTTPException(status_code=503, detail={"error": str(e)})
    conn = db.connect()
    try:
        rows, total = content.bank_rows(conn, difficulty, offset, limit)
    finally:
        conn.close()
    return {"total": total, "items": [_meta(r) for r in rows]}


@router.get("/daily")
def daily(date: str | None = Query(
        None, description="YYYY-MM-DD,默认服务器今日,只允许 ±1 天")):
    day = _validate_daily_date(date)
    conn = db.connect()
    try:
        try:
            row = content.ensure_daily(conn, day)
        except RuntimeError as e:
            # 产题失败是"服务暂时不可用",不是客户端错误;503 也让客户端
            # 落到"服务器暂时不可用,请稍后重试"的文案上
            raise HTTPException(status_code=503, detail={"error": str(e)})
    finally:
        conn.close()
    return {"date": day, "difficulty": row["difficulty"], "level": content.row_to_level(row)}


@router.get("/{puzzle_id}")
def get_puzzle(puzzle_id: int):
    conn = db.connect()
    try:
        row = content.puzzle_row(conn, puzzle_id)
    finally:
        conn.close()
    if row is None:
        raise HTTPException(status_code=404, detail={"error": "关卡不存在"})
    return content.row_to_level(row)
