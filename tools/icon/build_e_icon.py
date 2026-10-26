"""MImic app icon: the owner's glass waveform disc image -> every Android launcher asset.

    python tools/icon/build_e_icon.py

Source: docs/icon/source/mimic-wave-disc-source.png (the owner's image "1234", unchanged). Its
"transparent" background is a checkerboard baked into the JPEG, so the disc is cut out with an
exact circle. Nothing is redrawn: the disc is only scaled and centered so it survives every
launcher mask (circle, squircle, rounded square), over a soft blur of its own colours.

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
SRC = os.path.join(ROOT, "docs", "icon", "source", "mimic-wave-disc-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# The disc in the source (measured on the 1024 px original): center and radius, pulled in a few
# pixels so no checkerboard shows at the edge.
SIZE = 1024
CENTER = (511.5, 515.5)
RADIUS = 428
# Adaptive canvas: 108 dp. The disc is scaled to 70 dp: inside the 72 dp visible area of every
# launcher mask, rim included.
CANVAS = 1024
DISC_DP = 70


def disc(img):
    """The disc alone on transparency (anti-aliased circular cut)."""
    s = 4
    m = Image.new("L", (SIZE * s, SIZE * s), 0)
    cx, cy = CENTER
    ImageDraw.Draw(m).ellipse(((cx - RADIUS) * s, (cy - RADIUS) * s, (cx + RADIUS) * s, (cy + RADIUS) * s), fill=255)
    out = img.convert("RGBA")
    out.putalpha(m.resize((SIZE, SIZE), Image.LANCZOS))
    return out.crop((round(cx - RADIUS), round(cy - RADIUS), round(cx + RADIUS), round(cy + RADIUS)))


def backdrop(d):
    """A soft blur of the disc's own teal-to-violet colours, full canvas."""
    return d.convert("RGB").resize((64, 64)).filter(ImageFilter.GaussianBlur(14)).resize((CANVAS, CANVAS), Image.BICUBIC)


def adaptive_full(d):
    canvas = backdrop(d).convert("RGBA")
    px = round(DISC_DP / 108 * CANVAS)
    canvas.alpha_composite(d.resize((px, px), Image.LANCZOS), ((CANVAS - px) // 2, (CANVAS - px) // 2))
    return canvas.convert("RGB")


def monochrome(d):
    """White silhouette of the dark waveform on transparency, sized like the adaptive disc."""
    dark = d.convert("RGB").convert("L").point(lambda v: 255 if v < 95 else 0)
    inside = d.getchannel("A").point(lambda v: 255 if v > 250 else 0).filter(ImageFilter.MinFilter(41))
    alpha = Image.composite(dark, Image.new("L", d.size, 0), inside).filter(ImageFilter.GaussianBlur(1.5))
    px = round(DISC_DP / 108 * CANVAS)
    out = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
    w = Image.new("RGBA", (px, px), (255, 255, 255, 255))
    w.putalpha(alpha.resize((px, px), Image.LANCZOS))
    out.alpha_composite(w, ((CANVAS - px) // 2, (CANVAS - px) // 2))
    return out


def save(im, path, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.resize((px, px), Image.LANCZOS).save(path, optimize=True)


def main():
    img = Image.open(SRC).convert("RGB")
    assert img.size == (SIZE, SIZE), img.size
    d = disc(img)
    full = adaptive_full(d)
    mono = monochrome(d)
    bg = full.getpixel((40, 40))
    for name, k in DENS.items():
        # Legacy icons (API 24-25): the disc itself, square and round alike.
        save(d, os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"), round(48 * k))
        save(d, os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"), round(48 * k))
        save(full, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"), round(108 * k))
        save(mono, os.path.join(RES, f"mipmap-{name}", "ic_launcher_monochrome.png"), round(108 * k))
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n</resources>\n')
    docs = os.path.join(ROOT, "docs", "icon")
    save(d, os.path.join(docs, "mimic_icon_1024.png"), 1024)
    save(d, os.path.join(docs, "mimic_icon_512.png"), 512)
    full.save(os.path.join(docs, "mimic_icon_adaptive_full_1024.png"))
    print("background", "#%02X%02X%02X" % bg)


if __name__ == "__main__":
    main()
