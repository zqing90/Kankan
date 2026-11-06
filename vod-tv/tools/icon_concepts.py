# -*- coding: utf-8 -*-
"""
图标概念稿：只出预览，不动 res/ 下的正式资源。

参照夸克 / UU远程 / 飞牛的共同规律：
  纯色底 + 单色（最多双色）巨大几何图形 + 无渐变无细节

用法：python icon_concepts.py
产物：D:/tools/_dl/concept_*.png 与 concept_sheet.png（横向对比图）
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = "D:/tools/_dl"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

BLUE = (43, 92, 255)        # 主蓝，接近夸克
BLUE_DEEP = (26, 56, 190)
CYAN = (40, 205, 240)
WHITE = (255, 255, 255)


def rounded_mask(w, h, radius, ss=8):
    m = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, w * ss - 1, h * ss - 1], radius=radius * ss, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def soften(mask, r):
    """把 mask 的尖角磨圆：高斯模糊后二值化，等效圆角半径≈r"""
    if r <= 0:
        return mask
    b = mask.filter(ImageFilter.GaussianBlur(r * 0.55))
    return b.point(lambda v: 255 if v > 127 else 0)


def tri_mask(S, w_ratio, h_ratio, dx=0.0, dy=0.0, round_r=0.0):
    """指向右的播放三角，dx/dy 用于光学居中微调"""
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    tw, th = S * w_ratio, S * h_ratio
    cx, cy = S / 2 + S * dx, S / 2 + S * dy
    pts = [(cx - tw / 2, cy - th / 2), (cx - tw / 2, cy + th / 2), (cx + tw / 2, cy)]
    d.polygon(pts, fill=255)
    return soften(m, S * round_r)


def paste_shape(base, mask, color, alpha=255):
    layer = Image.new("RGBA", base.size, color + (0,))
    layer.putalpha(mask.point(lambda v: v * alpha // 255))
    base.alpha_composite(layer)
    return base


def canvas(S, color):
    return Image.new("RGBA", (S, S), color + (255,))


def finish(base, size, corner=0.225):
    mask = rounded_mask(base.size[0], base.size[1], int(base.size[0] * corner))
    out = Image.new("RGBA", base.size, (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- 方案 A
def concept_a(size, S=1024):
    """夸克式：蓝底 + 纯白三角。最极简，缩到 48px 也不糊。"""
    img = canvas(S, BLUE)
    img = paste_shape(img, tri_mask(S, 0.36, 0.44, dx=0.022, round_r=0.018), WHITE)
    return finish(img, size)


# ---------------------------------------------------------------- 方案 B
def concept_b(size, S=1024):
    """蓝底 + 白三角 + 下方进度条（一实一半透），多一层「正在播放」的暗示。"""
    img = canvas(S, BLUE)

    # 三角上移，给进度条腾位置
    img = paste_shape(img, tri_mask(S, 0.34, 0.40, dx=0.020, dy=-0.075, round_r=0.018), WHITE)

    # 进度条：背景槽 + 已完成段
    d_mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(d_mask)
    bw = S * 0.50
    bh = S * 0.055
    bx = (S - bw) / 2
    by = S * 0.685
    r = bh / 2
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=r, fill=110)
    d.rounded_rectangle([bx, by, bx + bw * 0.62, by + bh], radius=r, fill=255)
    img = paste_shape(img, d_mask, WHITE)
    return finish(img, size)


# ---------------------------------------------------------------- 方案 C
def concept_c(size, S=1024):
    """UU 式：白底 + 蓝/青双色错位三角。桌面上一排深色图标里最跳。"""
    img = canvas(S, WHITE)

    # 后层浅蓝三角（略微右移放大）
    img = paste_shape(img, tri_mask(S, 0.38, 0.46, dx=0.045, round_r=0.018),
                      (176, 205, 255))

    # 前层主蓝三角
    img = paste_shape(img, tri_mask(S, 0.30, 0.38, dx=0.005, round_r=0.016), BLUE)

    # 左上青色小块，呼应 UU 的双色感
    sq = Image.new("L", (S, S), 0)
    ImageDraw.Draw(sq).ellipse([S * 0.10, S * 0.10, S * 0.10 + S * 0.115, S * 0.10 + S * 0.115],
                               fill=255)
    img = paste_shape(img, soften(sq, 0), CYAN)
    return finish(img, size)


# ---------------------------------------------------------------- 方案 D
def concept_d(size, S=1024):
    """蓝底 + 白色播放键（圆角三角）+ 右侧一道斜切高光，比 A 更「有质感」。"""
    img = canvas(S, BLUE)

    # 底层暗蓝斜块
    base = Image.new("L", (S, S), 0)
    ImageDraw.Draw(base).polygon([(0, S), (0, S * 0.55), (S * 0.62, S)], fill=255)
    img = paste_shape(img, base, BLUE_DEEP, alpha=90)

    img = paste_shape(img, tri_mask(S, 0.36, 0.44, dx=0.022, round_r=0.020), WHITE)
    return finish(img, size)


CONCEPTS = [
    ("A", concept_a, "蓝底白三角 · 最简"),
    ("B", concept_b, "三角+进度条 · 影视感"),
    ("C", concept_c, "白底双色 · 最跳"),
    ("D", concept_d, "蓝底+暗块 · 有层次"),
]


def sheet(cell=240, pad=34):
    """横向对比图，深色桌面背景 + 标签"""
    n = len(CONCEPTS)
    W = pad + n * (cell + pad)
    H = cell + pad * 3 + 46
    img = Image.new("RGB", (W, H), (24, 25, 28))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT_BOLD, 22)
    f2 = ImageFont.truetype(FONT_BOLD, 15)

    for i, (tag, fn, desc) in enumerate(CONCEPTS):
        ic = fn(cell)
        x = pad + i * (cell + pad)
        img.paste(ic, (x, pad), ic)
        d.text((x, pad + cell + 12), tag, font=f, fill=(240, 240, 245))
        d.text((x + 26, pad + cell + 17), desc, font=f2, fill=(150, 156, 168))

    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for tag, fn, _ in CONCEPTS:
        fn(512).save(os.path.join(OUT, "concept_%s_512.png" % tag))
        fn(96).save(os.path.join(OUT, "concept_%s_96.png" % tag))
    s = sheet()
    s.save(os.path.join(OUT, "concept_sheet.png"))
    print("ok:", s.size)


if __name__ == "__main__":
    main()
