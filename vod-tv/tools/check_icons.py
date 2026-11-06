# -*- coding: utf-8 -*-
"""
图标自检：所有 ic_ui_*.png 必须是「居中 + 尺寸合理」的。

为什么需要：fetch_material_icons.py 走的是 svg → PDF → pdfium 光栅化这条链路，
PDF 页面比图形 bbox 大，字形会被画到画布左下角（实测 96px 画布偏 (-12,+12)）。
偏移 1/8 画幅后，图标在按钮里明显往左下歪、右上留白，小字（比如 replay_10
里的「10」）被挤到看不清，还容易被当成别的图标。人眼很难一眼看出「偏了 3dp」，
所以做成机械检查。

判定标准（以 drawable-xxhdpi 为基准，其余密度按比例）：
  1. 字形 bbox 中心与画布中心的偏差 ≤ 2% 画幅（72px 上约 1.5px）
  2. 字形最长边占画幅 40%~92%（太小说明字形被缩过头，太大说明贴边了）

用法：python tools/check_icons.py
退出码非 0 表示有图标不合格。
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# 各密度同一套图，抽最大的两档看就够；mdpi 太小，量化误差会误报
CHECK_DIRS = ["drawable-xxhdpi", "drawable-xhdpi"]

MIN_FILL = 0.40
MAX_FILL = 0.92
MAX_OFFSET = 0.02


def check_one(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    bb = im.split()[3].getbbox()
    if not bb:
        return "字形全透明"

    bw, bh = bb[2] - bb[0], bb[3] - bb[1]
    cx = (bb[0] + bb[2]) / 2.0
    cy = (bb[1] + bb[3]) / 2.0
    off_x = abs(cx - w / 2.0) / w
    off_y = abs(cy - h / 2.0) / h
    fill = max(bw, bh) / float(max(w, h))

    problems = []
    if off_x > MAX_OFFSET or off_y > MAX_OFFSET:
        problems.append("偏心 %.1f%%/%.1f%%（上限 %.0f%%）"
                        % (off_x * 100, off_y * 100, MAX_OFFSET * 100))
    if fill < MIN_FILL:
        problems.append("字形只占 %.0f%%（下限 %.0f%%）" % (fill * 100, MIN_FILL * 100))
    if fill > MAX_FILL:
        problems.append("字形占 %.0f%%（上限 %.0f%%，贴边了）" % (fill * 100, MAX_FILL * 100))
    return "; ".join(problems)


def main():
    total = 0
    bad = []
    for d in CHECK_DIRS:
        folder = os.path.join(RES, d)
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            if not (name.startswith("ic_ui_") and name.endswith(".png")):
                continue
            total += 1
            msg = check_one(os.path.join(folder, name))
            if msg:
                bad.append("%s/%s: %s" % (d, name, msg))

    if bad:
        print("图标自检不通过（%d 项）：" % len(bad))
        for b in bad:
            print("  " + b)
        sys.exit(1)
    print("OK: %d 个图标全部居中、尺寸合规" % total)


if __name__ == "__main__":
    main()
