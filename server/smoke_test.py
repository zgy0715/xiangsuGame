#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端冒烟测试(纯 assert,无 pytest 依赖):
  cd server && python smoke_test.py
覆盖:生成器唯一解 → 每日一题确定性 → 题库保底 → 登录/令牌 → 排行榜计算闭环
  → 昵称规范化与持久化 → 房间流程(发车/完成/结算/重赛/断线重进/房主宽限回收/NTP 校时)
  → 多人对局语义(先完成者入榜、本局不提前结束、全员完成才出排行榜)。
使用临时数据库,不污染正式库。generator 对 HARD 较慢,默认只测 EASY/MEDIUM。
"""
import asyncio
import json
import os
import sys
import tempfile
import types

tmp_db = os.environ.get("XIANGSU_DB") or os.path.join(
    tempfile.mkdtemp(prefix="xiangsu_smoke_"), "smoke.db")
os.environ["XIANGSU_DB"] = tmp_db

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import db  # noqa: E402
import rooms  # noqa: E402
import content  # noqa: E402
import generator  # noqa: E402
import auth  # noqa: E402
import mailer  # noqa: E402
from auth import normalize_email, normalize_nickname, change_nickname, NicknameRequest  # noqa: E402
from leaderboard import compute_board, compute_my_best, compute_my_puzzles  # noqa: E402

# 下发给客户端的 id 必须落在这个区间:安卓端用 Kotlin Int(32 位)承载
INT32_MIN, INT32_MAX = -2147483648, 2147483647


def _fake_request(host="127.0.0.1"):
    """端点函数直调时补一个假 Request(send_code 只用到 request.client.host 做 IP 限流)。"""
    return types.SimpleNamespace(client=types.SimpleNamespace(host=host))


def _send_code(email, host="127.0.0.1"):
    """直接调用 send-code 端点函数(绕过 HTTP 层)。"""
    return asyncio.run(auth.send_code(auth.SendCodeRequest(email=email), _fake_request(host)))


def expect_http_error(call, status, contains=None, label=""):
    """断言调用抛出指定状态码的 HTTPException(冒烟测试不带 pytest)。"""
    try:
        call()
    except Exception as e:  # noqa: BLE001
        code = getattr(e, "status_code", None)
        assert code == status, f"{label}: 期望 {status},实际 {code} ({e})"
        if contains:
            detail = getattr(e, "detail", "")
            text = detail.get("error", "") if isinstance(detail, dict) else str(detail)
            assert contains in text, f"{label}: 期望文案含「{contains}」,实际「{text}」"
        return
    raise AssertionError(f"{label}: 期望抛出 HTTPException({status}),但没有抛")


def _auth_flow():
    """邮箱验证码全链路:覆盖发码/限流/过期/错码/单次使用/老用户复用账号。"""
    sent = {}

    def fake_send(email, code, ttl_minutes=10):
        sent["email"], sent["code"] = email, code
        return mailer.DELIVERY_CONSOLE, "冒烟测试不真发信"

    real_send = mailer.send_code_email
    mailer.send_code_email = fake_send
    try:
        # —— 邮箱规范化与非法输入 ——
        assert normalize_email("  A@Example.COM ") == "a@example.com"
        for bad in ("", "abc", "a@b", "a b@c.com", "a@@b.com", "a..b@c.com", "@c.com"):
            expect_http_error(lambda e=bad: normalize_email(e), 400, "邮箱格式", f"非法邮箱 {bad!r}")

        # —— 第一步:发码 ——
        resp = _send_code("A@Example.com")
        assert resp.email == "a@example.com" and resp.expiresInSeconds == 600
        assert resp.delivered is False and len(sent["code"]) == 6 and sent["code"].isdigit()
        code1 = sent["code"]
        # 库里只应有哈希,不含明文
        otp = db.latest_otp("a@example.com")
        assert otp["code_hash"] == auth.hash_code("a@example.com", code1)
        assert code1 not in otp["code_hash"] and otp["used"] == 0 and otp["attempts"] == 0
        # 重发间隔限流:立刻再发必被拒
        expect_http_error(
            lambda: _send_code("a@example.com"),
            429, "太频繁", "重发间隔限流")

        # —— 第二步:错码 → 次数耗尽后即使给对码也拒绝 ——
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="a@example.com", code="000000"
                                                 if code1 != "000000" else "111111")),
            400, "验证码错误", "错码")
        assert db.latest_otp("a@example.com")["attempts"] == 1
        # 非 6 位数字直接挡掉
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="a@example.com", code="12345")),
            400, "6 位数字", "位数校验")
        # 未发过码的邮箱
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="nobody@example.com", code="123456")),
            400, "请先获取验证码", "无码登录")

        # —— 正确登录:签发 token,首次为 isNew ——
        out = auth.login(auth.LoginRequest(email="a@example.com", code=code1, nickname=" 小A "))
        assert out["isNew"] is True and out["nickname"] == "小A" and len(out["token"]) == 48
        assert db.user_by_token(out["token"])["user_id"] == out["userId"]
        # 单次使用:同一个码再登一次必须失败
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="a@example.com", code=code1)),
            400, "已使用", "验证码单次使用")

        # —— 老用户再次登录:复用同一 userId,isNew=False ——
        _send_code("b@example.com")
        code2 = sent["code"]
        first = auth.login(auth.LoginRequest(email="b@example.com", code=code2))
        assert first["isNew"] is True
        db.execute("UPDATE email_otps SET created_at = datetime('now','-2 hours') WHERE email = ?",
                   ("b@example.com",))  # 绕过重发间隔(仅测试)
        _send_code("b@example.com")
        again = auth.login(auth.LoginRequest(email="b@example.com", code=sent["code"]))
        assert again["isNew"] is False and again["userId"] == first["userId"]
        assert again["nickname"] == first["nickname"], "重复登录不应改名"

        # —— 过期码:即使内容正确也拒绝 ——
        db.execute("UPDATE email_otps SET expires_at = datetime('now','-1 seconds') "
                   "WHERE email = ? AND used = 0", ("b@example.com",))
        db.execute("UPDATE email_otps SET created_at = datetime('now','-2 hours') WHERE email = ?",
                   ("b@example.com",))
        _send_code("b@example.com")
        db.execute("UPDATE email_otps SET expires_at = datetime('now','-1 seconds') WHERE email = ?",
                   ("b@example.com",))
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="b@example.com", code=sent["code"])),
            400, "已过期", "过期码")

        # —— 失败次数上限:5 次错码后作废 ——
        db.execute("UPDATE email_otps SET created_at = datetime('now','-2 hours') WHERE email = ?",
                   ("b@example.com",))
        _send_code("b@example.com")
        good = sent["code"]
        wrong = "000000" if good != "000000" else "111111"
        for i in range(5):
            expect_http_error(
                lambda: auth.login(auth.LoginRequest(email="b@example.com", code=wrong)),
                400, None, f"连续错码第 {i + 1} 次")
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="b@example.com", code=good)),
            400, "错误次数过多", "错码超限后作废")

        # —— 重发即作废旧码:新码可用,旧码失效 ——
        db.execute("UPDATE email_otps SET created_at = datetime('now','-2 hours') WHERE email = ?",
                   ("b@example.com",))
        _send_code("b@example.com")
        stale = sent["code"]
        db.execute("UPDATE email_otps SET created_at = datetime('now','-2 hours') WHERE email = ?",
                   ("b@example.com",))
        _send_code("b@example.com")
        fresh = sent["code"]
        assert stale != fresh
        expect_http_error(
            lambda: auth.login(auth.LoginRequest(email="b@example.com", code=stale)),
            400, "已失效", "重发后旧码失效(提示用户用最新码)")
        assert auth.login(auth.LoginRequest(email="b@example.com", code=fresh))["userId"] \
            == first["userId"]

        # —— 每小时发送上限:灌入 5 条"2 分钟前"的记录(避开 60 秒重发间隔)后必须被拒 ——
        db.execute("DELETE FROM email_otps WHERE email = ?", ("c@example.com",))
        for _ in range(5):
            db.insert_otp("c@example.com", "x" * 64, 600)
        db.execute("UPDATE email_otps SET created_at = datetime('now','-120 seconds') "
                   "WHERE email = ?", ("c@example.com",))
        assert db.count_otps_since("c@example.com", 3600) == 5
        expect_http_error(
            lambda: _send_code("c@example.com"),
            429, "1 小时内", "每小时发送上限")
        print("[ok] auth: 邮箱验证码(发码/限流/过期/错码上限/单次使用/老用户复用账号)")
    finally:
        mailer.send_code_email = real_send


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


def _rooms_multi_flow(uid1, tok1, uid2, uid3):
    """多人(3 人)对局关键语义:第一个人完成 ≠ 本局结束。

    验证:先完成者立刻拿到名次并被广播;未完成者**不会被服务端做任何冻结**,
    仍可继续上报进度与提交;只有全员完成后才广播权威 result,
    且每条成绩带各自的用时(名次 = 服务器接收序)。
    """
    rooms._rooms.clear()
    level = generator.generate_level("race-multi", "EASY", name="多人对战测试")
    answer = level["answerGrid"]

    async def main_flow():
        rooms._rooms["RM01"] = {
            "code": "RM01", "host_id": uid1, "phase": "lobby",
            "players": {}, "puzzle": None, "start_ms": None, "finishes": [],
        }
        host, p2, p3 = FakeWs(), FakeWs(), FakeWs()
        task_h = asyncio.create_task(rooms.room_socket(host, "RM01", tok1))
        await asyncio.sleep(0.05)
        task_2 = asyncio.create_task(rooms.room_socket(p2, "RM01", db.create_token(uid2, 30)))
        await asyncio.sleep(0.05)
        task_3 = asyncio.create_task(rooms.room_socket(p3, "RM01", db.create_token(uid3, 30)))
        await asyncio.sleep(0.05)
        assert host.accepted and p2.accepted and p3.accepted, "三人都应进入房间"
        room = rooms._rooms["RM01"]
        assert len(room["players"]) == 3, "房间应有 3 个席位"

        host.send("choosePuzzle", {"level": level})
        await asyncio.sleep(0.05)
        host.send("start")
        await asyncio.sleep(0.05)
        assert p3.frames("raceStart"), "第三人应收到题目"

        # —— 第一个人(uid2)完成:立刻广播名次,但房间仍是 racing,不发 result ——
        p2.send("finish", {"grid": answer, "elapsedMs": 5000})
        await asyncio.sleep(0.05)
        fin = p3.frames("finished")
        assert fin and fin[-1]["payload"]["rank"] == 1, "先完成者应立刻被广播为第 1 名"
        assert fin[-1]["payload"]["durationMs"] >= 0
        assert room["phase"] == "racing", "有人完成不等于本局结束,房间应仍是 racing"
        assert not p3.frames("result"), "未全员完成前不得广播 result"

        # —— 未完成者不受影响:仍能上报进度、仍能提交 ——
        p3.send("progress", {"filled": 7, "elapsedMs": 6000})
        await asyncio.sleep(0.03)
        prog = [f for f in host.frames("progress") if f["payload"]["userId"] == uid3]
        assert prog and prog[-1]["payload"]["filled"] == 7, "未完成者应能继续上报进度"

        # —— 第二个人(host)完成:仍不结算(还有 uid3) ——
        host.send("finish", {"grid": answer, "elapsedMs": 7000})
        await asyncio.sleep(0.05)
        assert room["phase"] == "racing", "还剩 1 人未完成,不应结算"
        assert not p3.frames("result"), "还剩 1 人未完成,不应广播 result"
        assert len(room["finishes"]) == 2

        # —— 最后一人完成 → 结算广播权威排行榜(含每人的用时) ——
        p3.send("finish", {"grid": answer, "elapsedMs": 9000})
        await asyncio.sleep(0.06)
        result = p3.frames("result")
        assert result, "全员完成后应广播 result"
        entries = result[0]["payload"]["entries"]
        assert [e["userId"] for e in entries] == [uid2, uid1, uid3], \
            f"名次应为接收序 uid2 > uid1 > uid3: {entries}"
        assert [e["rank"] for e in entries] == [1, 2, 3]
        assert room["phase"] == "settled"
        assert all(e["durationMs"] >= 0 for e in entries), "每条成绩应带服务器计时"

        # 清理
        for t in (task_h, task_2, task_3):
            t.cancel()
        for t in (task_h, task_2, task_3):
            try:
                await t
            except asyncio.CancelledError:
                pass

    asyncio.run(main_flow())
    rooms._rooms.clear()


def _ws_auth_flow(uid1, tok1):
    """WebSocket 鉴权两条路径:
      a) 首帧 auth 帧(新客户端:token 不进 URL,避免写进访问日志)→ 正常进房;
      b) 首帧 auth 带无效 token → 以 4401 关闭。
    query 传 token 的旧路径由 _rooms_flow / _rooms_multi_flow 覆盖(仍兼容)。
    """
    rooms._rooms.clear()
    rooms._rooms["RW01"] = {
        "code": "RW01", "host_id": uid1, "phase": "lobby",
        "players": {}, "puzzle": None, "start_ms": None, "finishes": [],
    }

    async def main_flow():
        # a) token 只在首帧里
        ws = FakeWs()
        task = asyncio.create_task(rooms.room_socket(ws, "RW01", ""))
        await asyncio.sleep(0.02)
        assert ws.accepted, "连接应先 accept(便于把拒绝原因送回客户端)"
        assert not ws.frames("roomState"), "未鉴权前不应收到 roomState"
        ws.send("auth", {"token": tok1})
        await asyncio.sleep(0.05)
        states = ws.frames("roomState")
        assert states, "首帧 auth 后应收到 roomState"
        players = states[-1]["payload"]["players"]
        assert any(p["userId"] == uid1 for p in players), "首帧 auth 应把该用户放进房间"
        task.cancel()

        # b) 无效 token:以 4401 关闭
        ws2 = FakeWs()
        old_timeout = rooms.AUTH_FRAME_TIMEOUT_SECONDS
        rooms.AUTH_FRAME_TIMEOUT_SECONDS = 0.2
        try:
            task2 = asyncio.create_task(rooms.room_socket(ws2, "RW01", ""))
            await asyncio.sleep(0.02)
            ws2.send("auth", {"token": "not-a-real-token"})
            await asyncio.sleep(0.05)
            assert ws2.closed and ws2.closed[0] == 4401, \
                f"无效 token 应以 4401 关闭,实际 {ws2.closed}"
            task2.cancel()
        finally:
            rooms.AUTH_FRAME_TIMEOUT_SECONDS = old_timeout

    asyncio.run(main_flow())
    print("[ok] ws auth: 首帧 auth 进房 / 无效 token 4401 拒绝")


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
    # 下发给客户端的 id 必须落在 Int32 内:安卓端用 Kotlin Int 承载,溢出会让整个接口
    # 在客户端解析失败(曾经用"时间戳毫秒"生成 id,直接把每日一题打成了"离线")。
    for pid in (row1["puzzle_id"],):
        assert INT32_MIN <= pid <= INT32_MAX, f"id 超出 Int32: {pid}"
    level = content.row_to_level(row1)
    assert generator.verify_unique(level), "每日一题未通过唯一解校验"
    print(f"[ok] daily {day} → id={row1['puzzle_id']} {row1['difficulty']}")

    # 3) 题库保底
    content.ensure_bank(conn, "EASY", target=3)
    assert content.bank_count(conn, "EASY") >= 3
    rows, total = content.bank_rows(conn, "EASY", 0, 10)
    assert total >= 3 and len(rows) >= 3
    assert all(INT32_MIN <= r["puzzle_id"] <= INT32_MAX for r in rows), "题库 id 超出 Int32"
    assert all(INT32_MIN <= r["puzzle_id"] <= INT32_MAX
               for r in content.bank_rows(conn, "EASY", 0, 100)[0]), "题库 id 超出 Int32"
    # bank 列表元信息不含超大答案,取整题接口再验唯一性
    full = content.row_to_level(content.puzzle_row(conn, rows[0]["puzzle_id"]))
    assert generator.verify_unique(full)
    print(f"[ok] bank EASY: {total} 题,抽检唯一性通过")

    # 4) 邮箱验证码登录:发码 → 校验 → 消费,含限流/过期/错误次数/单次使用
    _auth_flow()
    uid1 = db.query_one("SELECT user_id FROM users WHERE email = ?", ("a@example.com",))["user_id"]
    uid2 = db.query_one("SELECT user_id FROM users WHERE email = ?", ("b@example.com",))["user_id"]
    tok1 = db.create_token(uid1, 30)
    assert db.user_by_token(tok1)["user_id"] == uid1
    assert db.user_by_token("bogus") is None
    print(f"[ok] auth: user{uid1}(a@example.com) / user{uid2}(b@example.com)")

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

    # 8) 多人(3 人)语义:先完成者入榜但本局不结束,未完成者可继续,全员完成才结算
    uid3, _ = db.get_or_create_user("c@example.com", "小C")
    _rooms_multi_flow(uid1, tok1, uid2, uid3)
    print("[ok] room multi: 第一人完成不冻结他人 / 继续上报 / 全员完成出排行榜")

    # 8.5) WS 鉴权:首帧 auth(token 不进 URL)/ 无效 token 4401
    _ws_auth_flow(uid1, tok1)

    # 9) "我打过分的题"列表 —— 排行榜"对战榜"能选到题目的依据
    conn = db.connect()
    try:
        mine = compute_my_puzzles(conn, uid1, limit=20)
        assert mine, "打完对战后应能查到'我打过的题'"
        pids = [m["puzzleId"] for m in mine]
        # 8) 里的多人对局用的是随机在线关(负 id),应带上服务端题名
        assert any(p < 0 for p in pids), f"应包含在线关(负 id): {pids}"
        online = next(m for m in mine if m["puzzleId"] < 0)
        assert online["source"] == "online"
        assert online["name"], "在线关应带回服务端题名"
        assert online["plays"] >= 1
        # 内置关(正 id):服务端没有题名,由客户端本地关卡表补 —— 此处只验 source 标记
        db.execute("INSERT INTO solve_records(puzzle_id, user_id, duration_ms, source) "
                   "VALUES(1, ?, 12345, 'race')", (uid1,))
        mine2 = compute_my_puzzles(conn, uid1, limit=20)
        builtin = next(m for m in mine2 if m["puzzleId"] == 1)
        assert builtin["source"] == "builtin" and builtin["name"] is None, \
            "内置关不应由服务端返回题名(客户端本地补)"
        assert mine2[0]["puzzleId"] == 1, "应按最近一次成绩倒序(刚写入的内置关排最前)"
        print(f"[ok] my-puzzles: {len(mine2)} 题(含内置关 {builtin['puzzleId']} 与在线关 {online['puzzleId']})")
    finally:
        conn.close()

    conn.close()
    for suffix in ("", "-wal", "-shm"):
        try:
            os.remove(tmp_db + suffix)
        except OSError:
            pass  # Windows WAL 文件偶发占用,忽略
    print("\n全部冒烟测试通过 ✔")


if __name__ == "__main__":
    main()
