# -*- coding: utf-8 -*-
"""
抓取 Google Material Icons 官方图标，转成各密度 PNG。

为什么不自己画：Material Icons 是 Apache-2.0 许可的现成图形库，
形状、光学居中、描边粗细都经过大量设备验证，手画只会更难看。
源：https://github.com/google/material-design-icons （src/<category>/<name>/<variant>/24px.svg）
变体由环境变量 MATERIAL_VARIANT 指定，默认 materialiconsround（圆头）。

流程：拉 SVG（缓存到 icons_src/）→ 填充改白 → svglib 转 PDF → pdfium 光栅化到 96px
     → LANCZOS 缩到各密度 → 写入 res/drawable-*/。
用法：python fetch_material_icons.py
"""
import io
import os
import re
import sys
import tempfile
import urllib.request

import pypdfium2 as pdfium
from PIL import Image
from reportlab.graphics import renderPDF
from svglib.svglib import svg2rlg

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
CACHE = os.path.join(HERE, "icons_src")

# 图标变体：materialicons=filled / materialiconsround=圆头 / materialiconsoutlined=细线
# 本项目选 Rounded —— 界面圆角是 8dp，圆头图标才和它同一种语言
VARIANT = os.environ.get("MATERIAL_VARIANT", "materialiconsround")

BASE = ("https://raw.githubusercontent.com/google/material-design-icons/master/"
        "src/%s/%s/" + VARIANT + "/24px.svg")

# 本地文件名 -> (Material 分类, Material 图标名)
ICONS = {
    "ic_ui_search":    ("action", "search"),
    "ic_ui_history":   ("action", "history"),
    "ic_ui_refresh":   ("navigation", "refresh"),
    "ic_ui_settings":  ("action", "settings"),
    "ic_ui_back":      ("navigation", "arrow_back"),
    "ic_ui_delete":    ("action", "delete"),
    # 播放控制：Material 原生就有 ±10 秒图标，不用自己拿文字凑
    "ic_ui_replay10":  ("av", "replay_10"),
    "ic_ui_forward10": ("av", "forward_10"),
    "ic_ui_play":      ("av", "play_arrow"),
    "ic_ui_pause":     ("av", "pause"),
    "ic_ui_prev":      ("av", "skip_previous"),
    "ic_ui_next":      ("av", "skip_next"),
    "ic_ui_episodes":  ("av", "playlist_play"),
}

DENSITIES = [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)]
RENDER_PX = 96          # 先渲染到最大密度再往下缩，边缘更干净


def fetch(category, name):
    """返回 SVG 文本；本地有缓存就不联网"""
    cache_dir = os.path.join(CACHE, VARIANT)
    if not os.path.isdir(cache_dir):
        os.makedirs(cache_dir)
    path = os.path.join(cache_dir, name + ".svg")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return f.read()
    with urllib.request.urlopen(BASE % (category, name), timeout=30) as r:
        text = r.read().decode("utf-8")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def to_white(svg):
    """Material 的 SVG 默认黑色填充，统一改白色；fill="none" 的占位路径保持不动"""
    svg = re.sub(r'fill="(?!none)[^"]*"', 'fill="#ffffff"', svg)
    # 没有 fill 属性的 path 补白色（第一版漏了这步，渲染出来全黑）
    svg = re.sub(r"<path (?![^>]*fill=)", '<path fill="#ffffff" ', svg)
    return svg


def center_glyph(im):
    """把字形挪到画布正中（只改位置，不改字形尺寸）。

    svglib → PDF → pdfium 这条链路里，PDF 页面比图形 bbox 大，光栅化后
    字形会落在画布的偏左下（实测 96px 画布上偏 (-12, +12)，即 1/8 画幅）。
    结果是每个图标在按钮里都往左下角歪，右上留一大片空，字显得又小又飘。
    按 alpha bbox 居中即可，不缩放：Material 各图标的相对大小是设计好的。
    """
    bb = im.split()[3].getbbox()
    if not bb:
        return im
    glyph = im.crop(bb)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(glyph, ((im.width - glyph.width) // 2,
                      (im.height - glyph.height) // 2), glyph)
    return out


def render(svg_text, px):
    drawing = svg2rlg(io.StringIO(svg_text))
    if drawing is None:
        raise RuntimeError("svglib 解析失败")
    drawing.width, drawing.height = 24, 24

    fd, pdf = tempfile.mkstemp(suffix=".pdf")
    os.close(fd)
    try:
        renderPDF.drawToFile(drawing, pdf)
        doc = pdfium.PdfDocument(pdf)
        page = doc[0]
        scale = px / page.get_width()
        pil = page.render(scale=scale, fill_color=(0, 0, 0, 0)).to_pil()
        doc.close()
    finally:
        try:
            os.remove(pdf)
        except OSError:
            pass
    return center_glyph(pil.convert("RGBA")).resize((px, px), Image.LANCZOS)


def main():
    failed = []
    for local, (cat, name) in sorted(ICONS.items()):
        try:
            big = render(to_white(fetch(cat, name)), RENDER_PX)
        except Exception as ex:                     # 单个失败不影响其他
            failed.append("%s(%s/%s): %s" % (local, cat, name, ex))
            continue
        for dpi, px in DENSITIES:
            out_dir = os.path.join(RES, "drawable-" + dpi)
            if not os.path.isdir(out_dir):
                os.makedirs(out_dir)
            big.resize((px, px), Image.LANCZOS).save(os.path.join(out_dir, local + ".png"))
        print("ok  %-18s %s/%s" % (local, cat, name))

    if failed:
        print("\n失败：")
        for f in failed:
            print("  " + f)
        sys.exit(1)


if __name__ == "__main__":
    main()
