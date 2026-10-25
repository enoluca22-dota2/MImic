"""MImic 4.5 app icon: the owner's neon-glass "E" image -> every Android launcher asset.

    python tools/icon/build_e_icon.py

Source: docs/icon/source/mimic-e-neon-source.png (the owner's image, unchanged). Nothing is
redrawn: the artwork is only cropped to its tile, scaled, and centered so it survives every
launcher mask (circle, squircle, rounded square), with the tile's own dark navy as background.

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
SRC = os.path.join(ROOT, "docs", "icon", "source", "mimic-e-neon-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# The rounded tile inside the source image (measured on the 1024 px original).
TILE = (72, 70, 952, 948)
# Inner part of the tile used for the adaptive layer (inside the tile's rounded corners).
INNER = (120, 112, 904, 904)
# The E's center in the source.
CENTER = (512, 510)
# Adaptive canvas: 108 dp. The E (about 650 px tall in the source) is scaled to ~51 dp so the
# whole letter stays inside every launcher mask (72 dp visible square, 66 dp safe circle).
CANVAS = 1024
E_SCALE = 0.74


def bg_color(img):
    # Tile navy, sampled away from the letter and its glow.
    pts = [(150, 160), (870, 160), (150, 860), (870, 860), (512, 150), (512, 880)]
    px = [img.getpixel(p) for p in pts]
    return tuple(sum(c[i] for c in px) // len(px) for i in range(3))


def adaptive_full(img, bg):
    """108 dp square: navy background + the tile's inner artwork, edges feathered into the navy."""
    canvas = Image.new("RGB", (CANVAS, CANVAS), bg)
    inner = img.crop(INNER)
    w, h = inner.size
    inner = inner.resize((round(w * E_SCALE), round(h * E_SCALE)), Image.LANCZOS)
    # Place so the E's center lands on the canvas center.
    ox = CANVAS // 2 - round((CENTER[0] - INNER[0]) * E_SCALE)
    oy = CANVAS // 2 - round((CENTER[1] - INNER[1]) * E_SCALE)
    mask = Image.new("L", inner.size, 0)
    feather = 46
    ImageDraw.Draw(mask).rounded_rectangle((feather, feather, inner.size[0] - feather, inner.size[1] - feather), radius=90, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    canvas.paste(inner, (ox, oy), mask)
    return canvas


def monochrome(full):
    """White silhouette of the bright strokes (E outline, waves, play button) on transparency."""
    gray = full.convert("RGB").split()
    bright = ImageChops.lighter(ImageChops.lighter(gray[0], gray[1]), gray[2])
    alpha = bright.point(lambda v: 0 if v < 120 else min(255, (v - 120) * 3))
    out = Image.new("RGBA", full.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def legacy_square(img):
    """The original tile itself (with its own rounded corners) on transparency."""
    tile = img.crop(TILE).convert("RGBA")
    m = Image.new("L", tile.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, tile.size[0] - 1, tile.size[1] - 1), radius=round(tile.size[0] * 0.19), fill=255)
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
    assert img.size == (1024, 1024), img.size
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
