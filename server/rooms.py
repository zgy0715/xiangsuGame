#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""网络对战房间 —— 同题竞速(2~4 人),WebSocket 中转,全内存。

流程:
  房主 POST /api/rooms → 4 位房号;全员直接 WS /ws/rooms/{code}?token=…
  房主 choosePuzzle(完整 Level JSON,含答案) → 服务器缓存为校验基准,只广播题名;
  房主 start → 服务器记 serverStartMs → 广播 raceStart(完整题目);
  竞速中 progress(节流)转发展示;finish(grid) → 服务器比对涂黑掩码,
  正确者按接收序排名(时长=服务器计时,不可自报),写 solve_records(source='race');
  全部完成后广播 result(服务器权威)并进入 settled。
  心跳:客户端 25s ping;服务器 90s 无消息判掉线;竞速中主机掉线 → 房间解散。
"""
import asyncio
import json
import secrets
import threading
import time

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect

import db
from auth import current_user

router = APIRouter(tags=["rooms"])

ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"  # 去掉 0O1I 防误读
MAX_PLAYERS = 4
HEARTBEAT_IDLE_SECONDS = 90

_lock = threading.Lock()
_rooms = {}  # code -> room dict


def _now_ms():
    return int(time.time() * 1000)


def _nickname(user_id):
    row = db.query_one("SELECT nickname FROM users WHERE user_id = ?", (user_id,))
    return row["nickname"] if row else f"玩家{user_id}"


def _broadcast(room, message):
    """向房内所有在线连接广播(仅允许在事件循环线程内调用)。"""
    text = json.dumps(message, ensure_ascii=False)
    for p in room["players"].values():
        ws = p.get("ws")
        if ws is not None:
            asyncio.create_task(ws.send_text(text))


def _room_state(room):
    players = [{
        "userId": p["user_id"], "nickname": p["nickname"],
        "connected": p["connected"], "ready": p["ready"],
        "filled": p.get("filled", 0), "finishedRank": p.get("finished_rank", 0),
    } for p in room["players"].values()]
    return {
        "roomId": room["code"],
        "hostId": room["host_id"],
        "phase": room["phase"],
        "players": players,
        "puzzleName": (room.get("puzzle") or {}).get("name"),
    }


def _ok_level(level):
    """校验房主提交的关卡 JSON:rows/cols 与 answerGrid 尺寸匹配且值 0/1。"""
    try:
        rows, cols = int(level["rows"]), int(level["cols"])
        ans = level["answerGrid"]
        if rows <= 0 or cols <= 0 or rows != len(ans):
            return False
        for row in ans:
            if len(row) != cols or any(v not in (0, 1) for v in row):
                return False
    except (KeyError, TypeError, ValueError):
        return False
    return True


@router.post("/api/rooms")
def create_room(user=Depends(current_user)):
    with _lock:
        for _ in range(20):
            code = "".join(secrets.choice(ROOM_CODE_ALPHABET) for _ in range(4))
            if code not in _rooms:
                _rooms[code] = {
                    "code": code,
                    "host_id": user["user_id"],
                    "phase": "lobby",
                    "players": {},       # user_id -> player dict
                    "puzzle": None,
                    "start_ms": None,
                    "finishes": [],      # [{user_id, rank, duration_ms}]
                }
                return {"roomId": code}
    raise HTTPException(status_code=500, detail={"error": "创建房间失败,请重试"})


def _settle_if_done(room):
    """竞速中:所有在线玩家都正确完成后结算并广播 result。"""
    racing = [p for p in room["players"].values() if p["connected"]]
    if room["phase"] == "racing" and racing and len(room["finishes"]) >= len(racing):
        room["phase"] = "settled"
        entries = [{"userId": f["user_id"], "rank": f["rank"], "durationMs": f["duration_ms"]}
                   for f in sorted(room["finishes"], key=lambda f: f["rank"])]
        _broadcast(room, {"type": "result", "payload": {"entries": entries}})


@router.websocket("/ws/rooms/{code}")
async def room_socket(ws: WebSocket, code: str, token: str = ""):
    user = db.user_by_token(token)
    if user is None:
        await ws.close(code=4401, reason="未登录")
        return
    with _lock:
        room = _rooms.get(code.upper())
        if room is None:
            await ws.close(code=4404, reason="房间不存在")
            return
        if room["phase"] == "settled":
            await ws.close(code=4409, reason="本局已结束")
            return
        if len(room["players"]) >= MAX_PLAYERS:
            await ws.close(code=4413, reason="房间已满")
            return
        player = {
            "user_id": user["user_id"], "nickname": user["nickname"],
            "ws": ws, "connected": True, "ready": False,
            "filled": 0, "last_progress": 0.0, "finished_rank": 0,
        }
        room["players"][user["user_id"]] = player
        player_id = user["user_id"]
        is_host = (room["host_id"] == player_id)

    await ws.accept()
    await ws.send_text(json.dumps({"type": "roomState", "payload": _room_state(room)}, ensure_ascii=False))

    try:
        while True:
            try:
                raw = await asyncio.wait_for(ws.receive_text(), timeout=HEARTBEAT_IDLE_SECONDS)
            except asyncio.TimeoutError:
                break  # 心跳超时视为掉线
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue
            mtype = msg.get("type")
            payload = msg.get("payload") or {}
            self_error = None
            with _lock:
                room = _rooms.get(code.upper())
                if not room or player_id not in room["players"]:
                    break
                if mtype == "ping":
                    continue
                if mtype == "choosePuzzle" and is_host:
                    level = payload.get("level")
                    if not _ok_level(level):
                        self_error = {"type": "error", "payload": {"message": "关卡数据不合法"}}
                    else:
                        room["puzzle"] = level
                        _broadcast(room, {"type": "roomState", "payload": _room_state(room)})
                elif mtype == "start" and is_host and room["phase"] == "lobby":
                    connected = [p for p in room["players"].values() if p["connected"]]
                    if len(connected) < 2 or not room.get("puzzle"):
                        self_error = {"type": "error",
                                      "payload": {"message": "至少 2 人且房主已选题后才能开始"}}
                    else:
                        room["phase"] = "racing"
                        room["start_ms"] = _now_ms()
                        _broadcast(room, {"type": "raceStart", "payload": {
                            "serverStartMs": room["start_ms"], "level": room["puzzle"]}})
                elif mtype == "progress":
                    now = time.time()
                    if now - player["last_progress"] < 0.5:
                        continue
                    player["last_progress"] = now
                    player["filled"] = int(payload.get("filled", 0) or 0)
                    _broadcast(room, {"type": "progress", "payload": {
                        "userId": player_id, "filled": player["filled"],
                        "elapsedMs": int(payload.get("elapsedMs", 0) or 0)}})
                elif mtype == "finish" and room["phase"] == "racing":
                    if player.get("finished_rank", 0):
                        continue  # 重复提交忽略
                    grid = payload.get("grid")
                    answer = (room.get("puzzle") or {}).get("answerGrid")
                    ok = False
                    if isinstance(grid, list) and answer:
                        rows, cols = len(answer), len(answer[0])
                        ok = len(grid) == rows and all(
                            isinstance(row, list) and len(row) == cols
                            and all(v in (0, 1, 2) for v in row)
                            for row in grid)
                        if ok:
                            ok = all((grid[r][c] == 1) == (answer[r][c] == 1)
                                     for r in range(rows) for c in range(cols))
                    if not ok:
                        self_error = {"type": "error",
                                      "payload": {"message": "棋盘与答案不一致,未判定通关"}}
                    else:
                        rank = len(room["finishes"]) + 1
                        duration = max(0, _now_ms() - (room["start_ms"] or _now_ms()))
                        room["finishes"].append(
                            {"user_id": player_id, "rank": rank, "duration_ms": duration})
                        player["finished_rank"] = rank
                        _broadcast(room, {"type": "finished", "payload": {
                            "userId": player_id, "rank": rank, "durationMs": duration}})
                        try:  # 服务器权威计时写榜
                            with db.connect() as conn:
                                conn.execute(
                                    "INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) "
                                    "VALUES(?, ?, ?, 'race')",
                                    (room["puzzle"]["id"], player_id, duration))
                        except Exception:
                            pass
                        _settle_if_done(room)
            if self_error:
                await ws.send_text(json.dumps(self_error, ensure_ascii=False))
    except WebSocketDisconnect:
        pass
    finally:
        with _lock:
            room = _rooms.get(code.upper())
            if not room or player_id not in room["players"]:
                return
            room["players"][player_id]["connected"] = False
            room["players"][player_id]["ws"] = None
            if player_id == room["host_id"]:
                _rooms.pop(room["code"], None)
                _broadcast(room, {"type": "error", "payload": {"message": "房主已离开,房间解散"}})
                return
            _broadcast(room, {"type": "playerLeft", "payload": {"userId": player_id}})
            _broadcast(room, {"type": "roomState", "payload": _room_state(room)})
            if room["phase"] == "racing":
                _settle_if_done(room)
            if all(not p["connected"] for p in room["players"].values()):
                _rooms.pop(room["code"], None)
