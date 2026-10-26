"""MImic app icon: the owner's brushed-metal "E" tile image -> every Android launcher asset.

    python tools/icon/build_e_icon.py

Source: docs/icon/source/mimic-e-metal-source.png (the owner's image "123", unchanged). Nothing is
redrawn: the artwork is only scaled and centered on its tile so the rounded tile survives every
launcher mask (circle, squircle, rounded square), on the image's own dark navy gradient.

Outputs (app/src/main/res):
  mipmap-*/ic_launcher.png, ic_launcher_round.png         legacy (48 dp) icons, API 24-25
  mipmap-*/ic_launcher_foreground.png                     adaptive foreground (108 dp)
  mipmap-*/ic_launcher_monochrome.png                     themed-icon (Android 13+) silhouette
  values/ic_launcher_background.xml                       adaptive background color
  docs/icon/mimic_icon_1024.png, mimic_icon_512.png       high-resolution master exports
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs", "icon", "source", "mimic-e-metal-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# The metal tile in the source (measured on the 1024 px original): bounds and corner radius.
SIZE = 1024
TILE = (163, 158, 861, 864)
TILE_RADIUS = 168
CENTER = ((TILE[0] + TILE[2]) // 2, (TILE[1] + TILE[3]) // 2)
# Adaptive canvas: 108 dp. The tile is scaled to 52 dp wide; with its rounded corners that keeps
# it inside the 72 dp circle mask (the tightest launcher shape), shadow included.
CANVAS = 1024
TILE_DP = 52


def bg_color(img):
    # Average of the navy gradient along the image's edges (outside the tile).
    pts = [(8, 8), (SIZE - 9, 8), (8, SIZE - 9), (SIZE - 9, SIZE - 9), (8, SIZE // 2), (SIZE - 9, SIZE // 2)]
    px = [img.getpixel(p) for p in pts]
    return tuple(sum(c[i] for c in px) // len(px) for i in range(3))


def backdrop(img):
    """The source's own top-to-bottom navy gradient (its left/right edge columns), full canvas."""
    edge = Image.blend(img.crop((0, 0, 16, SIZE)).resize((1, SIZE), Image.LANCZOS),
                       img.crop((SIZE - 16, 0, SIZE, SIZE)).resize((1, SIZE), Image.LANCZOS), 0.5)
    return edge.resize((CANVAS, CANVAS), Image.BICUBIC)


def adaptive_full(img):
    """108 dp square: the source gradient + the scaled artwork, its edges feathered into it."""
    scale = TILE_DP / 108 * CANVAS / (TILE[2] - TILE[0])
    canvas = backdrop(img)
    art = img.resize((round(SIZE * scale), round(SIZE * scale)), Image.LANCZOS)
    ox = CANVAS // 2 - round(CENTER[0] * scale)
    oy = CANVAS // 2 - round(CENTER[1] * scale)
    mask = Image.new("L", art.size, 0)
    feather = 40
    ImageDraw.Draw(mask).rectangle((feather, feather, art.size[0] - feather, art.size[1] - feather), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    canvas.paste(art, (ox, oy), mask)
    return canvas


def monochrome(full):
    """Solid white silhouette of the metal (tile frame and E) on transparency."""
    # The metal is grey and the backdrop navy, so the red channel separates them cleanly.
    red = full.convert("RGB").split()[0]
    alpha = red.point(lambda v: 0 if v < 95 else min(255, (v - 95) * 16)).filter(ImageFilter.GaussianBlur(1.5))
    out = Image.new("RGBA", full.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def legacy_square(img):
    """The metal tile itself (with its own rounded corners) on transparency."""
    tile = img.crop(TILE).convert("RGBA")
    m = Image.new("L", tile.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, tile.size[0] - 1, tile.size[1] - 1), radius=TILE_RADIUS, fill=255)
    tile.putalpha(m)
    side = max(tile.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.alpha_composite(tile, ((side - tile.size[0]) // 2, (side - tile.size[1]) // 2))
    return sq


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
    full = adaptive_full(img)
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
