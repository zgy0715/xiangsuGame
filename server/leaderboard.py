#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排行榜:每题每人取最优成绩排序;单人成绩客户端自报,对战成绩服务端计时覆盖。

compute_board / compute_my_best 为纯函数(只依赖 conn),便于冒烟测试直连。
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

import db
from auth import current_user, current_user_optional

router = APIRouter(prefix="/api/leaderboard", tags=["leaderboard"])

ALLOWED_SOURCES = {"single", "race", ""}


class SubmitRequest(BaseModel):
    puzzleId: int
    durationMs: int
    source: str = "single"


def compute_board(conn, puzzle_id, source="", limit=10):
    sql = ("SELECT u.user_id, u.nickname, MIN(s.duration_ms) AS best "
           "FROM solve_records s JOIN users u ON u.user_id = s.user_id "
           "WHERE s.puzzle_id = ?")
    params = [puzzle_id]
    if source:
        sql += " AND s.source = ?"
        params.append(source)
    sql += " GROUP BY u.user_id ORDER BY best ASC, u.user_id ASC LIMIT ?"
    params.append(min(limit, 100))
    entries = [{"rank": i + 1,
                "userId": r["user_id"],
                "nickname": r["nickname"],
                "durationMs": r["best"]}
               for i, r in enumerate(conn.execute(sql, params).fetchall())]
    return entries


def compute_my_best(conn, puzzle_id, user_id, source=""):
    sql = ("SELECT MIN(s.duration_ms) AS best FROM solve_records s "
           "WHERE s.puzzle_id = ? AND s.user_id = ?")
    params = [puzzle_id, user_id]
    if source:
        sql += " AND s.source = ?"
        params.append(source)
    row = conn.execute(sql, params).fetchone()
    if not row or row["best"] is None:
        return None
    fewer = conn.execute(
        "SELECT COUNT(*) AS n FROM (SELECT s2.user_id, MIN(s2.duration_ms) AS b FROM solve_records s2 "
        "WHERE s2.puzzle_id = ? GROUP BY s2.user_id HAVING b < ?) t",
        [puzzle_id, row["best"]]).fetchone()["n"]
    return {"rank": fewer + 1, "durationMs": row["best"]}


@router.post("/submit")
def submit(req: SubmitRequest, user=Depends(current_user)):
    if req.source not in ALLOWED_SOURCES or req.source == "":
        raise HTTPException(status_code=400, detail={"error": "非法成绩类型"})
    if req.durationMs <= 0 or req.durationMs > 24 * 3600 * 1000:
        raise HTTPException(status_code=400, detail={"error": "非法时长"})
    conn = db.connect()
    try:
        if not content_puzzle_exists(conn, req.puzzleId):
            raise HTTPException(status_code=404, detail={"error": "关卡不存在"})
        cur = conn.execute(
            "INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) VALUES(?, ?, ?, ?)",
            (req.puzzleId, user["user_id"], req.durationMs, req.source))
        conn.commit()
    finally:
        conn.close()
    return {"recordId": cur.lastrowid}


def content_puzzle_exists(conn, puzzle_id):
    return conn.execute("SELECT 1 FROM puzzles WHERE puzzle_id = ?", (puzzle_id,)).fetchone() is not None


@router.get("")
def leaderboard(puzzleId: int,
                source: str = Query("", description="single | race | 空=全部"),
                limit: int = Query(10, ge=1, le=100),
                user=Depends(current_user_optional)):
    conn = db.connect()
    try:
        entries = compute_board(conn, puzzleId, source, limit)
        me = user
        my_best = compute_my_best(conn, puzzleId, me["user_id"], source) if me else None
    finally:
        conn.close()
    return {"puzzleId": puzzleId, "entries": entries, "myBest": my_best}
