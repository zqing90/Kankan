# -*- coding: utf-8 -*-
"""第三轮：定稿候选。只出预览。"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = "D:/tools/_dl"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

BLUE = (43, 92, 255)
BLUE_TOP = (66, 128, 255)
BLUE_BOT = (26, 66, 226)
WHITE = (255, 255, 255)


def lerp(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def vgrad(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        d.line([(0, y), (w, y)], fill=lerp(top, bottom, y / max(1, h - 1)))
    return img.convert("RGBA")


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


def paste(base, mask, color, alpha=255):
    layer = Image.new("RGBA", base.size, color + (0,))
    layer.putalpha(mask.point(lambda v: v * alpha // 255))
    base.alpha_composite(layer)
    return base


def canvas(S, color=BLUE):
    return Image.new("RGBA", (S, S), color + (255,))


def finish(base, size, corner=0.225):
    S = base.size[0]
    mask = rounded_mask(S, S, int(S * corner))
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- A3 极简
def c_a(size, S=1024):
    """蓝底 + 纯白三角。基准线，最能打但偏通用。"""
    img = canvas(S)
    return finish(paste(img, tri_mask(S, 0.345, 0.425, dx=0.020, round_r=0.016), WHITE), size)


# ---------------------------------------------------------------- B3 播放中
def c_b(size, S=1024):
    """三角 + 进度条。三角与进度条共用同一视觉中轴，读作「正在播放」。"""
    img = canvas(S)
    dx = 0.016
    img = paste(img, tri_mask(S, 0.300, 0.365, dx=dx, dy=-0.062, round_r=0.014), WHITE)

    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    bw, bh = S * 0.46, S * 0.056
    cx = S / 2 + S * dx
    bx = cx - bw / 2
    by = S * 0.635
    r = bh / 2
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=r, fill=80)
    d.rounded_rectangle([bx, by, bx + bw * 0.58, by + bh], radius=r, fill=255)
    img = paste(img, m, WHITE)
    return finish(img, size)


# ---------------------------------------------------------------- G 描边屏幕
def c_g(size, S=1024):
    """白色粗描边圆角屏 + 实心三角。线条风，接近飞牛那种质感。"""
    img = canvas(S)
    m = Image.new("L", (S * 2, S * 2), 0)
    d = ImageDraw.Draw(m)
    rw, rh = S * 0.78, S * 0.62
    t = S * 0.070
    c = S
    x0, y0 = c - rw / 2, c - rh / 2
    d.rounded_rectangle([x0, y0, x0 + rw, y0 + rh], radius=int(S * 0.145), fill=255)
    d.rounded_rectangle([x0 + t, y0 + t, x0 + rw - t, y0 + rh - t],
                        radius=int(S * 0.145 - t), fill=0)
    frame = m.resize((S, S), Image.LANCZOS)
    img = paste(img, frame, WHITE)
    img = paste(img, tri_mask(S, 0.20, 0.245, dx=0.010, round_r=0.010), WHITE)
    return finish(img, size)


# ---------------------------------------------------------------- H 渐变
def c_h(size, S=1024):
    """顶部提亮的微渐变蓝 + 白三角。比纯色多一点体积感，仍属扁平。"""
    img = vgrad(S, S, BLUE_TOP, BLUE_BOT)
    return finish(paste(img, tri_mask(S, 0.345, 0.425, dx=0.020, round_r=0.016), WHITE), size)


CONCEPTS = [
    ("A", c_a, "纯色底 · 最极简"),
    ("B", c_b, "三角+进度条 · 影视"),
    ("G", c_g, "描边屏 · 线条风"),
    ("H", c_h, "微渐变 · 有体积"),
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
        d.text((x + 24, pad + cell + 16), desc, font=f2, fill=(150, 156, 168))
    return img


def small(cell=76, pad=24):
    n = len(CONCEPTS)
    W = pad + n * (cell + pad)
    H = cell + pad * 2
    img = Image.new("RGB", (W, H), (24, 25, 28))
    for i, (_, fn, _) in enumerate(CONCEPTS):
        ic = fn(cell)
        img.paste(ic, (pad + i * (cell + pad), pad), ic)
    return img.resize((W * 3, H * 3), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    for tag, fn, _ in CONCEPTS:
        fn(512).save(os.path.join(OUT, "v3_%s_512.png" % tag))
    sheet().save(os.path.join(OUT, "v3_sheet.png"))
    small().save(os.path.join(OUT, "v3_small.png"))
    print("ok")


if __name__ == "__main__":
    main()
