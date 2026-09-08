#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""网络对战房间 —— 同题竞速(2~4 人),WebSocket 中转,全内存。

流程:
  房主 POST /api/rooms → 4 位房号;全员直接 WS /ws/rooms/{code}?token=…
  房主 choosePuzzle(完整 Level JSON,含答案) → 服务器缓存为校验基准,只广播题名;
  房主 start → 服务器记 serverStartMs → 广播 raceStart(完整题目);开局即发送
  timeSync 回包(服务器时钟,供客户端 NTP 校时显示);
  竞速中 progress(节流)转发展示;finish(grid) → 服务器比对涂黑掩码,
  正确者按接收序排名(**时长 = 服务器计时,不可自报**),写 solve_records(source='race');
  全部完成后广播 result(服务器权威)并进入 settled;
  settled 后房主可发 rematch 重置为 lobby 再来一局(重赛)。
  心跳:客户端 25s ping;服务器 90s 无消息判掉线。
  断线重进:掉线只标记 connected=False,席位与已涂进度保留(竞速中未完成的
  重进会重置其进度并重发 raceStart);房主掉线后保留 ROOM_HOST_GRACE_SECONDS
  宽限,期间重连即恢复,超时房间解散。
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
# 房主掉线后房间保留的宽限秒数:宽限内房主重连即恢复对局(断线重进);超时解散。
# 模块级常量便于冒烟测试临时调短。
ROOM_HOST_GRACE_SECONDS = 60

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


def _purge_room_if_host_away(code):
    """房主掉线宽限期到期回调:房主仍未重连则解散房间,通知在场玩家。"""
    with _lock:
        room = _rooms.get(code)
        if not room or room["phase"] == "settled":
            return
        host = room["players"].get(room["host_id"])
        if host is None or not host.get("connected"):
            _rooms.pop(code, None)
            _broadcast(room, {"type": "error", "payload": {"message": "房主已离线,房间解散"}})


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
        member = room["players"].get(user["user_id"])
        if member is None:
            # 新成员:已结束的房间拒绝加入;席位按“在线人数”限量(离线席位留给原主重进)
            if room["phase"] == "settled":
                await ws.close(code=4409, reason="本局已结束")
                return
            connected = [p for p in room["players"].values() if p["connected"]]
            if len(connected) >= MAX_PLAYERS:
                await ws.close(code=4413, reason="房间已满")
                return
            player = {
                "user_id": user["user_id"], "nickname": user["nickname"],
                "ws": ws, "connected": True, "ready": False,
                "filled": 0, "last_progress": 0.0, "finished_rank": 0,
            }
            room["players"][user["user_id"]] = player
        else:
            # 断线重进:恢复原席位,保留 ready/filled/finished_rank
            player = member
            player["ws"] = ws
            player["connected"] = True
        player_id = user["user_id"]
        is_host = (room["host_id"] == player_id)
        room.pop("host_away_since", None)
        # 竞速中重进:一律补发题目(新会话需要完整 Level);未完成者本地棋盘已丢,
        # 进度归零重赛,完成者保留进度只看结算
        rejoin_racing = room["phase"] == "racing" and member is not None
        if rejoin_racing and not player.get("finished_rank"):
            player["filled"] = 0

    await ws.accept()
    await ws.send_text(json.dumps({"type": "roomState", "payload": _room_state(room)}, ensure_ascii=False))
    if rejoin_racing:
        # 只向重进者重发题目与服务器起跑时刻;随后全员 roomState 对齐进度
        await ws.send_text(json.dumps({"type": "raceStart", "payload": {
            "serverStartMs": room["start_ms"] or 0, "level": room["puzzle"] or {}}}, ensure_ascii=False))
    _broadcast(room, {"type": "roomState", "payload": _room_state(room)})

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

            # timeSync:与房间状态无关,直接回服务器时钟(客户端 NTP 校时,竞速计时基准)
            if mtype == "timeSync":
                await ws.send_text(json.dumps(
                    {"type": "timeSync", "payload": {"serverTimeMs": _now_ms()}},
                    ensure_ascii=False))
                continue

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
                elif mtype == "rematch" and is_host and room["phase"] in ("settled", "racing"):
                    # 重赛:回到 lobby,清空上局结算与进度,房主可重新选题发车
                    room["phase"] = "lobby"
                    room["puzzle"] = None
                    room["start_ms"] = None
                    room["finishes"] = []
                    for p in room["players"].values():
                        p["filled"] = 0
                        p["finished_rank"] = 0
                        p["ready"] = False
                        p["last_progress"] = 0.0
                    _broadcast(room, {"type": "roomState", "payload": _room_state(room)})
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
            # 全员离线 → 房间直接回收
            if all(not p["connected"] for p in room["players"].values()):
                _rooms.pop(room["code"], None)
                return
            _broadcast(room, {"type": "playerLeft", "payload": {"userId": player_id}})
            _broadcast(room, {"type": "roomState", "payload": _room_state(room)})
            # 房主掉线:不立即解散,保留宽限期等待重连;超时由 _purge_room_if_host_away 回收
            if player_id == room["host_id"] and not room.get("host_away_since"):
                room["host_away_since"] = _now_ms()
                loop = asyncio.get_running_loop()
                loop.call_later(ROOM_HOST_GRACE_SECONDS, _purge_room_if_host_away, room["code"])
            if room["phase"] == "racing":
                _settle_if_done(room)
