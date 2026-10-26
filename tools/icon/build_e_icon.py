"""MImic app icon: the owner's "Glossy Neon Glass E Emblem" image -> every Android launcher asset.

    python tools/icon/build_e_icon.py

Source: docs/icon/source/mimic-e-emblem-source.png (the owner's image, unchanged). Nothing is
redrawn: the artwork is only scaled and centered on its ring so the round emblem survives every
launcher mask (circle, squircle, rounded square), on the image's own dark navy background.

Outputs (app/src/main/res):
  mipmap-*/ic_launcher.png, ic_launcher_round.png         legacy (48 dp) icons, API 24-25
  mipmap-*/ic_launcher_foreground.png                     adaptive foreground (108 dp)
  mipmap-*/ic_launcher_monochrome.png                     themed-icon (Android 13+) silhouette
  values/ic_launcher_background.xml                       adaptive background color
  docs/icon/mimic_icon_1024.png, mimic_icon_512.png       high-resolution master exports
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs", "icon", "source", "mimic-e-emblem-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# The glass ring in the source (measured on the 1254 px original): center and outer diameter.
SIZE = 1254
CENTER = (627, 610)
RING = 1105
# Adaptive canvas: 108 dp. The ring is scaled to 64 dp so it sits inside the 72 dp visible
# area of every launcher mask, glow included.
CANVAS = 1024
RING_DP = 64


def bg_color(img):
    # Navy from the image's corners (outside the ring's glow).
    pts = [(8, 8), (SIZE - 9, 8), (8, SIZE - 9), (SIZE - 9, SIZE - 9)]
    px = [img.getpixel(p) for p in pts]
    return tuple(sum(c[i] for c in px) // len(px) for i in range(3))


def adaptive_full(img, bg):
    """108 dp square: navy background + the emblem, the image's edges feathered into the navy."""
    scale = RING_DP / 108 * CANVAS / RING
    canvas = Image.new("RGB", (CANVAS, CANVAS), bg)
    art = img.resize((round(SIZE * scale), round(SIZE * scale)), Image.LANCZOS)
    ox = CANVAS // 2 - round(CENTER[0] * scale)
    oy = CANVAS // 2 - round(CENTER[1] * scale)
    mask = Image.new("L", art.size, 0)
    feather = 24
    ImageDraw.Draw(mask).rectangle((feather, feather, art.size[0] - feather, art.size[1] - feather), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    canvas.paste(art, (ox, oy), mask)
    return canvas


def monochrome(full):
    """White silhouette of the bright strokes (ring, E outline, waves, play button) on transparency."""
    ch = full.convert("RGB").split()
    bright = ImageChops.lighter(ImageChops.lighter(ch[0], ch[1]), ch[2])
    alpha = bright.point(lambda v: 0 if v < 205 else min(255, (v - 205) * 6))
    out = Image.new("RGBA", full.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def legacy_square(img):
    """The emblem on its own navy as a rounded square, on transparency."""
    side = 2 * min(CENTER[0], CENTER[1], SIZE - CENTER[0], SIZE - CENTER[1])
    x0, y0 = CENTER[0] - side // 2, CENTER[1] - side // 2
    tile = img.crop((x0, y0, x0 + side, y0 + side)).convert("RGBA")
    m = Image.new("L", tile.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, side - 1, side - 1), radius=round(side * 0.19), fill=255)
    tile.putalpha(m)
    return tile


def legacy_round(full):
    side = round(CANVAS * 72 / 108)
    off = (CANVAS - side) // 2
    c = full.crop((off, off, off + side, off + side)).convert("RGBA")
    m = Image.new("L", c.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, side - 1, side - 1), fill=255)
    c.putalpha(m)
    return c


def save(im, path, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.resize((px, px), Image.LANCZOS).save(path, optimize=True)


def main():
    img = Image.open(SRC).convert("RGB")
    assert img.size == (SIZE, SIZE), img.size
    bg = bg_color(img)
    full = adaptive_full(img, bg)
    mono = monochrome(full)
    square = legacy_square(img)
    rnd = legacy_round(full)
    for d, k in DENS.items():
        save(square, os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"), round(48 * k))
        save(rnd, os.path.join(RES, f"mipmap-{d}", "ic_launcher_round.png"), round(48 * k))
        save(full, os.path.join(RES, f"mipmap-{d}", "ic_launcher_foreground.png"), round(108 * k))
        save(mono, os.path.join(RES, f"mipmap-{d}", "ic_launcher_monochrome.png"), round(108 * k))
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n</resources>\n')
    docs = os.path.join(ROOT, "docs", "icon")
    save(square, os.path.join(docs, "mimic_icon_1024.png"), 1024)
    save(square, os.path.join(docs, "mimic_icon_512.png"), 512)
    full.save(os.path.join(docs, "mimic_icon_adaptive_full_1024.png"))
    print("background", "#%02X%02X%02X" % bg)


if __name__ == "__main__":
    main()
