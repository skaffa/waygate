"""Generates the Waygate icon as 32x32 pixel art, in the Gauge / Ortho icon style.

Style: dark rounded-square badge (#10161C) with 4-px stepped corners, soft radial glow, cream 4-point
sparkles. Subject: an obsidian nether portal frame with a swirling purple portal inside, and a gold
arrow through it pointing at a small destination marker (where the portal leads).

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128),
       and src/main/resources/assets/waygate/icon.png (128)
"""
import math
import os
import sys

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (44, 22, 64)
OBS = (34, 24, 52, 255)
OBS_HI = (70, 52, 104, 255)
OBS_LO = (18, 12, 28, 255)
P_DARK = (96, 20, 170)
P_LIGHT = (200, 110, 255)
EYE = (30, 6, 50, 255)
EYE_HI = (255, 240, 255, 255)
ARROW = (255, 214, 90, 255)
ARROW_HI = (255, 246, 190, 255)
PIN = (255, 120, 90, 255)
SPARK = (255, 236, 140)


def blend(dst, src):
    sa = src[3] / 255
    da = dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa == 0:
        return (0, 0, 0, 0)
    c = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return c + (round(oa * 255),)


def corner_cut(x, y):
    steps = [4, 3, 2, 1]
    for yy, xx in ((y, x), (N - 1 - y, x), (y, N - 1 - x), (N - 1 - y, N - 1 - x)):
        if yy < 4 and xx < steps[yy]:
            return True
    return False


def main(out_dirs):
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N and not corner_cut(x, y):
            px[y][x] = blend(px[y][x], c) if c[3] < 255 else c

    for y in range(N):
        for x in range(N):
            if corner_cut(x, y):
                continue
            d = math.hypot(x + 0.5 - 14, y + 0.5 - 16) / 15
            t = max(0.0, 1 - d) ** 1.6
            px[y][x] = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3)) + (255,)

    # portal frame: outer x 5..22, y 4..28, 3 px thick obsidian
    x0, x1, y0, y1, th = 5, 22, 4, 28, 3
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            inner = x0 + th <= x <= x1 - th and y0 + th <= y <= y1 - th
            if inner:
                cx, cy = (x0 + x1) / 2 + 0.5, (y0 + y1) / 2 + 0.5
                fx, fy = x + 0.5 - cx, y + 0.5 - cy
                ang = math.atan2(fy, fx)
                r = math.hypot(fx, fy * 0.7)
                s = 0.5 + 0.5 * math.sin(ang * 2 + r * 1.1)
                c = tuple(round(P_DARK[i] + (P_LIGHT[i] - P_DARK[i]) * s) for i in range(3))
                put(x, y, c + (255,))
            else:
                c = OBS
                if (x * 7 + y * 3) % 11 == 0:
                    c = OBS_HI
                elif (x * 5 + y * 9) % 13 == 0:
                    c = OBS_LO
                put(x, y, c)
    # bevel highlight on the frame's top-left edges
    for x in range(x0, x1 + 1):
        put(x, y0, OBS_HI)
    for y in range(y0, y1 + 1):
        put(x0, y, OBS_HI)

    # cute face in the portal
    for ex in (11, 16):
        for dy in range(3):
            put(ex, 15 + dy, EYE)
        put(ex, 15, EYE_HI)

    # gold arrow leaving the portal toward a destination pin (right side)
    for x in range(17, 28):
        put(x, 23, ARROW)
        put(x, 22, ARROW_HI if x % 2 == 0 else ARROW)
    for i in range(4):
        put(27 - i, 22 - i - 1, ARROW)
        put(27 - i, 23 + i + 1, ARROW)
    # destination pin above the arrow tip
    for y, w in ((8, 1), (9, 2), (10, 2), (11, 1), (12, 0)):
        for dx in range(-w, w + 1):
            put(27 + dx, y, PIN)
    put(27, 9, EYE_HI)

    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y + dy, SPARK + (a,))

    sparkle(27, 3 + 13, strong=False)
    sparkle(26, 28)

    img = Image.new("RGBA", (N, N))
    img.putdata([c for row in px for c in row])
    media, asset = out_dirs
    os.makedirs(media, exist_ok=True)
    os.makedirs(asset, exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(media, "icon.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(media, "icon-128.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(asset, "icon.png"))
    print("wrote", media, asset)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main((os.path.join(root, "media"), os.path.join(root, "src", "main", "resources", "assets", "waygate")))
