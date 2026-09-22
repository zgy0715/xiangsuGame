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
  **发车时在场的全部席位**都完成(或掉线超过宽限)后广播 result 并进入 settled;
  settled 后房主可发 rematch 重置为 lobby 再来一局(重赛)。
  心跳:客户端 25s ping;服务器 90s 无消息判掉线。
  断线重进:掉线只标记 connected=False,席位与已涂进度保留(竞速中未完成的
  重进会重置其进度并重发 raceStart;已结算的房间重进会补发 result);
  房主掉线后保留 ROOM_HOST_GRACE_SECONDS 宽限,期间重连即恢复,超时房间解散。

并发安全:房间状态用全局 threading.Lock 保护,临界区内**禁止 await**
(threading.Lock 在事件循环线程上阻塞会导致整个服务端挂死)。
"""
import asyncio
import contextlib
import json
import logging
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
# 竞速中未完成者掉线后,等他重连的宽限秒数;超时按现有成绩强制结算(不再无限等)。
RACER_RECONNECT_GRACE_SECONDS = 60
# 连接建立后等待首帧鉴权(auth)的秒数;超时按未登录关闭。
AUTH_FRAME_TIMEOUT_SECONDS = 10

_lock = threading.Lock()
_rooms = {}  # code -> room dict
_bg_tasks = set()  # 广播任务的强引用(事件循环只弱引用 create_task 的结果)


def _now_ms():
    return int(time.time() * 1000)


def _as_int(value, default=0):
    """宽松取整:客户端可能发字符串/None,取不到就用默认值,不让畸形帧打崩连接。"""
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


async def _auth_from_first_frame(ws):
    """等连接后的第一帧 auth 帧取 token;超时/格式不对一律返回 None(未登录)。

    新客户端(安卓 v1.1+ / 鸿蒙版)走这条路:token 不进 URL,避免被访问日志记录。
    """
    try:
        raw = await asyncio.wait_for(ws.receive_text(), timeout=AUTH_FRAME_TIMEOUT_SECONDS)
    except Exception:
        return None  # 超时/连接已断/收到二进制帧:一律按未登录处理
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(msg, dict) or msg.get("type") != "auth":
        return None
    payload = msg.get("payload")
    candidate = payload.get("token") if isinstance(payload, dict) else None
    if not isinstance(candidate, str) or not candidate:
        return None
    return await asyncio.to_thread(db.user_by_token, candidate)


def _nickname(user_id):
    row = db.query_one("SELECT nickname FROM users WHERE user_id = ?", (user_id,))
    return row["nickname"] if row else f"玩家{user_id}"


async def _safe_send(ws, text):
    """发送失败只当连接已死,异常不往外抛(否则事件循环会打印未回收异常)。"""
    try:
        await ws.send_text(text)
    except Exception:
        pass


def _broadcast(room, message):
    """向房内所有在线连接广播(仅允许在事件循环线程内调用)。"""
    text = json.dumps(message, ensure_ascii=False)
    for p in room["players"].values():
        ws = p.get("ws")
        if ws is None:
            continue
        task = asyncio.create_task(_safe_send(ws, text))
        # 必须持强引用:否则任务可能在执行完之前被 GC("Task was destroyed but it is pending")
        _bg_tasks.add(task)
        task.add_done_callback(_bg_tasks.discard)


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


def _do_settle(room):
    """置为 settled 并广播服务器权威结算(含昵称,否则客户端只能显示"玩家3")。"""
    if room["phase"] == "settled":
        return
    room["phase"] = "settled"
    entries = [{
        "userId": f["user_id"],
        "nickname": f.get("nickname") or _nickname(f["user_id"]),
        "rank": f["rank"],
        "durationMs": f["duration_ms"],
    } for f in sorted(room["finishes"], key=lambda f: f["rank"])]
    room["result"] = {"entries": entries}
    _broadcast(room, {"type": "result", "payload": room["result"]})


def _force_settle(code):
    """未完成者掉线超过宽限期:按现有成绩强制结算,不让全房一直等下去。"""
    with _lock:
        room = _rooms.get(code)
        if room and room["phase"] == "racing":
            _do_settle(room)


def _settle_if_done(room):
    """竞速结算判定。

    结算基数是**发车时在场的席位**(room["racing_seats"]),而不是"当前在线的人":
    否则只要有一个未完成的人掉线,就会立刻按剩下的在线玩家结算,他重连回来后
    既拿不到题目也拿不到名次。未完成且掉线的席位给 RACER_RECONNECT_GRACE_SECONDS
    宽限等待重连,超时由 _force_settle 按现有成绩收尾。
    """
    if room["phase"] != "racing":
        return
    seats = room.get("racing_seats") or list(room["players"].keys())
    pending = [p for uid, p in room["players"].items()
               if uid in seats and not p.get("finished_rank")]
    if not pending:
        room.pop("settle_deadline", None)
        _do_settle(room)
        return
    if all(not p["connected"] for p in pending):
        if room.get("settle_deadline") is None:
            room["settle_deadline"] = _now_ms() + RACER_RECONNECT_GRACE_SECONDS * 1000
            asyncio.get_running_loop().call_later(
                RACER_RECONNECT_GRACE_SECONDS, _force_settle, room["code"])
    else:
        room.pop("settle_deadline", None)


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
    # 先 accept 再 close:握手未完成时的 4401/4404/4413 只会变成 HTTP 403,
    # 客户端 onFailure 只拿到一句通用文本,用户看不到"房间不存在/已满"这类可操作原因。
    await ws.accept()

    # —— 鉴权 ——
    # 优先用查询参数里的 token(兼容旧版本客户端);没有则等**第一帧 auth 帧**。
    # 新客户端不再把 token 放 URL:query 会被 uvicorn/反代的访问日志完整打印,
    # 等于把 30 天有效的令牌写进日志。查库是阻塞操作,统一丢线程池。
    user = await asyncio.to_thread(db.user_by_token, token) if token else None
    if user is None:
        user = await _auth_from_first_frame(ws)
    if user is None:
        await ws.close(code=4401, reason="未登录")
        return

    stale_ws = None
    reject = None
    # ⚠️ 这个临界区里绝对不能出现 await:threading.Lock 不感知协程,一旦在事件循环
    #    线程上阻塞,先前在锁内 await 过的协程就永远拿不回锁 → 整个服务端挂死。
    with _lock:
        room = _rooms.get(code.upper())
        if room is None:
            reject = (4404, "房间不存在")
        else:
            member = room["players"].get(user["user_id"])
            if member is None:
                # 新成员:只有大厅阶段能加入 —— 竞速中途进来的人拿不到 raceStart,
                # 永远无法完成,会把"全员完成才结算"拖成死局。
                if room["phase"] != "lobby":
                    reject = (4409, "本局已开始,请等下一局")
                elif len(room["players"]) >= MAX_PLAYERS:
                    # 席位按"全部席位"计:离线保留席同样占位,否则 4 人房掉 3 人后
                    # 还能再进 3 个新人,在线人数突破 MAX_PLAYERS。
                    reject = (4413, "房间已满")
                else:
                    member = {
                        "user_id": user["user_id"], "nickname": user["nickname"],
                        "ws": ws, "connected": True, "ready": False,
                        "filled": 0, "last_progress": 0.0, "finished_rank": 0,
                    }
                    room["players"][user["user_id"]] = member
            else:
                # 断线重进:恢复原席位,保留 ready/filled/finished_rank;旧连接显式踢掉
                old = member.get("ws")
                if old is not None and old is not ws:
                    stale_ws = old
                member["ws"] = ws
                member["connected"] = True
            if reject is None:
                player_id = user["user_id"]
                is_host = (room["host_id"] == player_id)
                room.pop("host_away_since", None)
                # 竞速中重进:补发题目;未完成者本地棋盘已丢,进度归零重画,
                # 完成者保留进度只看结算
                rejoin_racing = room["phase"] == "racing" and not member.get("finished_rank")
                if rejoin_racing:
                    member["filled"] = 0
                # 结算后才重进的人要能看到成绩,否则客户端停在加载圈
                settled_payload = room.get("result") if room["phase"] == "settled" else None

    if reject is not None:
        await ws.close(code=reject[0], reason=reject[1])
        return
    if stale_ws is not None:
        # 旧连接不关掉的话,它的 finally 会在新连接注册之后把玩家标记成离线,
        # 广播静默丢失、房主还会被误判掉线而触发 60 秒解散。
        with contextlib.suppress(Exception):
            await stale_ws.close(code=4001, reason="账号已在别处重连")

    await ws.send_text(json.dumps({"type": "roomState", "payload": _room_state(room)}, ensure_ascii=False))
    if rejoin_racing:
        # 只向重进者重发题目与服务器起跑时刻;随后全员 roomState 对齐进度
        await ws.send_text(json.dumps({"type": "raceStart", "payload": {
            "serverStartMs": room["start_ms"] or 0, "level": room["puzzle"] or {}}}, ensure_ascii=False))
    if settled_payload is not None:
        await ws.send_text(json.dumps({"type": "result", "payload": settled_payload},
                                      ensure_ascii=False))
    _broadcast(room, {"type": "roomState", "payload": _room_state(room)})

    try:
        while True:
            try:
                raw = await asyncio.wait_for(ws.receive_text(), timeout=HEARTBEAT_IDLE_SECONDS)
            except asyncio.TimeoutError:
                break  # 心跳超时视为掉线
            self_error = None
            try:
                msg = json.loads(raw)
                if not isinstance(msg, dict):
                    raise ValueError("帧不是 JSON 对象")
                mtype = msg.get("type")
                payload = msg.get("payload")
                if payload is None:
                    payload = {}
                if not isinstance(payload, dict):
                    raise ValueError("payload 不是 JSON 对象")
            except (json.JSONDecodeError, ValueError):
                # 畸形帧只回一条 error 并继续:以前这里异常穿透到 finally,
                # 一帧 {"type":"progress","payload":[]} 就能把玩家判成掉线。
                with contextlib.suppress(Exception):
                    await ws.send_text(json.dumps(
                        {"type": "error", "payload": {"message": "消息格式不正确"}},
                        ensure_ascii=False))
                continue

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
                player = room["players"][player_id]  # 重连后 ws/座位可能已更新,每帧重新取
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
                        room["result"] = None
                        room.pop("settle_deadline", None)
                        # 结算基数:发车时在场的席位(离线保留席也算),见 _settle_if_done
                        room["racing_seats"] = [p["user_id"] for p in room["players"].values()
                                                if p["connected"]]
                        for p in room["players"].values():
                            p["finished_rank"] = 0
                            p["filled"] = 0
                        _broadcast(room, {"type": "raceStart", "payload": {
                            "serverStartMs": room["start_ms"], "level": room["puzzle"]}})
                elif mtype == "rematch" and is_host and room["phase"] == "settled":
                    # 重赛:回到 lobby,清空上局结算与进度,房主可重新选题发车。
                    # 只在 settled 允许 —— 否则房主能在竞速中随时清空全场进度。
                    room["phase"] = "lobby"
                    room["puzzle"] = None
                    room["start_ms"] = None
                    room["finishes"] = []
                    room["result"] = None
                    room.pop("racing_seats", None)
                    room.pop("settle_deadline", None)
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
                    player["filled"] = max(0, _as_int(payload.get("filled", 0)))
                    _broadcast(room, {"type": "progress", "payload": {
                        "userId": player_id, "filled": player["filled"],
                        "elapsedMs": max(0, _as_int(payload.get("elapsedMs", 0)))}})
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
                            {"user_id": player_id, "nickname": player["nickname"],
                             "rank": rank, "duration_ms": duration})
                        player["finished_rank"] = rank
                        _broadcast(room, {"type": "finished", "payload": {
                            "userId": player_id, "rank": rank, "durationMs": duration}})
                        puzzle_id = (room.get("puzzle") or {}).get("id")
                        if puzzle_id is not None:
                            try:  # 服务器权威计时写榜
                                conn = db.connect()
                                try:
                                    conn.execute(
                                        "INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) "
                                        "VALUES(?, ?, ?, 'race')",
                                        (puzzle_id, player_id, duration))
                                    conn.commit()
                                finally:
                                    conn.close()  # with conn 只提交事务,不会关闭连接
                            except Exception as exc:
                                # 写榜失败不影响本局,但必须留痕:以前是 except: pass,
                                # 成绩会静默消失,排行榜缺数据却查不出原因
                                logging.warning("写入对战成绩失败 puzzle=%s user=%s: %s",
                                                puzzle_id, player_id, exc)
                        _settle_if_done(room)
            if self_error:
                await ws.send_text(json.dumps(self_error, ensure_ascii=False))
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # 未预期的帧处理异常不该把连接打崩(会连带判玩家掉线)
        logging.warning("房间 %s 连接异常: %s", code, exc)
    finally:
        with _lock:
            room = _rooms.get(code.upper())
            if not room or player_id not in room["players"]:
                return
            current = room["players"][player_id]
            if current.get("ws") is not ws:
                # 这条连接已被"重连后的新连接"顶替:不能当成玩家掉线,
                # 否则重连成功的人会被标记离线、广播静默丢失、房主还会被误判离开。
                return
            current["connected"] = False
            current["ws"] = None
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
