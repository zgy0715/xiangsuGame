#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""谜题路由:题库列表 / 每日一题 / 按 id 取整题(含答案,客户端本地校验用)。"""
from fastapi import APIRouter, HTTPException, Query

import content
import db
from config import BANK_TARGET

router = APIRouter(prefix="/api/puzzles", tags=["puzzles"])


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
    _ensure_difficulties(difficulty)
    conn = db.connect()
    try:
        rows, total = content.bank_rows(conn, difficulty, offset, limit)
    finally:
        conn.close()
    return {"total": total, "items": [_meta(r) for r in rows]}


@router.get("/daily")
def daily(date: str | None = Query(None, description="YYYY-MM-DD,默认服务器今日")):
    day = date or content.today_cn()
    conn = db.connect()
    try:
        row = content.ensure_daily(conn, day)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail={"error": str(e)})
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
