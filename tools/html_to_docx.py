#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把作品说明书 HTML 转成 .docx(**不依赖 Word**,直接生成 OOXML)。

为什么不用 Word COM:
  本机 Word 的 COM 自动化会卡住(可能弹了未响应对话框),在无人值守环境里不可靠。
  OOXML 本质是一个 zip + 几个 XML,用标准库直接生成既稳又快,而且样式完全可控。

支持:多级标题(黑体)、正文(宋体小四/行距 20 磅)、无序列表、表格(带边框)、
      居中图片(自动嵌入 word/media)、图注与表注(居中)。

用法:
  python tools/html_to_docx.py --html 作品说明书-最终版.html --out 作品说明书-最终版.docx
"""
import argparse
import os
import re
import sys
import zipfile
from xml.sax.saxutils import escape

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
WP = 'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"'
A = 'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"'
PIC = 'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"'

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="jpeg" ContentType="image/jpeg"/>
<Default Extension="jpg" ContentType="image/jpeg"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

# 样式:正文宋体小四(12pt)+ 行距 20 磅;标题黑体四号(14pt);图注 10.5pt 居中
STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="&#23435;&#20307;"/>
<w:sz w:val="24"/><w:szCs w:val="24"/>
</w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
<w:pPr><w:spacing w:line="400" w:lineRule="exact"/><w:ind w:firstLineChars="200" w:firstLine="480"/>
<w:jc w:val="both"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
<w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="240" w:after="120" w:line="400" w:lineRule="exact"/>
<w:ind w:firstLine="0"/><w:outlineLvl w:val="0"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="&#40657;&#20307;" w:ascii="Times New Roman" w:hAnsi="Times New Roman"/>
<w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/>
<w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="160" w:after="80" w:line="400" w:lineRule="exact"/>
<w:ind w:firstLine="0"/><w:outlineLvl w:val="1"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="&#40657;&#20307;" w:ascii="Times New Roman" w:hAnsi="Times New Roman"/>
<w:b/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListPara"><w:name w:val="List Paragraph"/>
<w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="480" w:hanging="240" w:firstLine="0"/>
<w:spacing w:line="400" w:lineRule="exact"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Caption2"><w:name w:val="Caption"/>
<w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/>
<w:spacing w:after="120" w:line="360" w:lineRule="exact"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="&#40657;&#20307;"/><w:sz w:val="21"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="CenterFig"><w:name w:val="Figure"/>
<w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="CoverLine"><w:name w:val="Cover Line"/>
<w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/>
<w:spacing w:line="480" w:lineRule="exact"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="&#40657;&#20307;"/><w:sz w:val="32"/><w:b/></w:rPr></w:style>
</w:styles>""".replace("{W}", W)


def run(text, bold=False, size=None, font_ea=None):
    """一段文本 → 若干 w:r(处理 <b> 与 <code>)。"""
    parts = re.split(r"(<b>.*?</b>|<code>.*?</code>)", text, flags=re.S)
    out = []
    for p in parts:
        if not p:
            continue
        b = bold
        ea = font_ea
        sz = size
        ascii_font = None
        if p.startswith("<b>"):
            p = p[3:-4]
            b = True
        elif p.startswith("<code>"):
            p = p[6:-7]
            ascii_font = "Consolas"
            ea = "Consolas"
        if not p:
            continue
        props = ""
        if b:
            props += "<w:b/>"
        fonts = f'<w:rFonts w:ascii="{ascii_font or "Times New Roman"}" w:hAnsi="{ascii_font or "Times New Roman"}"'
        if ea:
            fonts += f' w:eastAsia="{ea}"'
        fonts += "/>"
        props = fonts + props
        if sz:
            props += f'<w:sz w:val="{sz}"/><w:szCs w:val="{sz}"/>'
        out.append(f"<w:r><w:rPr>{props}</w:rPr><w:t xml:space=\"preserve\">{escape(p)}</w:t></w:r>")
    return "".join(out) or '<w:r><w:t xml:space="preserve"></w:t></w:r>'


def para(text, style="Normal", bold=False, size=None, font_ea=None):
    ppr = f'<w:pPr><w:pStyle w:val="{style}"/></w:pPr>' if style else ""
    return f"<w:p>{ppr}{run(text, bold, size, font_ea)}</w:p>"


def table(rows):
    """rows: [[cell,...], ...],第一行当表头。"""
    grid = rows[0]
    widths = []
    for i in range(len(grid)):
        widths.append('<w:gridCol w:w="%d"/>' % int(9000 / len(grid)))
    borders = ("<w:tblBorders>" + "".join(
        f'<w:{s} w:val="single" w:sz="4" w:space="0" w:color="000000"/>'
        for s in ("top", "left", "bottom", "right", "insideH", "insideV")) + "</w:tblBorders>")
    out = ['<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/>' + borders + '</w:tblPr><w:tblGrid>'
           + "".join(widths) + "</w:tblGrid>"]
    for ri, row in enumerate(rows):
        out.append("<w:tr>")
        for cell in row:
            shade = '<w:shd w:val="clear" w:fill="F2F2F2"/>' if ri == 0 else ""
            jc = '<w:jc w:val="center"/>' if ri == 0 else ""
            out.append(
                '<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/>' + shade + "</w:tcPr>"
                + f'<w:p><w:pPr><w:ind w:firstLine="0"/><w:spacing w:line="300" w:lineRule="exact"/>{jc}</w:pPr>'
                + run(cell, bold=(ri == 0), size=21)
                + "</w:p></w:tc>")
        out.append("</w:tr>")
    out.append("</w:tbl>")
    # 表格后补一个空段,避免与后续内容粘连
    out.append('<w:p><w:pPr><w:spacing w:line="240" w:lineRule="exact"/></w:pPr></w:p>')
    return "".join(out)


def image_para(rid, cx, cy, docpr_id):
    return (
        '<w:p><w:pPr><w:pStyle w:val="CenterFig"/></w:pPr><w:r><w:drawing>'
        f'<wp:inline distT="0" distB="0" distL="0" distR="0">'
        f'<wp:extent cx="{cx}" cy="{cy}"/>'
        f'<wp:docPr id="{docpr_id}" name="Picture {docpr_id}"/>'
        f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        f'<pic:pic><pic:nvPicPr><pic:cNvPr id="{docpr_id}" name="p{docpr_id}"/><pic:cNvPicPr/></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
        f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>'
        f'</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>')


def image_size(path):
    """读图片宽高(用于按比例算显示尺寸,避免拉伸变形)。

    优先用 Pillow(支持 PNG/JPEG/GIF/BMP/WebP);没有 Pillow 时退化为
    读文件头解析 PNG(IHDR) 与 JPEG(SOF 标记),两者都读不到才用默认值。
    """
    try:
        from PIL import Image
        with Image.open(path) as im:
            return im.size
    except Exception:
        pass

    with open(path, "rb") as f:
        data = f.read()

    if data[:8] == b"\x89PNG\r\n\x1a\n":           # PNG: IHDR 紧跟文件头
        w = int.from_bytes(data[16:20], "big")
        h = int.from_bytes(data[20:24], "big")
        if w and h:
            return w, h

    if data[:2] == b"\xff\xd8":                    # JPEG: 扫描 SOF 段
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                          0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h = int.from_bytes(data[i + 5:i + 7], "big")
                w = int.from_bytes(data[i + 7:i + 9], "big")
                if w and h:
                    return w, h
            seg = int.from_bytes(data[i + 2:i + 4], "big")
            i += 2 + seg

    return 800, 600


def parse_html(html, root):
    body = html.split("<body>", 1)[1].split("</body>")[0]
    blocks = []
    # 依次扫描 h1/h2/p/ul/table/图
    token = re.compile(
        r"<h1>(.*?)</h1>|<h2>(.*?)</h2>|<p class=\"(cover-title|cover-sub|fcap|tcap|fig)\">(.*?)</p>"
        r"|<p(?: class=\"noindent\")?>(.*?)</p>|<ul>(.*?)</ul>|<table>(.*?)</table>"
        r"|<div class=\"fig\"><img src=\"([^\"]+)\"[^>]*></div>",
        re.S)
    for m in token.finditer(body):
        if m.group(1) is not None:
            blocks.append(("h1", m.group(1)))
        elif m.group(2) is not None:
            blocks.append(("h2", m.group(2)))
        elif m.group(3) is not None:
            kind = m.group(3)
            if kind == "cover-title":
                blocks.append(("cover", m.group(4)))
            elif kind == "cover-sub":
                blocks.append(("coversub", m.group(4)))
            elif kind == "fcap":
                blocks.append(("caption", m.group(4)))
            elif kind == "tcap":
                blocks.append(("caption", m.group(4)))
            else:
                blocks.append(("figclass", m.group(4)))
        elif m.group(5) is not None:
            blocks.append(("p", m.group(5)))
        elif m.group(6) is not None:
            items = re.findall(r"<li>(.*?)</li>", m.group(6), re.S)
            blocks.append(("ul", items))
        elif m.group(7) is not None:
            rows = []
            for tr in re.findall(r"<tr>(.*?)</tr>", m.group(7), re.S):
                cells = [re.sub(r"<[^>]+>", "", c).strip()
                         for c in re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", tr, re.S)]
                if cells:
                    rows.append(cells)
            blocks.append(("table", rows))
        elif m.group(8) is not None:
            blocks.append(("img", m.group(8)))
    return blocks


def build_docx(blocks, root, out_path):
    media = []       # (rel_name, abs_path)
    rels = []
    doc_parts = []
    docpr = 100
    for kind, payload in blocks:
        if kind == "cover":
            doc_parts.append(para(payload, "CoverLine", size=32, font_ea="黑体"))
        elif kind == "coversub":
            doc_parts.append(para(payload, "CenterFig", size=24))
        elif kind == "h1":
            doc_parts.append(para(payload, "Heading1", bold=True, size=28, font_ea="黑体"))
        elif kind == "h2":
            doc_parts.append(para(payload, "Heading2", bold=True, size=24, font_ea="黑体"))
        elif kind == "p":
            doc_parts.append(para(payload))
        elif kind == "caption":
            doc_parts.append(para(payload, "Caption2", size=21, font_ea="黑体"))
        elif kind == "figclass":
            doc_parts.append(para(payload, "CenterFig"))
        elif kind == "ul":
            for it in payload:
                doc_parts.append(para("● " + it, "ListPara"))
        elif kind == "table":
            doc_parts.append(table(payload))
        elif kind == "img":
            abs_path = os.path.join(root, payload.replace("/", os.sep))
            if not os.path.exists(abs_path):
                continue  # 图片不存在就不放,避免破图占位
            ext = os.path.splitext(abs_path)[1].lower()
            idx = len(media) + 1
            rel_name = f"image{idx}{ext}"
            media.append((rel_name, abs_path))
            rid = f"rIdImg{idx}"
            w, h = image_size(abs_path)
            # 正文可用宽度 = 页宽 11906 - 左右页边距 1797×2 = 8312 twips = 5,278,120 EMU
            max_w = 5_270_000  # 约 14.6cm，保证图片不越出页边距
            cx = min(max_w, w * 9525)
            cy = int(cx * h / w)
            docpr += 1
            doc_parts.append(image_para(rid, cx, cy, docpr))
            rels.append((rid, rel_name))

    sect = ('<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1797" w:bottom="1440" w:left="1797" '
            'w:header="851" w:footer="992" w:gutter="0"/></w:sectPr>')
    document = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                f'<w:document {W} {R} {WP} {A} {PIC}><w:body>'
                + "".join(doc_parts) + sect + "</w:body></w:document>")

    rel_xml = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">']
    for rid, name in rels:
        rel_xml.append(f'<Relationship Id="{rid}" '
                       'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
                       f'Target="media/{name}"/>')
    rel_xml.append("</Relationships>")

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", RELS)
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/_rels/document.xml.rels", "".join(rel_xml))
        for name, ap in media:
            z.write(ap, f"word/media/{name}")
    return len(media)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--html", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--root", default=".")
    a = ap.parse_args()

    html = open(a.html, encoding="utf-8").read()
    blocks = parse_html(html, a.root)
    n_img = build_docx(blocks, a.root, a.out)
    size = os.path.getsize(a.out)
    kinds = {}
    for k, _ in blocks:
        kinds[k] = kinds.get(k, 0) + 1
    print(f"[ok] {a.out}  ({size/1024:.0f} KB)")
    print(f"     内容块:{kinds}")
    print(f"     嵌入图片:{n_img} 张")
    return 0


if __name__ == "__main__":
    sys.exit(main())
