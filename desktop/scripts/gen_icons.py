#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Tauri 打包图标（暖色浅色风格）：
icon.png(1024) / 32x32.png / 128x128.png / 128x128@2x.png(256) / icon.ico(多尺寸)
风格：米底圆角方形渐变 + 左侧深褐任务线 + 右侧金色对勾（对应 UI 的 F5EFE0/3A2E1A/C9A227）
"""
import os
from PIL import Image, ImageDraw

S = 1024  # 主画布
RADIUS = 180  # 圆角
INK = (58, 46, 26, 255)      # #3A2E1A 深褐
GOLD = (201, 162, 39, 255)   # #C9A227 金
BG_TOP = (252, 247, 235, 255)     # #FCF7EB
BG_BOTTOM = (241, 230, 200, 255)  # #F1E6C8 深一点的米（底部微暗增加层次）

def gradient_bg(w, h, c_top, c_bot):
    """垂直渐变背景"""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        r = int(c_top[0] + (c_bot[0] - c_top[0]) * t)
        g = int(c_top[1] + (c_bot[1] - c_top[1]) * t)
        b = int(c_top[2] + (c_bot[2] - c_top[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b, 255)
    return img

def round_corners(img, radius):
    """按半径圆角（全画布）"""
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def round_cap_line(d, p1, p2, width, color):
    """带圆头端点的粗线（Pillow line 无 round cap，用端点圆补丁）"""
    d.line([p1, p2], fill=color, width=width)
    r = width // 2
    for (x, y) in (p1, p2):
        d.ellipse([x - r, y - r, x + r, y + r], fill=color)

def main():
    # 背景渐变 + 圆角
    img = gradient_bg(S, S, BG_TOP, BG_BOTTOM)
    img = round_corners(img, RADIUS)
    d = ImageDraw.Draw(img)

    # 左侧三条任务线（深褐，圆头，逐条错落）
    lw = 54
    r = lw // 2
    lines = [(250, 372, 600), (250, 512, 706), (250, 652, 556)]
    for (x1, y, x2) in lines:
        d.line([x1, y, x2, y], fill=INK, width=lw)
        for cx in (x1, x2):
            d.ellipse([cx - r, y - r, cx + r, y + r], fill=INK)

    # 右侧金色对勾（圆头折线）
    gw = 84
    gr = gw // 2
    pts = [(646, 522), (726, 602), (866, 438)]
    d.line([pts[0], pts[1]], fill=GOLD, width=gw, joint="curve")
    d.line([pts[1], pts[2]], fill=GOLD, width=gw, joint="curve")
    # 端点圆补丁（对勾两端 + 折点已有 joint 处理，但仍补一下保险）
    for (x, y) in (pts[0], pts[2]):
        d.ellipse([x - gr, y - gr, x + gr, y + gr], fill=GOLD)

    # 输出目录
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")
    os.makedirs(out_dir, exist_ok=True)

    # icon.png = 1024 主图
    img.save(os.path.join(out_dir, "icon.png"))

    # 常规尺寸
    for name, size in [("32x32.png", 32), ("128x128.png", 128), ("128x128@2x.png", 256)]:
        img.resize((size, size), Image.LANCZOS).save(os.path.join(out_dir, name))

    # icon.ico 多尺寸（16~256）
    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    img.resize((256, 256), Image.LANCZOS).save(
        os.path.join(out_dir, "icon.ico"),
        format="ICO", sizes=ico_sizes
    )

    print("生成完成：")
    for f in sorted(os.listdir(out_dir)):
        p = os.path.join(out_dir, f)
        print(f"  {f}  ({os.path.getsize(p)} bytes)")

if __name__ == "__main__":
    main()
