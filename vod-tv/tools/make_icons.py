# -*- coding: utf-8 -*-
"""
生成「看看」的图标与 TV 横幅。

造型：蓝底 + 纯白播放三角。参照夸克 / UU远程 / 飞牛这一类的共同规律——
纯色底、单色巨大几何图形、无渐变无细节。细节越少，小尺寸越干净。

全部用几何图形画在 8 倍画布上再 LANCZOS 缩小，保证 48px 也边缘锐利。
用法：python make_icons.py
产物写入 ../app/src/main/res/ 对应目录。
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

BLUE = (43, 92, 255)        # #2B5CFF，与夸克同一色域
BLUE_TOP = (58, 116, 255)
BLUE_BOT = (24, 60, 214)
WHITE = (255, 255, 255)

FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"
FONT_REG = "C:/Windows/Fonts/msyh.ttc"

# 三角几何参数（相对画布边长）。dx 为光学居中：三角视觉重心偏左，右移一点才显得居中
TRI_W, TRI_H, TRI_DX, TRI_ROUND = 0.345, 0.425, 0.020, 0.016
CORNER = 0.225              # 圆角方块半径


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
    """高斯模糊后二值化，等效把尖角磨圆，半径≈r"""
    if r <= 0:
        return mask
    return mask.filter(ImageFilter.GaussianBlur(r * 0.55)).point(
        lambda v: 255 if v > 127 else 0)


def tri_mask(size, scale=1.0):
    """指向右的播放三角，scale<1 用于自适应图标的 72dp 安全区"""
    S = size
    m = Image.new("L", (S, S), 0)
    tw, th = S * TRI_W * scale, S * TRI_H * scale
    cx, cy = S / 2 + S * TRI_DX * scale, S / 2
    ImageDraw.Draw(m).polygon(
        [(cx - tw / 2, cy - th / 2), (cx - tw / 2, cy + th / 2), (cx + tw / 2, cy)], fill=255)
    return soften(m, S * TRI_ROUND * scale)


def paste(base, mask, color, alpha=255):
    layer = Image.new("RGBA", base.size, color + (0,))
    layer.putalpha(mask.point(lambda v: v * alpha // 255))
    base.alpha_composite(layer)
    return base


def make_launcher(size, S=1024):
    """非自适应图标：蓝色圆角方块 + 白色三角"""
    base = Image.new("RGBA", (S, S), BLUE + (255,))
    base = paste(base, tri_mask(S), WHITE)
    mask = rounded_mask(S, S, int(S * CORNER))
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def make_foreground(size, S=1024):
    """自适应图标前景：只画白色三角。108dp 画布的 72dp 安全区能装下 1.0 尺寸，不用再缩"""
    tri = Image.new("RGBA", (S, S), WHITE + (0,))
    tri.putalpha(tri_mask(S, 1.0))
    return tri.resize((size, size), Image.LANCZOS)


def soft_spot(img, box, color, blur, alpha):
    """模糊椭圆光斑，避免硬边色块"""
    w, h = img.size
    layer = Image.new("L", (w, h), 0)
    ImageDraw.Draw(layer).ellipse(box, fill=255)
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    if alpha < 1.0:
        layer = layer.point(lambda v: int(v * alpha))
    return Image.composite(Image.new("RGBA", (w, h), color + (255,)), img, layer)


def make_banner(w, h):
    """TV 横幅：蓝色渐变底 + 白色三角 + 标题，与图标同一套语言"""
    S, H = w * 4, h * 4
    img = vgrad(S, H, BLUE_TOP, BLUE_BOT)

    # 右上角提亮，避免大面积纯色显得死
    img = soft_spot(img, [S * 0.45, -H * 0.9, S * 1.25, H * 1.5], (120, 165, 255), H * 0.22, 0.35)

    # 左侧白色三角
    mark_size = int(H * 0.50)
    mark = tri_mask(mark_size)
    mx = int(H * 0.20)
    white_tri = Image.new("RGBA", (mark_size, mark_size), WHITE + (0,))
    white_tri.putalpha(mark)
    img.alpha_composite(white_tri, (mx, (H - mark_size) // 2))

    d = ImageDraw.Draw(img)
    tx = mx + mark_size + int(H * 0.12)
    right = S - int(H * 0.10)

    t_size = int(H * 0.32)
    f1 = ImageFont.truetype(FONT_BOLD, t_size)
    t_y = int(H * 0.17)
    d.text((tx, t_y), "看看", font=f1, fill=WHITE)

    # 标题下分隔线（半透明白，比彩色强调线更克制）
    line_y = t_y + int(t_size * 1.06) + int(H * 0.035)
    d.rectangle([tx + 4, line_y, tx + 4 + int(H * 0.26), line_y + max(3, int(H * 0.012))],
                fill=(255, 255, 255, 150))

    # 副标题：从大到小试，保证不越界
    sub = "影视热播 · 点开即看"
    s_size = int(H * 0.10)
    f2 = ImageFont.truetype(FONT_REG, s_size)
    while s_size > 10:
        f2 = ImageFont.truetype(FONT_REG, s_size)
        if d.textlength(sub, font=f2) <= right - tx:
            break
        s_size -= 3
    d.text((tx + 4, line_y + int(H * 0.055)), sub, font=f2, fill=(228, 236, 255))

    return img.resize((w, h), Image.LANCZOS).convert("RGB")


def ensure(path):
    os.makedirs(path, exist_ok=True)


def main():
    # 传统图标：mdpi 48 ~ xxxhdpi 192
    for bucket, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                       ("xxhdpi", 144), ("xxxhdpi", 192)]:
        p = os.path.join(RES, "mipmap-" + bucket)
        ensure(p)
        make_launcher(px).save(os.path.join(p, "ic_launcher.png"), optimize=True)
        print("ic_launcher.png", bucket, px)

    # 自适应图标前景（108dp 内容区）
    for bucket, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                       ("xxhdpi", 324), ("xxxhdpi", 432)]:
        p = os.path.join(RES, "mipmap-" + bucket)
        ensure(p)
        make_foreground(px).save(os.path.join(p, "ic_launcher_foreground.png"), optimize=True)
        print("ic_launcher_foreground.png", bucket, px)

    # TV 横幅
    for bucket, size in [("mdpi", (320, 180)), ("xhdpi", (640, 360)), ("xxhdpi", (960, 540))]:
        p = os.path.join(RES, "drawable-" + bucket)
        ensure(p)
        make_banner(*size).save(os.path.join(p, "tv_banner.png"), optimize=True)
        print("tv_banner.png", bucket, size)


if __name__ == "__main__":
    main()
