# -*- coding: utf-8 -*-
"""第二轮图标概念稿。只出预览。"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = "D:/tools/_dl"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

BLUE = (43, 92, 255)
CYAN = (40, 205, 240)
WHITE = (255, 255, 255)


def rounded_mask(w, h, radius, ss=8):
    m = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, w * ss - 1, h * ss - 1], radius=radius * ss, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def soften(mask, r):
    if r <= 0:
        return mask
    return mask.filter(ImageFilter.GaussianBlur(r * 0.55)).point(
        lambda v: 255 if v > 127 else 0)


def tri_mask(S, w_ratio, h_ratio, dx=0.0, dy=0.0, round_r=0.0):
    m = Image.new("L", (S, S), 0)
    tw, th = S * w_ratio, S * h_ratio
    cx, cy = S / 2 + S * dx, S / 2 + S * dy
    ImageDraw.Draw(m).polygon(
        [(cx - tw / 2, cy - th / 2), (cx - tw / 2, cy + th / 2), (cx + tw / 2, cy)], fill=255)
    return soften(m, S * round_r)


def ring_mask(S, outer, thickness):
    m = Image.new("L", (S * 2, S * 2), 0)
    o = S * outer
    t = S * thickness
    c = S
    ImageDraw.Draw(m).ellipse([c - o, c - o, c + o, c + o], fill=255)
    ImageDraw.Draw(m).ellipse([c - o + t, c - o + t, c + o - t, c + o - t], fill=0)
    return m.resize((S, S), Image.LANCZOS)


def paste(base, mask, color, alpha=255):
    layer = Image.new("RGBA", base.size, color + (0,))
    layer.putalpha(mask.point(lambda v: v * alpha // 255))
    base.alpha_composite(layer)
    return base


def canvas(S, color):
    return Image.new("RGBA", (S, S), color + (255,))


def finish(base, size, corner=0.225):
    S = base.size[0]
    mask = rounded_mask(S, S, int(S * corner))
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


# ------------------------------------------------------------ A2 基准
def v2_a(size, S=1024):
    """蓝底 + 纯白三角。干净，但偏通用。"""
    img = canvas(S, BLUE)
    return finish(paste(img, tri_mask(S, 0.345, 0.425, dx=0.020, round_r=0.016), WHITE), size)


# ------------------------------------------------------------ B2 影视感
def v2_b(size, S=1024):
    """三角 + 底部进度条：垂直节奏收紧，读作「正在播放」。"""
    img = canvas(S, BLUE)
    img = paste(img, tri_mask(S, 0.315, 0.385, dx=0.018, dy=-0.070, round_r=0.015), WHITE)
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    bw, bh = S * 0.40, S * 0.050
    bx, by = (S - bw) / 2, S * 0.645
    r = bh / 2
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=r, fill=90)
    d.rounded_rectangle([bx, by, bx + bw * 0.60, by + bh], radius=r, fill=255)
    img = paste(img, m, WHITE)
    return finish(img, size)


# ------------------------------------------------------------ C2 圆环
def v2_c(size, S=1024):
    """蓝底 + 白色圆环 + 中心三角。圆环呼应夸克的圆，比裸三角更有标示性。"""
    img = canvas(S, BLUE)
    ring = ring_mask(S, 0.290, 0.070)
    img = paste(img, ring, WHITE)
    img = paste(img, tri_mask(S, 0.185, 0.225, dx=0.012, round_r=0.010), WHITE)
    return finish(img, size)


# ------------------------------------------------------------ E 屏幕
def v2_e(size, S=1024):
    """白底 + 蓝色圆角屏幕 + 镂空三角。最直白，一眼是「视频播放器」。"""
    img = canvas(S, WHITE)
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    rw, rh = S * 0.70, S * 0.56
    x0, y0 = (S - rw) / 2, (S - rh) / 2
    d.rounded_rectangle([x0, y0, x0 + rw, y0 + rh], radius=int(S * 0.115), fill=255)
    hole = tri_mask(S, 0.20, 0.25, dx=0.014, round_r=0.010)
    m = Image.composite(Image.new("L", (S, S), 0), m, hole)   # 三角处挖空
    img = paste(img, m, BLUE)
    return finish(img, size)


CONCEPTS = [
    ("A2", v2_a, "白三角 · 极简"),
    ("B2", v2_b, "三角+进度条 · 影视"),
    ("C2", v2_c, "圆环+三角 · 品牌感"),
    ("E", v2_e, "白底+屏幕 · 最直白"),
]


def sheet(cell=240, pad=34):
    n = len(CONCEPTS)
    W = pad + n * (cell + pad)
    H = cell + pad * 3 + 40
    img = Image.new("RGB", (W, H), (24, 25, 28))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT_BOLD, 21)
    f2 = ImageFont.truetype(FONT_BOLD, 14)
    for i, (tag, fn, desc) in enumerate(CONCEPTS):
        ic = fn(cell)
        x = pad + i * (cell + pad)
        img.paste(ic, (x, pad), ic)
        d.text((x, pad + cell + 12), tag, font=f, fill=(240, 240, 245))
        d.text((x + 30, pad + cell + 16), desc, font=f2, fill=(150, 156, 168))
    return img


def small_sheet(cell=72, pad=22):
    """96px 小尺寸对比，验证缩到启动器尺寸还能不能看清"""
    n = len(CONCEPTS)
    W = pad + n * (cell + pad)
    H = cell + pad * 2
    img = Image.new("RGB", (W, H), (24, 25, 28))
    for i, (_, fn, _) in enumerate(CONCEPTS):
        ic = fn(cell)
        img.paste(ic, (pad + i * (cell + pad), pad), ic)
    return img.resize((W * 2, H * 2), Image.NEAREST)


def main():
    os.makedirs(OUT, exist_ok=True)
    for tag, fn, _ in CONCEPTS:
        fn(512).save(os.path.join(OUT, "v2_%s_512.png" % tag))
    sheet().save(os.path.join(OUT, "v2_sheet.png"))
    small_sheet().save(os.path.join(OUT, "v2_small.png"))
    print("ok")


if __name__ == "__main__":
    main()
