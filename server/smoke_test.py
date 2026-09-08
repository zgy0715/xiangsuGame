#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端冒烟测试(纯 assert,无 pytest 依赖):
  cd server && python smoke_test.py
覆盖:生成器唯一解 → 每日一题确定性 → 题库保底 → 登录/令牌 → 排行榜计算闭环
  → 昵称规范化与持久化 → 房间流程(发车/完成/结算/重赛/断线重进/房主宽限回收/NTP 校时)。
使用临时数据库,不污染正式库。generator 对 HARD 较慢,默认只测 EASY/MEDIUM。
"""
import asyncio
import json
import os
import sys
import tempfile

tmp_db = os.path.join(tempfile.mkdtemp(prefix="xiangsu_smoke_"), "smoke.db")
os.environ["XIANGSU_DB"] = tmp_db

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import db  # noqa: E402
import rooms  # noqa: E402
import content  # noqa: E402
import generator  # noqa: E402
from auth import _mock_openid, normalize_nickname, change_nickname, NicknameRequest  # noqa: E402
from leaderboard import compute_board, compute_my_best  # noqa: E402


class FakeWs:
    """最小假 WebSocket:入站用队列驱动,出站记录文本帧。"""

    def __init__(self):
        self.sent = []
        self.incoming = asyncio.Queue()
        self.accepted = False
        self.closed = None

    async def accept(self):
        self.accepted = True

    async def send_text(self, text):
        self.sent.append(text)

    async def receive_text(self):
        return await self.incoming.get()

    async def close(self, code=1000, reason=""):
        self.closed = (code, reason)

    # ---- 测试辅助 ----
    def frames(self, mtype):
        return [json.loads(s) for s in self.sent if json.loads(s).get("type") == mtype]

    def send(self, mtype, payload=None):
        self.incoming.put_nowait(json.dumps({"type": mtype, "payload": payload or {}}))


def _rooms_flow(uid1, tok1, uid2):
    """房间全流程:建房 → 发车 → 双人完成结算 → 房主重赛 → 房客断线重进(竞速中) →
    房主掉线宽限回收;timeSync 回包验证 NTP 校时。"""
    rooms._rooms.clear()
    rooms.ROOM_HOST_GRACE_SECONDS = 0.15  # 测试用短宽限
    level = generator.generate_level("race-flow", "EASY", name="对战测试")
    answer = level["answerGrid"]

    async def main_flow():
        # 建房(直接注入房间,等价 POST /api/rooms)
        rooms._rooms["RL01"] = {
            "code": "RL01", "host_id": uid1, "phase": "lobby",
            "players": {}, "puzzle": None, "start_ms": None, "finishes": [],
        }
        host, guest = FakeWs(), FakeWs()
        task_h = asyncio.create_task(rooms.room_socket(host, "RL01", tok1))
        await asyncio.sleep(0.05)
        assert host.accepted, "房主应连上房间"
        task_g = asyncio.create_task(rooms.room_socket(guest, "RL01", db.create_token(uid2, 30)))
        await asyncio.sleep(0.05)
        assert guest.accepted, "房客应连上房间"

        # 选题 + 发车
        host.send("choosePuzzle", {"level": level})
        await asyncio.sleep(0.05)
        host.send("start")
        await asyncio.sleep(0.05)
        assert guest.frames("raceStart"), "房客应收到 raceStart(含完整题目)"
        assert guest.frames("raceStart")[0]["payload"]["serverStartMs"] > 0

        # NTP 校时:房客发 timeSync,应收到服务器时钟回包
        guest.send("timeSync")
        await asyncio.sleep(0.03)
        sync = guest.frames("timeSync")
        assert sync and sync[-1]["payload"].get("serverTimeMs", 0) > 0, "timeSync 应回服务器时钟"

        # 进度转发
        guest.send("progress", {"filled": 3, "elapsedMs": 1000})
        await asyncio.sleep(0.03)
        prog = [f for f in host.frames("progress") if f["payload"]["userId"] == uid2]
        assert prog and prog[-1]["payload"]["filled"] == 3, "进度应转发给房主"

        # 双人完成 → 结算(先提交的房客应为第 1 名,服务器按接收序判定)
        guest.send("finish", {"grid": answer, "elapsedMs": 5000})
        await asyncio.sleep(0.03)
        host.send("finish", {"grid": answer, "elapsedMs": 4000})
        await asyncio.sleep(0.05)
        result = guest.frames("result")
        assert result, "全员完成后应广播 result"
        ranks = {e["userId"]: e["rank"] for e in result[0]["payload"]["entries"]}
        assert ranks == {uid2: 1, uid1: 2}, f"服务器按接收序判定名次: {ranks}"

        # 房主重赛 → lobby
        host.send("rematch")
        await asyncio.sleep(0.05)
        lobby = guest.frames("roomState")[-1]
        assert lobby["payload"]["phase"] == "lobby", "重赛应回到 lobby"

        # 重新选题发车,测竞速中断线重进
        host.send("choosePuzzle", {"level": level})
        await asyncio.sleep(0.03)
        host.send("start")
        await asyncio.sleep(0.05)
        assert guest.frames("raceStart"), "重赛后再发车应再次 raceStart"

        # 房客掉线 → 重进(新会话):重收题目,未完成进度归零
        task_g.cancel()
        try:
            await task_g
        except asyncio.CancelledError:
            pass
        await asyncio.sleep(0.05)
        guest2 = FakeWs()
        task_g2 = asyncio.create_task(rooms.room_socket(guest2, "RL01", db.create_token(uid2, 30)))
        await asyncio.sleep(0.05)
        assert guest2.accepted, "掉线房客应能重进同一房间"
        rs2 = guest2.frames("roomState")[-1]["payload"]
        assert [p for p in rs2["players"] if p["userId"] == uid2], "重进后席位应保留"
        assert guest2.frames("raceStart"), "竞速中重进应补发题目"
        room = rooms._rooms["RL01"]
        assert room["players"][uid2]["filled"] == 0, "未完成重进进度应归零"

        # 房主掉线 → 宽限期回收房间(仍在场的房客收到解散提示)
        task_h.cancel()
        try:
            await task_h
        except asyncio.CancelledError:
            pass
        await asyncio.sleep(0.3)
        assert "RL01" not in rooms._rooms, "房主掉线超过宽限应解散房间"
        timeouts = [f for f in guest2.frames("error") if "解散" in f["payload"]["message"]]
        assert timeouts, "在场房客应收到房间解散提示"

    asyncio.run(main_flow())
    rooms.ROOM_HOST_GRACE_SECONDS = 60  # 还原默认
    rooms._rooms.clear()


def main():
    db.init_db()

    # 1) 生成器:难度与尺寸合法 + 唯一解等于答案
    for diff in ("EASY", "MEDIUM"):
        level = generator.generate_level(f"smoke-{diff}", diff, name=f"测试-{diff}")
        rows = level["rows"]
        assert len(level["answerGrid"]) == rows and len(level["clueGrid"]) == rows
        assert all(len(r) == rows for r in level["answerGrid"])
        assert generator.verify_unique(level), f"{diff} 生成关卡未通过唯一解校验"
        print(f"[ok] generate {diff}: {rows}x{rows}")

    # 2) 每日一题:同日期两次请求同一道题;难度按星期轮换
    conn = db.connect()
    day = "2026-09-07"  # 星期一(weekday 0) → EASY
    assert content.difficulty_for_date(day) == "EASY"
    assert content.difficulty_for_date("2026-09-08") == "MEDIUM"  # 星期二
    row1 = content.ensure_daily(conn, day)
    row2 = content.ensure_daily(conn, day)
    assert row1["puzzle_id"] == row2["puzzle_id"], "同日期每日一题必须一致"
    assert row1["puzzle_id"] < 0, "网络关卡 id 必须为负"
    level = content.row_to_level(row1)
    assert generator.verify_unique(level), "每日一题未通过唯一解校验"
    print(f"[ok] daily {day} → id={row1['puzzle_id']} {row1['difficulty']}")

    # 3) 题库保底
    content.ensure_bank(conn, "EASY", target=3)
    assert content.bank_count(conn, "EASY") >= 3
    rows, total = content.bank_rows(conn, "EASY", 0, 10)
    assert total >= 3 and len(rows) >= 3
    # bank 列表元信息不含超大答案,取整题接口再验唯一性
    full = content.row_to_level(content.puzzle_row(conn, rows[0]["puzzle_id"]))
    assert generator.verify_unique(full)
    print(f"[ok] bank EASY: {total} 题,抽检唯一性通过")

    # 4) 登录/令牌
    uid1, new1 = db.get_or_create_user(_mock_openid("device-a"), "小A")
    uid2, _ = db.get_or_create_user(_mock_openid("device-b"), "小B")
    assert new1
    tok1 = db.create_token(uid1, 30)
    assert db.user_by_token(tok1)["user_id"] == uid1
    assert db.user_by_token("bogus") is None
    print(f"[ok] auth: user{uid1} / user{uid2}")

    # 5) 排行榜闭环:先提交慢成绩,再提交快成绩,取最优且名次正确
    pid = row1["puzzle_id"]
    for uid, dur in ((uid1, 90_000), (uid1, 70_000), (uid2, 80_000)):
        conn.execute("INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) "
                     "VALUES(?, ?, ?, 'single')", (pid, uid, dur))
    conn.commit()
    entries = compute_board(conn, pid, "", 10)
    assert [e["userId"] for e in entries] == [uid1, uid2]
    assert entries[0]["durationMs"] == 70_000 and entries[0]["rank"] == 1
    assert entries[1]["durationMs"] == 80_000
    mine = compute_my_best(conn, pid, uid1, "")
    assert mine == {"rank": 1, "durationMs": 70_000}
    assert compute_my_best(conn, pid, 99999, "") is None
    print(f"[ok] leaderboard: {entries}")

    # 6) 昵称规范化与持久化(端点逻辑直调:Depends 用假 user 代替)
    assert normalize_nickname("  小明  ") == "小明"
    assert len(normalize_nickname("你好啊" * 8)) == 20  # 超长昵称截断到 20
    try:
        normalize_nickname("   ")
        raise AssertionError("空昵称应抛 HTTPException(400)")
    except Exception as e:
        assert getattr(e, "status_code", None) == 400
    resp = change_nickname(NicknameRequest(nickname=" 剑客一号 "), {"user_id": uid1})
    assert resp == {"nickname": "剑客一号"}
    row = db.query_one("SELECT nickname FROM users WHERE user_id = ?", (uid1,))
    assert row["nickname"] == "剑客一号", "昵称修改必须落库"
    print(f"[ok] nickname: {resp}")

    # 7) 房间流程(全内存,用假 WebSocket 直驱处理器;_rooms_flow 内部自管事件循环)
    _rooms_flow(uid1, tok1, uid2)
    print("[ok] room flow: 发车/完成/结算/重赛/断线重进/房主宽限回收/NTP 校时")

    conn.close()
    for suffix in ("", "-wal", "-shm"):
        try:
            os.remove(tmp_db + suffix)
        except OSError:
            pass  # Windows WAL 文件偶发占用,忽略
    print("\n全部冒烟测试通过 ✔")


if __name__ == "__main__":
    main()
