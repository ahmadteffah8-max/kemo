"""Generates ORIGINA / kemo pixel-art sprites (64x80) and launcher icons."""
import os, sys
from PIL import Image, ImageDraw
import numpy as np

W, H = 64, 80
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
RES = os.path.join(OUT, "app/src/main/res")

HAIR   = (25, 211, 197, 255)
HAIR_D = (12, 150, 154, 255)
HAIR_L = (140, 250, 238, 255)
SKIN   = (255, 232, 220, 255)
SKIN_S = (240, 196, 184, 255)
DARK   = (20, 32, 52, 255)
DARK2  = (16, 28, 46, 255)
CYAN   = (61, 242, 255, 255)
PINK   = (255, 110, 199, 255)
OUTL   = (4, 44, 56, 255)
IRIS   = (31, 229, 240, 255)
IRIS_P = (255, 110, 199, 255)
RING   = (170, 255, 248, 255)
WHITE  = (255, 255, 255, 255)

def mirror(pts):
    return [(W - 1 - x, y) for x, y in pts]

def braid(d, cx):
    for i, y in enumerate(range(36, 72, 6)):
        wob = 1 if i % 2 else -1
        w = 4 if i < 4 else 3
        col = HAIR if i % 2 == 0 else HAIR_D
        d.ellipse((cx + wob - w, y, cx + wob + w, y + 8), fill=col)
        d.point((cx + wob - 1, y + 2), fill=HAIR_L)
    d.rectangle((cx - 3, 70, cx + 3, 72), fill=PINK)
    d.polygon([(cx - 3, 73), (cx + 3, 73), (cx + 1, 78), (cx - 1, 78)], fill=HAIR)

def eye(d, cx, cy, mode):
    if mode == "open":
        d.ellipse((cx - 5, cy - 6, cx + 5, cy + 6), fill=RING, outline=OUTL)
        d.ellipse((cx - 4, cy - 5, cx + 4, cy + 5), fill=IRIS)
        d.pieslice((cx - 4, cy - 5, cx + 4, cy + 5), 25, 155, fill=IRIS_P)
        d.pieslice((cx - 4, cy - 5, cx + 4, cy + 5), 45, 135, fill=(255, 170, 225, 255))
        d.rectangle((cx - 1, cy - 2, cx + 1, cy + 2), fill=(10, 42, 58, 255))
        d.rectangle((cx - 3, cy - 4, cx - 2, cy - 3), fill=WHITE)
        d.point((cx + 2, cy + 3), fill=WHITE)
        d.line((cx - 6, cy - 6, cx + 5, cy - 6), fill=OUTL, width=2)
        d.point((cx + (6 if cx > 32 else -7), cy - 7), fill=OUTL)
    elif mode == "blink":
        d.line((cx - 5, cy + 1, cx + 5, cy + 1), fill=OUTL, width=2)
    else:  # happy ^ ^
        d.arc((cx - 5, cy - 3, cx + 5, cy + 8), 200, 340, fill=OUTL, width=2)

def sprite(mode="open", mouth="smile"):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # back hair mass
    d.ellipse((9, 8, 55, 58), fill=HAIR_D)
    # neck
    d.rectangle((28, 50, 36, 60), fill=SKIN_S)
    # body / futuristic outfit
    d.polygon([(8, 80), (13, 65), (26, 58), (38, 58), (51, 65), (56, 80)], fill=DARK2)
    d.polygon([(26, 58), (32, 67), (38, 58)], fill=SKIN)
    d.line([(26, 58), (32, 67), (38, 58)], fill=CYAN, width=1)
    d.line([(13, 65), (26, 58)], fill=CYAN, width=1)
    d.line([(51, 65), (38, 58)], fill=CYAN, width=1)
    d.rectangle((31, 68, 33, 70), fill=PINK)
    for pts in ([(18, 74), (24, 74), (27, 78)], mirror([(18, 74), (24, 74), (27, 78)])):
        d.line(pts, fill=CYAN, width=1)
    d.point((22, 70), fill=PINK); d.point((41, 70), fill=PINK)
    # braids (hang over shoulders)
    braid(d, 8); braid(d, 55)
    # face
    d.ellipse((17, 18, 47, 52), fill=SKIN)
    d.polygon([(18, 38), (46, 38), (41, 51), (32, 54), (23, 51)], fill=SKIN)
    d.line([(22, 50), (32, 53), (42, 50)], fill=SKIN_S, width=1)
    # bangs
    bangs = [(15, 34), (14, 22), (20, 12), (32, 9), (44, 12), (50, 22), (49, 34),
             (46, 27), (43, 32), (40, 23), (36, 30), (32, 22), (28, 30), (24, 23), (21, 32), (18, 27)]
    d.polygon(bangs, fill=HAIR)
    d.line([(21, 15), (27, 12), (35, 11)], fill=HAIR_L, width=2)
    d.line([(38, 14), (43, 16)], fill=HAIR_L, width=1)
    for x0 in (28, 36, 24):
        d.line([(x0, 28), (x0, 24)], fill=HAIR_D, width=1)
    # side locks
    lock = [(14, 24), (19, 27), (19, 45), (16, 51), (13, 45)]
    d.polygon(lock, fill=HAIR); d.polygon(mirror(lock), fill=HAIR)
    d.line([(16, 30), (16, 46)], fill=HAIR_D, width=1)
    d.line([(47, 30), (47, 46)], fill=HAIR_D, width=1)
    # star hair clips
    for sx in (13, 50):
        d.rectangle((sx, 17, sx + 2, 19), fill=PINK); d.point((sx + 1, 16), fill=WHITE)
    # headphones
    d.arc((9, 5, 55, 51), 188, 352, fill=DARK, width=4)
    d.arc((11, 7, 53, 49), 192, 348, fill=CYAN, width=1)
    for x0 in (4, 50):
        d.rounded_rectangle((x0, 24, x0 + 9, 42), radius=3, fill=DARK)
        d.rounded_rectangle((x0 + 2, 27, x0 + 7, 39), radius=2, outline=CYAN)
        d.point((x0 + 4, 33), fill=PINK); d.point((x0 + 4, 30), fill=CYAN); d.point((x0 + 4, 36), fill=CYAN)
    # eyes
    eye(d, 24, 38, mode); eye(d, 40, 38, mode)
    # blush + mouth
    d.rectangle((19, 43, 22, 44), fill=(255, 150, 180, 255))
    d.rectangle((42, 43, 45, 44), fill=(255, 150, 180, 255))
    if mouth == "smile":
        d.arc((29, 42, 35, 48), 20, 160, fill=(200, 70, 110, 255), width=1)
    else:
        d.pieslice((28, 42, 36, 50), 0, 180, fill=(176, 48, 96, 255))
        d.rectangle((30, 47, 33, 48), fill=(255, 130, 160, 255))
    # outline pass
    a = np.array(im)
    alpha = a[:, :, 3] > 0
    pad = np.pad(alpha, 1)
    near = pad[:-2, 1:-1] | pad[2:, 1:-1] | pad[1:-1, :-2] | pad[1:-1, 2:]
    edge = near & ~alpha
    a[edge] = OUTL
    return Image.fromarray(a)

def main():
    dr = os.path.join(RES, "drawable-nodpi")
    os.makedirs(dr, exist_ok=True)
    frames = {"sprite_open": sprite("open"), "sprite_blink": sprite("blink"),
              "sprite_happy": sprite("happy", "open")}
    for name, im in frames.items():
        im.save(os.path.join(dr, name + ".png"))
    # preview sheet
    sheet = Image.new("RGBA", (W * 6 * 3 + 40, H * 6 + 20), (0, 0, 0, 255))
    for i, im in enumerate(frames.values()):
        sheet.paste(im.resize((W * 6, H * 6), Image.NEAREST), (10 + i * (W * 6 + 10), 10),
                    im.resize((W * 6, H * 6), Image.NEAREST))
    sheet.convert("RGB").save(os.path.join(OUT, "tools", "preview_sprites.png"))
    # launcher icons
    base = Image.new("RGBA", (192, 192), (3, 8, 14, 255))
    d = ImageDraw.Draw(base)
    sp = frames["sprite_open"].resize((W * 3, H * 3), Image.NEAREST)
    base.paste(sp, ((192 - W * 3) // 2, -6), sp)
    d.rounded_rectangle((3, 3, 188, 188), radius=34, outline=CYAN, width=4)
    d.line((30, 186, 70, 186), fill=PINK, width=4); d.line((122, 186, 162, 186), fill=PINK, width=4)
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        p = os.path.join(RES, "mipmap-" + folder)
        os.makedirs(p, exist_ok=True)
        base.resize((px, px), Image.LANCZOS).save(os.path.join(p, "ic_launcher.png"))
    base.save(os.path.join(OUT, "tools", "preview_icon.png"))

main()
