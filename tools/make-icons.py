#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ClipNest 剪巢 · Android 启动图标生成器（Pillow + numpy）

同一套视觉语言（与 Windows 原型 assets/app.ico 同源）：
  柔和蓝粉对角渐变（#9CC6FA -> #F7BAD4）+ 顶部柔光 + 右下暖晕 + 内缘高光
  三张半透明白玻璃卡片错位叠放（1px 高光边）+ 右下角蓝色小圆点

产物（默认写到 app/src/main/res）：
  mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png          48/72/96/144/192   legacy 圆角方块
  mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_round.png    48/72/96/144/192   圆形版（备用）
  mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png
                                                     108/162/216/324/432
                                                     自适应图标前景（108dp 画布，内容收在 66dp 安全区）
  tools/icon-preview.png                              审阅用对照图（不进 APK）

用法：
  python tools/make-icons.py                      # 只生成图标
  python tools/make-icons.py --preview-only       # 只看预览
  python tools/make-icons.py --preview path.png

设计要点：
  * 所有形状按 1024px 母版渲染，再 Lanczos 降采样，16px 级别仍保留轮廓。
  * legacy 图与自适应前景共用同一套几何参数，只有内容缩放比不同
    （自适应前景要落在 66dp 安全区内，所以整体缩到 0.68）。
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

# ---------------------------------------------------------------- palette
BG_TL = "#9CC6FA"      # 左上：柔和蓝
BG_BR = "#F7BAD4"      # 右下：柔和粉
BLUSH = "#F2A9C6"      # 右下暖晕
SHEEN = "#FFFFFF"      # 左上柔光
ACCENT = "#3B7BF6"     # 主色 / 小圆点
SHADOW_RGB = (58, 78, 118)

MASTER_TILE = 1024     # legacy 图母版
MASTER_FG = 1024       # 自适应前景母版

# 卡片几何（归一化到母版画布，未缩放前）
CARDS = [
    # cx,    cy,    w,     h,     r,     angle, fill_alpha, shadow_alpha
    (0.432, 0.372, 0.400, 0.258, 0.072, -13.0, 0.80, 0.30),
    (0.488, 0.452, 0.400, 0.258, 0.072, 0.0, 0.90, 0.34),
    (0.552, 0.540, 0.400, 0.258, 0.072, 13.0, 0.985, 0.42),
]
DOT_CENTER = (0.792, 0.782)
DOT_RADIUS = 0.050

LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

TILE_MARGIN = 0.045    # 圆角方块四周留白（透明）
SUPER_N = 4.6          # squircle 超椭圆指数
FG_CONTENT_SCALE = 0.68   # 自适应前景内容缩放（保证落在 66dp 安全区内）


# ---------------------------------------------------------------- color
def hex_rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], dtype=np.float32)


def srgb_to_linear(c):
    c = np.clip(c, 0.0, 255.0) / 255.0
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def linear_to_srgb(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ---------------------------------------------------------------- background
def gradient_fields(size):
    """返回 (rgb_linear, f, x, y)：线性光 RGB、超椭圆隐函数值、归一化坐标。"""
    yy, xx = np.mgrid[0:size, 0:size]
    x = xx.astype(np.float32) / (size - 1)
    y = yy.astype(np.float32) / (size - 1)

    half = 0.5 - TILE_MARGIN
    u = (x - 0.5) / half
    v = (y - 0.5) / half
    f = np.abs(u) ** SUPER_N + np.abs(v) ** SUPER_N      # =1 正好在 squircle 边上

    t = np.clip(((x + y) - 2.0 * TILE_MARGIN) / (2.0 - 4.0 * TILE_MARGIN), 0.0, 1.0)
    t = t * t * (3.0 - 2.0 * t)
    rgb = (srgb_to_linear(hex_rgb(BG_TL))[None, None, :] * (1.0 - t)[..., None]
           + srgb_to_linear(hex_rgb(BG_BR))[None, None, :] * t[..., None])

    sheen = np.exp(-((x - 0.28) ** 2 + (y - 0.12) ** 2) / (0.58 ** 2)).astype(np.float32) * 0.26
    rgb = rgb + (srgb_to_linear(hex_rgb(SHEEN))[None, None, :] - rgb) * sheen[..., None]

    blush = np.exp(-((x - 0.87) ** 2 + (y - 0.96) ** 2) / (0.55 ** 2)).astype(np.float32) * 0.22
    rgb = rgb * (1.0 - blush[..., None]) + srgb_to_linear(hex_rgb(BLUSH))[None, None, :] * blush[..., None]
    return rgb, f, x, y


def background_tile(size):
    """圆角方块底：渐变 + 内缘上高光/下暗边，alpha 用 squircle 遮罩。"""
    rgb, f, x, y = gradient_fields(size)
    band = np.clip(1.0 - f, 0.0, 1.0)
    edge = np.clip(1.0 - band / 0.075, 0.0, 1.0) ** 1.35
    hi = edge * smoothstep(0.92, 0.08, y) * 0.40
    rgb = rgb + (1.0 - rgb) * hi[..., None]
    lo = edge * smoothstep(0.28, 0.98, y) * 0.12
    rgb = rgb * (1.0 - lo[..., None]) + srgb_to_linear(hex_rgb("#5E6E90"))[None, None, :] * lo[..., None]

    mask = smoothstep(1.0 + 0.0022, 1.0 - 0.0022, f)
    rgba = np.dstack([linear_to_srgb(rgb) * 255.0, mask * 255.0]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA"), mask


def background_flat(size):
    """自适应图标背景（近似 drawable 里的线性+径向 gradient），无遮罩、满画布。"""
    rgb, f, x, y = gradient_fields(size)
    rgba = np.dstack([linear_to_srgb(rgb) * 255.0, np.full_like(x, 255.0)]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


# ---------------------------------------------------------------- shapes
def rounded_mask(w, h, box, radius, ss=4):
    img = Image.new("L", (w * ss, h * ss), 0)
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = [int(round(v * ss)) for v in box]
    d.rounded_rectangle([x0, y0, x1, y1], radius=int(round(radius * ss)), fill=255)
    return img.resize((w, h), Image.LANCZOS)


def card_layer(size, cx, cy, cw, ch, radius, angle, fill_alpha, shadow_alpha,
               shadow_blur, shadow_dx, shadow_dy):
    wpx, hpx, rpx = int(round(cw * size)), int(round(ch * size)), int(round(radius * size))
    pad = int(round(size * 0.14))
    w, h = wpx + 2 * pad, hpx + 2 * pad
    box = (pad, pad, pad + wpx - 1, pad + hpx - 1)

    shape = rounded_mask(w, h, box, rpx)
    card = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    card.putalpha(shape.point(lambda v: int(round(v * fill_alpha))))

    # 1px 玻璃高光边
    inner = shape.filter(ImageFilter.GaussianBlur(max(0.8, size * 0.0020)))
    ring = ImageChops.subtract(shape, inner)
    stroke = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    stroke.putalpha(ring.point(lambda v: int(round(v * 0.9))))
    card = Image.alpha_composite(card, stroke)

    shadow = Image.new("RGBA", (w, h), tuple(SHADOW_RGB) + (0,))
    shadow.putalpha(card.getchannel("A").point(lambda v: int(round(v * shadow_alpha))))
    shadow = shadow.filter(ImageFilter.GaussianBlur(shadow_blur))
    shifted = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    shifted.paste(shadow, (int(round(shadow_dx)), int(round(shadow_dy))))

    layer = Image.alpha_composite(shifted, card)
    if angle:
        layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    return layer


def dot_layer(size, cx, cy, r):
    rs = r * size
    pad = int(round(rs * 3.2))
    w = h = pad * 2
    ss = 4
    img = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = w * ss / 2.0
    ring = rs * 1.55
    d.ellipse([c - ring * ss, c - ring * ss, c + ring * ss, c + ring * ss], fill=(255, 255, 255, 165))
    d.ellipse([c - rs * ss, c - rs * ss, c + rs * ss, c + rs * ss],
              fill=tuple(int(v) for v in hex_rgb(ACCENT)) + (255,))
    return img.resize((w, h), Image.LANCZOS)


def content_layer(size, scale=1.0):
    """透明画布上叠三张卡片 + 小圆点，整体以画布中心缩放到 scale。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    def place(img, cx, cy):
        layer.alpha_composite(img, (int(round(cx * size - img.width / 2.0)),
                                    int(round(cy * size - img.height / 2.0))))

    for cx, cy, cw, ch, r, angle, fill, shadow in CARDS:
        scx = 0.5 + (cx - 0.5) * scale
        scy = 0.5 + (cy - 0.5) * scale
        img = card_layer(size, scx, scy, cw * scale, ch * scale, r * scale, angle,
                         fill, shadow,
                         shadow_blur=size * 0.011,
                         shadow_dx=size * 0.004 * scale, shadow_dy=size * 0.009 * scale)
        place(img, scx, scy)

    dcx = 0.5 + (DOT_CENTER[0] - 0.5) * scale
    dcy = 0.5 + (DOT_CENTER[1] - 0.5) * scale
    dot = dot_layer(size, dcx, dcy, DOT_RADIUS * scale)
    place(dot, dcx, dcy)
    return layer


# ---------------------------------------------------------------- compose
def render_tile(size):
    """legacy 启动图标：squircle 底 + 内容。"""
    img, mask = background_tile(size)
    content = content_layer(size, 1.0)
    img.alpha_composite(content)
    clip = Image.fromarray((np.clip(mask, 0.0, 1.0) * 255.0).astype(np.uint8), "L")
    img.putalpha(ImageChops.multiply(img.getchannel("A"), clip))
    return img


def render_round(size):
    """legacy 圆形图标：圆形底 + 略缩的内容。"""
    img, _ = background_tile(size)
    content = content_layer(size, 0.86)
    img.alpha_composite(content)
    circle = Image.new("L", (size, size), 0)
    ImageDraw.Draw(circle).ellipse([1, 1, size - 2, size - 2], fill=255)
    img.putalpha(ImageChops.multiply(img.getchannel("A"), circle))
    return img


def render_foreground(size):
    """自适应图标前景：透明底，内容缩到 66dp 安全区内。"""
    return content_layer(size, FG_CONTENT_SCALE)


def composite_adaptive(size, mask="circle"):
    """预览用：launcher 实际看到的效果。

    自适应层是 108dp 画布，系统只显示中央 72dp（=66.7%），再套形状遮罩。
    所以先按 108dp 渲染，再裁中央 72dp，最后加遮罩 —— 与真机一致。
    """
    full = int(round(size * 108.0 / 72.0))
    out = background_flat(full)
    out.alpha_composite(render_foreground(full))
    off = int(round((full - size) / 2.0))
    out = out.crop((off, off, off + size, off + size))

    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if mask == "circle":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif mask == "squircle":
        half = size / 2.0
        yy, xx = np.mgrid[0:size, 0:size]
        xx = (xx.astype(np.float32) + 0.5 - half) / half
        yy = (yy.astype(np.float32) + 0.5 - half) / half
        f = np.abs(xx) ** SUPER_N + np.abs(yy) ** SUPER_N
        m = Image.fromarray((np.clip(smoothstep(1.0 + 0.004, 1.0 - 0.004, f), 0, 1) * 255)
                            .astype(np.uint8), "L")
    else:  # rounded square：多数国产 launcher 的形状
        m = rounded_mask(size, size, (0, 0, size - 1, size - 1), size * 0.26)
    out.putalpha(ImageChops.multiply(out.getchannel("A"), m))
    return out

# ---------------------------------------------------------------- preview
def write_preview(path):
    """审阅对照图：真实尺寸、自适应三种遮罩、极小尺寸放大检查。"""
    from PIL import ImageFont

    W, H = 1560, 1260
    sheet = Image.new("RGBA", (W, H), (233, 234, 237, 255))
    sheet.alpha_composite(Image.new("RGBA", (W, 420), (255, 255, 255, 255)), (0, 400))
    sheet.alpha_composite(Image.new("RGBA", (W, 420), (241, 242, 245, 255)), (0, 820))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(20)
    except Exception:
        font = ImageFont.load_default()
    label_color = (92, 98, 112)

    def paste(img, x, y, label=""):
        sheet.alpha_composite(img, (int(x), int(y)))
        if label:
            draw.text((int(x), int(y) + img.height + 8), label, fill=label_color, font=font)

    # ---- strip 1: legacy 图标（真实像素）
    baseline = 300
    x = 40
    for s in (192, 144, 96, 72, 48):
        paste(render_tile(s), x, baseline - s, "%dpx" % s)
        x += max(s, 110) + 46
    paste(render_round(192), x, baseline - 192, "round 192")

    # ---- strip 2: 自适应图标（裁到 72dp 后套遮罩，贴近真机观感）+ 大图
    x = 40
    for mask, label in (("circle", "adaptive / circle"),
                        ("rounded", "adaptive / rounded"),
                        ("squircle", "adaptive / squircle")):
        paste(composite_adaptive(288, mask), x, 430, label)
        x += 340
    paste(render_tile(360), 1120, 430, "legacy tile 360")

    # ---- strip 3: 极小尺寸：真实 + 最近邻放大 5x
    x = 40
    for s in (16, 24, 32, 48):
        tiny = render_tile(s)
        paste(tiny, x, 850, "%dpx" % s)
        up = tiny.resize((s * 5, s * 5), Image.NEAREST)
        sheet.alpha_composite(up, (x, 950))
        x += max(s * 5, 120) + 40
    tiny_round = render_round(48)
    sheet.alpha_composite(tiny_round, (x + 30, 850))
    draw.text((x + 30, 850 + 48 + 8), "round 48", fill=label_color, font=font)
    sheet.alpha_composite(tiny_round.resize((240, 240), Image.NEAREST), (x + 30, 950))

    sheet.save(path)
    return path

# ---------------------------------------------------------------- main
def main(argv):
    ap = argparse.ArgumentParser()
    default_res = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                               "app", "src", "main", "res")
    ap.add_argument("--res-dir", default=default_res)
    ap.add_argument("--preview", default="")
    ap.add_argument("--preview-only", action="store_true")
    args = ap.parse_args(argv)

    res = args.res_dir
    if not os.path.isdir(res):
        print("make-icons: res dir not found: %s" % res, file=sys.stderr)
        return 2

    preview = args.preview or os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon-preview.png")

    if not args.preview_only:
        tile_master = render_tile(MASTER_TILE)
        round_master = render_round(MASTER_TILE)
        fg_master = render_foreground(MASTER_FG)
        written = []
        for dpi, s in LEGACY_SIZES.items():
            folder = os.path.join(res, "mipmap-" + dpi)

            os.makedirs(folder, exist_ok=True)
            tile_master.resize((s, s), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"), "PNG", optimize=True)
            round_master.resize((s, s), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_round.png"), "PNG", optimize=True)
            fs = FOREGROUND_SIZES[dpi]
            fg_master.resize((fs, fs), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_foreground.png"), "PNG", optimize=True)
            written.append((folder, s, fs))
        for folder, s, fs in written:
            print("  %-14s ic_launcher %dx%d  ic_launcher_round %dx%d  foreground %dx%d"
                  % (os.path.basename(folder), s, s, s, s, fs, fs))

    path = write_preview(preview)
    print("preview  %s" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))