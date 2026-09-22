# -*- coding: utf-8 -*-
"""生成《作品说明书》里的示意图（PNG）。

不依赖 Word / Visio / Graphviz / 联网，只用 Pillow 手绘 + 超采样抗锯齿，可重复运行：

    python tools/make_diagrams.py --out docs/images              # 生成全部图
    python tools/make_diagrams.py --only usecase --out docs/images

当前内置：
    usecase   图 4.1 系统用例图（游客 / 注册玩家 / 服务端）

画完会打印每张图的尺寸与"文字是否超出图元"的自检结果，便于修改文字后复查。
"""
from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- 基本参数
SS = 3                              # 超采样倍数（先放大画，再缩回，边缘平滑）
INK = (45, 55, 70)
CASE_FILL = (232, 241, 253)
CASE_EDGE = (58, 106, 168)
CORE_FILL = (255, 243, 224)
CORE_EDGE = (206, 133, 42)
BOUND_FILL = (252, 253, 255)
BOUND_EDGE = (146, 155, 168)
MUTED = (105, 115, 130)

FONT_REG = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"


def load_font(size: float, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = FONT_BOLD if bold else FONT_REG
    if not os.path.exists(path):
        sys.exit(f"缺少中文字体：{path}")
    return ImageFont.truetype(path, int(round(size * SS)))


class Painter:
    """按“设计坐标”作画，内部统一乘以 SS 倍绘制。"""

    def __init__(self, w: float, h: float):
        self.w, self.h = w, h
        self.img = Image.new("RGB", (int(w * SS), int(h * SS)), "white")
        self.d = ImageDraw.Draw(self.img)
        self.warnings: list[str] = []

    # -- 基础图元 ------------------------------------------------------
    def line(self, p1, p2, fill=INK, width=1.6, dash=None):
        x1, y1 = p1[0] * SS, p1[1] * SS
        x2, y2 = p2[0] * SS, p2[1] * SS
        wpx = max(1, int(round(width * SS)))
        if not dash:
            self.d.line([x1, y1, x2, y2], fill=fill, width=wpx)
            return
        seg, gap = dash[0] * SS, dash[1] * SS
        total = ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5
        if total == 0:
            return
        ux, uy = (x2 - x1) / total, (y2 - y1) / total
        pos = 0.0
        while pos < total:
            end = min(pos + seg, total)
            self.d.line([x1 + ux * pos, y1 + uy * pos, x1 + ux * end, y1 + uy * end],
                        fill=fill, width=wpx)
            pos = end + gap

    def rect(self, x0, y0, x1, y1, fill=None, edge=None, width=1.4):
        self.d.rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=fill,
                         outline=edge, width=max(1, int(round(width * SS))))

    def ellipse(self, cx, cy, w, h, fill=CASE_FILL, edge=CASE_EDGE, width=1.6):
        self.d.ellipse([(cx - w / 2) * SS, (cy - h / 2) * SS,
                        (cx + w / 2) * SS, (cy + h / 2) * SS],
                       fill=fill, outline=edge, width=max(1, int(round(width * SS))))

    def polygon(self, pts, fill, edge, width=1.6):
        self.d.polygon([(x * SS, y * SS) for x, y in pts], fill=fill,
                       outline=edge, width=max(1, int(round(width * SS))))

    # -- 文字 ----------------------------------------------------------
    def tw(self, s: str, f: ImageFont.FreeTypeFont) -> float:
        """文本宽度（换算回设计单位）。"""
        return self.d.textlength(s, font=f) / SS

    def text(self, x, y, s, f, fill=INK, anchor="la"):
        self.d.text((x * SS, y * SS), s, font=f, fill=fill, anchor=anchor)

    def text_block(self, cx, cy, lines, f, fill=INK, line_h=34, where="figure"):
        """多行文字整体在 (cx, cy) 处居中（垂直按总高度居中）。"""
        total = line_h * len(lines)
        y = cy - total / 2
        for s in lines:
            self.text(cx, y, s, f, fill=fill, anchor="ma")
            y += line_h

    def check_fit(self, lines, f, avail, where, line_h=34):
        for s in lines:
            got = self.tw(s, f)
            if got > avail:
                self.warnings.append(
                    f"{where}：文字“{s}”宽 {got:.0f} > 可用 {avail:.0f}")

    # -- 输出 ----------------------------------------------------------
    def save(self, path: str):
        out = self.img.resize((int(self.w), int(self.h)), Image.LANCZOS)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        out.save(path)
        return out.size


def stick_figure(p: Painter, cx, cy):
    """小人（参与者）：头部圆心 (cx, cy-48)，整体高约 110。"""
    r = 15
    p.d.ellipse([(cx - r) * SS, (cy - 48 - r) * SS, (cx + r) * SS, (cy - 48 + r) * SS],
                outline=INK, width=max(1, int(round(2.2 * SS))))
    p.line((cx, cy - 33), (cx, cy + 10))
    p.line((cx - 26, cy - 18), (cx + 26, cy - 18))
    p.line((cx, cy + 10), (cx - 20, cy + 46))
    p.line((cx, cy + 10), (cx + 20, cy + 46))


# ---------------------------------------------------------------- 图 4.1 用例图
def draw_usecase(path: str):
    W, H = 1340, 1010
    p = Painter(W, H)
    f_case = load_font(26)
    f_core = load_font(27, bold=True)
    f_title = load_font(29, bold=True)
    f_actor = load_font(26)
    f_small = load_font(20)

    # 系统边界
    BX0, BY0, BX1, BY1 = 200, 60, 1120, 950
    p.rect(BX0, BY0, BX1, BY1, fill=BOUND_FILL, edge=BOUND_EDGE, width=1.4)
    p.text((BX0 + BX1) / 2, BY0 + 14, "像素填空（Fill-a-Pix）系统", f_title,
           fill=(40, 50, 65), anchor="ma")

    # 参与者
    stick_figure(p, 95, 300)
    p.text(95, 356, "游客", f_actor, anchor="ma")

    stick_figure(p, 95, 700)
    p.text_block(95, 790, ["注册玩家", "（已登录）"], f_actor, line_h=33)

    stick_figure(p, 1230, 520)
    p.text_block(1230, 612, ["服务端", "（系统参与者）"], f_actor, line_h=33)

    # 注册玩家 ▷ 游客（泛化）
    p.polygon([(95, 400), (79, 432), (111, 432)], fill="white", edge=INK, width=1.6)
    p.line((95, 432), (95, 634))

    # 用例（显式给行，避免中文断行难看）
    CW, CH = 250, 110
    col1 = [
        (150, ["邮箱验证码登录"], False),
        (290, ["玩内置关卡", "（可离线）"], False),
        (440, ["涂格推理", "（核心用例）"], True),
        (600, ["使用辅助工具"], False),
        (740, ["撤销 / 重做 / 重置"], False),
        (880, ["通关结算", "与解锁下一关"], False),
    ]
    col2 = [
        (250, ["每日一题与题库"], False),
        (430, ["排行榜与", "我的最佳成绩"], False),
        (620, ["创建 / 加入", "对战房间"], False),
        (800, ["实时竞速对战", "（断线可重连）"], False),
    ]
    col3 = [
        (190, ["发送邮箱验证码"], False),
        (360, ["签发与校验令牌"], False),
        (530, ["生成并校验唯一解"], False),
        (700, ["裁决名次与计时"], False),
        (870, ["聚合排行榜"], False),
    ]

    def put(cx, cy, lines, core):
        w = 290 if core else CW
        h = 124 if core else CH
        f = f_core if core else f_case
        p.ellipse(cx, cy, w, h,
                  fill=CORE_FILL if core else CASE_FILL,
                  edge=CORE_EDGE if core else CASE_EDGE,
                  width=2.0 if core else 1.6)
        # 可用宽度：按文字最外侧一行所处高度算椭圆内接宽度
        n = len(lines)
        row_h = 34
        off = (n - 1) * row_h / 2
        avail = w * (1 - (off / (h / 2)) ** 2) ** 0.5 - 18
        p.check_fit(lines, f, avail, f"用例“{lines[0]}”")
        p.text_block(cx, cy, lines, f, line_h=row_h)

    for cy, lines, core in col1:
        put(360, cy, lines, core)
    for cy, lines, _ in col2:
        put(680, cy, lines, False)
    for cy, lines, _ in col3:
        put(960, cy, lines, False)

    # 关联线：参与者 → 用例（指向椭圆左侧/右侧端点）
    for cy, lines, core in col1:
        p.line((121, 300), (360 - (290 if core else CW) / 2, cy), width=1.3)
    for cy, lines, _ in col2:
        p.line((121, 700), (680 - CW / 2, cy), width=1.3)
    for cy, lines, _ in col3:
        p.line((1204, 520), (960 + CW / 2, cy), width=1.3)

    # «extend»：使用辅助工具 → 涂格推理
    p.line((360, 545), (360, 510), dash=(7, 5), width=1.6, fill=MUTED)
    p.line((360, 510), (352, 522), width=1.6, fill=MUTED)
    p.line((360, 510), (368, 522), width=1.6, fill=MUTED)
    p.text(372, 506, "«extend»", f_small, fill=MUTED)

    # 图例
    p.text(670, 972,
           "实线 = 参与者与用例的关联　·　«extend» = 扩展用例　·　服务端为系统参与者，提供系统侧用例",
           f_small, fill=MUTED, anchor="ma")

    size = p.save(path)
    return size, p.warnings


# ---------------------------------------------------------------- 通用图元
def layer_row(p: Painter, x0, x1, y0, y1, name, lines, f_name, f_text,
              chip=(214, 228, 246), edge=(96, 130, 175), fill=(246, 250, 255),
              dash=None, note=None, f_note=None):
    """一层：左边名称小色块 + 右边说明文字。"""
    p.rect(x0, y0, x1, y1, fill=fill, edge=edge, width=1.6)
    if dash:
        p.rect(x0, y0, x1, y1, fill=fill, edge=edge, width=1.4)
        p.d.rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], outline=edge,
                      width=max(1, int(round(1.4 * SS))))
    chip_x1 = x0 + 168
    p.rect(x0, y0, chip_x1, y1, fill=chip, edge=edge, width=1.4)
    p.text((x0 + chip_x1) / 2, (y0 + y1) / 2, name, f_name, anchor="mm")
    avail = x1 - chip_x1 - 26
    p.check_fit(lines, f_text, avail, f"层“{name}”")
    total = 30 * len(lines)
    yy = (y0 + y1) / 2 - total / 2 + 2 if not note else y0 + 12
    for s in lines:
        p.text(chip_x1 + 16, yy, s, f_text)
        yy += 30
    if note:
        p.text(chip_x1 + 16, yy + 2, note, f_note, fill=MUTED)


def entity_box(p: Painter, cx, cy, w, title, fields, f_title, f_field,
               fill=(247, 250, 255), head=(219, 232, 249), edge=(84, 122, 170)):
    """实体矩形：标题带 + 字段行。返回 (x0, y0, x1, y1)。"""
    h = 46 + len(fields) * 28 + 14
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    p.rect(x0, y0, x1, y1, fill=fill, edge=edge, width=1.8)
    p.rect(x0, y0, x1, y0 + 46, fill=head, edge=edge, width=1.6)
    p.text(cx, y0 + 23, title, f_title, anchor="mm")
    yy = y0 + 54
    for s in fields:
        p.check_fit([s], f_field, w - 30, f"实体“{title}”")
        p.text(x0 + 15, yy, s, f_field)
        yy += 28
    return x0, y0, x1, y1


def diamond(p: Painter, cx, cy, w, h, label, f,
            fill=(255, 246, 228), edge=(198, 138, 56)):
    p.polygon([(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)],
              fill=fill, edge=edge, width=1.7)
    p.text(cx, cy, label, f, anchor="mm")


def note_box(p: Painter, x0, y0, x1, y1, lines, f, title=None, f_title=None):
    """虚线说明框（左上角折角）。"""
    p.rect(x0, y0, x1, y1, fill=(252, 252, 250), edge=(170, 168, 160), width=1.3)
    fold = 18
    p.line((x1 - fold, y0), (x1 - fold, y0 + fold), fill=(170, 168, 160), width=1.3)
    p.line((x1 - fold, y0 + fold), (x1, y0 + fold), fill=(170, 168, 160), width=1.3)
    yy = y0 + (40 if title else 12)
    if title:
        p.text(x0 + 14, y0 + 10, title, f_title, fill=(70, 70, 70))
    for s in lines:
        p.check_fit([s], f, x1 - x0 - 28, "说明框")
        p.text(x0 + 14, yy, s, f)
        yy += 27


# ---------------------------------------------------------------- 图 5.1 架构图
def draw_arch(path: str):
    W, H = 1340, 1088
    p = Painter(W, H)
    f_head = load_font(25, bold=True)
    f_layer = load_font(22, bold=True)
    f_text = load_font(22)
    f_small = load_font(19)
    f_tiny = load_font(18)

    # —— 客户端层 ——
    p.rect(48, 30, 1292, 560, fill=(248, 251, 255), edge=(176, 194, 216), width=1.6)
    p.text(670, 46, "客户端（Android / HarmonyOS，两端按同一份设计实现）",
           f_head, fill=(38, 60, 96), anchor="ma")
    LX0, LX1 = 68, 1272
    layer_row(p, LX0, LX1, 82, 158, "界面层",
              ["页面与控件：首页 · 游戏页 · 题库 · 每日一题 · 排行榜 · 对战房"],
              f_layer, f_text)
    layer_row(p, LX0, LX1, 168, 244, "应用层",
              ["游戏会话与状态：棋盘状态机 · 撤销栈 · 计时 · 通关看门狗 · 音效与背景音乐"],
              f_layer, f_text)
    layer_row(p, LX0, LX1, 254, 350, "领域层",
              ["谜题模型与校验（三态 · 已满足 · 通关）· 求解器（约束传播 + 回溯）· 出题器",
               "关卡表 · 对战状态机 · 帧编解码"],
              f_layer, f_text,
              note="这一层不引用任何平台 API：可被单元测试毫秒级覆盖，两端共用同一份设计",
              f_note=f_small)
    layer_row(p, LX0, LX1, 360, 436, "数据层",
              ["本地设置 · 关卡缓存 · HTTP 客户端 · WebSocket 传输 · 令牌与会话"],
              f_layer, f_text)
    layer_row(p, LX0, LX1, 446, 534, "横切模块",
              ["音效与背景音乐 · 网络状态监听 · 传感器（摇一摇）· 本地日志诊断"],
              f_layer, f_text, chip=(238, 240, 244), edge=(150, 158, 170),
              fill=(250, 250, 251))

    # —— 通信 ——
    l1 = "HTTP / JSON：登录 · 题库 · 排行榜 · 成绩"
    l2 = "WebSocket 帧：对战同步 · 服务器权威计时与校时"
    p.check_fit([l1], f_small, 830 - 366, "通信标注 1")
    p.check_fit([l2], f_small, 1330 - 846, "通信标注 2")
    p.line((360, 566), (360, 596), width=2.0)
    p.line((360, 566), (352, 578), width=2.0)
    p.line((360, 566), (368, 578), width=2.0)
    p.line((360, 596), (352, 584), width=2.0)
    p.line((360, 596), (368, 584), width=2.0)
    p.text(374, 570, l1, f_small)

    p.line((830, 566), (830, 596), width=2.0)
    p.line((830, 566), (822, 578), width=2.0)
    p.line((830, 566), (838, 578), width=2.0)
    p.line((830, 596), (822, 584), width=2.0)
    p.line((830, 596), (838, 584), width=2.0)
    p.text(844, 570, l2, f_small)

    # —— 服务端层 ——
    p.rect(48, 606, 1292, 936, fill=(253, 251, 248), edge=(214, 198, 176), width=1.6)
    p.text(670, 622, "服务端（Python · FastAPI · SQLite）", f_head,
           fill=(96, 66, 30), anchor="ma")
    layer_row(p, LX0, LX1, 658, 734, "路由层",
              ["认证 · 题库与每日一题 · 排行榜 · 房间对战（WebSocket）· 发信（SMTP）"],
              f_layer, f_text, chip=(246, 232, 212), edge=(186, 146, 86))
    layer_row(p, LX0, LX1, 744, 820, "服务层",
              ["谜题引擎（生成 · 唯一解校验 · 提示推导）· 服务器权威计时与名次裁决 · 排行榜聚合"],
              f_layer, f_text, chip=(246, 232, 212), edge=(186, 146, 86))
    layer_row(p, LX0, LX1, 830, 906, "数据层",
              ["SQLite 单文件（WAL）· users · tokens · puzzles · daily · solve_records · email_otps"],
              f_layer, f_text, chip=(246, 232, 212), edge=(186, 146, 86))

    # —— 说明 ——
    note_box(p, 48, 960, 660, 1062,
             ["关卡数据由脚本从 Android 端定义生成，",
              "逐关自检：图案一致 · 提示唯一解 · 唯一解即答案",
              "谜题引擎被服务端与工具链共用，判定只有一份实现"],
             f_tiny, title="共用资产", f_title=load_font(19, bold=True))
    note_box(p, 680, 960, 1292, 1062,
             ["Android 端另有蓝牙直连（RFCOMM），复用同一帧协议与状态机",
              "鸿蒙端不实现蓝牙，只保留网络对战（界面已说明原因）"],
             f_tiny, title="平台差异", f_title=load_font(19, bold=True))

    size = p.save(path)
    return size, p.warnings


# ---------------------------------------------------------------- 图 5.2 E-R 图
def draw_er(path: str):
    W, H = 1340, 950
    p = Painter(W, H)
    f_title = load_font(22, bold=True)
    f_field = load_font(20)
    f_rel = load_font(20)
    f_card = load_font(21, bold=True)
    f_note = load_font(19)
    f_note_t = load_font(19, bold=True)

    u = entity_box(p, 300, 190, 300, "用户 users",
                   ["user_id　PK", "email　UNIQUE", "nickname"],
                   f_title, f_field)
    q = entity_box(p, 1040, 200, 360, "题目 puzzles",
                   ["puzzle_id　PK", "source · difficulty", "rows · cols",
                    "clue_grid（提示矩阵）", "answer_grid（答案矩阵）"],
                   f_title, f_field)
    s = entity_box(p, 670, 500, 360, "成绩 solve_records",
                   ["record_id　PK", "user_id　FK", "puzzle_id　FK",
                    "duration_ms（用时）", "source（单人 / 对战）"],
                   f_title, f_field, fill=(247, 253, 249), head=(219, 243, 226),
                   edge=(74, 150, 106))
    v = entity_box(p, 300, 700, 320, "验证码 email_otps",
                   ["otp_id　PK", "email", "code_hash（sha256）",
                    "expires_at · used", "attempts（失败次数）"],
                   f_title, f_field, fill=(253, 248, 252), head=(243, 226, 240),
                   edge=(160, 106, 150))
    d = entity_box(p, 1090, 700, 280, "每日一题 daily",
                   ["day　PK（日期）", "puzzle_id　FK"],
                   f_title, f_field, fill=(253, 248, 252), head=(243, 226, 240),
                   edge=(160, 106, 150))

    # 联系：用户 1—N 成绩（提交）
    p.line((370, u[3]), (560, s[1]), width=1.6)
    diamond(p, 465, 316, 112, 50, "提交", f_rel)
    p.text(400, 270, "1", f_card, fill=(150, 60, 60))
    p.text(528, 372, "N", f_card, fill=(150, 60, 60))
    # 联系：题目 1—N 成绩（被作答）
    p.line((1000, q[3]), (790, s[1]), width=1.6)
    diamond(p, 895, 336, 130, 50, "被作答", f_rel)
    p.text(985, 304, "1", f_card, fill=(150, 60, 60))
    p.text(810, 378, "N", f_card, fill=(150, 60, 60))
    # 联系：题目 1—N 每日一题（排入）
    p.line((1080, q[3]), (1085, d[1]), width=1.6)
    diamond(p, 1083, 470, 112, 50, "排入", f_rel)
    p.text(1099, 400, "1", f_card, fill=(150, 60, 60))
    p.text(1099, 574, "N", f_card, fill=(150, 60, 60))
    # 弱关联：用户 — 验证码（同一邮箱）
    p.line((280, u[3]), (300, v[1]), dash=(9, 6), width=1.5, fill=MUTED)
    p.text(96, 452, "邮箱弱关联", f_note, fill=MUTED)
    p.text(96, 480, "（无外键）", f_note, fill=MUTED)

    note_box(p, 470, 830, 1292, 936,
             ["令牌表 tokens（token PK · user_id FK · expires_at，30 天）属会话数据；",
              "表间其余字段、索引与约束见表 5-1。用户与成绩、题目与成绩均为一对多。"],
             f_note, title="说明", f_title=f_note_t)

    size = p.save(path)
    return size, p.warnings


FIGURES = {
    "usecase": ("fig4-1-usecase.png", draw_usecase),
    "arch": ("fig5-1-arch.png", draw_arch),
    "er": ("fig5-2-er.png", draw_er),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs/images", help="输出目录")
    ap.add_argument("--only", default=None, help="只生成某一张（usecase）")
    a = ap.parse_args()

    names = [a.only] if a.only else list(FIGURES)
    for n in names:
        if n not in FIGURES:
            sys.exit(f"未知图名：{n}（可选：{', '.join(FIGURES)}）")
        fn, drawer = FIGURES[n]
        out = os.path.join(a.out, fn)
        size, warns = drawer(out)
        print(f"[ok] {out}  {size[0]}x{size[1]}px")
        for w in warns:
            print(f"     [警告] {w}")
    print("完成。重新生成 Word：python tools/html_to_docx.py "
          "--html 作品说明书-最终版.html --out 作品说明书-最终版.docx --root .")


if __name__ == "__main__":
    main()
