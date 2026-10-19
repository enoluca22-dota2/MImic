"""Render the icon candidates in design/icon/ to one preview sheet (MImic 4.0).

    python tools/icon/preview_candidates.py <render.js> <out.png>

<render.js> is a small resvg-js wrapper (node render.js in.svg out.png size). Each candidate is
composited over design/icon/background.svg exactly like an adaptive icon (the visible area is the
centre 72 of the 108-unit canvas), masked as circle and rounded square, at 192/96/48 px on light
and dark wallpapers, plus the dashed 66-unit safe circle on a full-canvas view.
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ICON = os.path.join(ROOT, "design", "icon")
CANDIDATES = [
    ("A  Monogram", "candidate_a_monogram.svg"),
    ("B  Premium note", "candidate_b_note.svg"),
    ("C  Audio orbit", "candidate_c_orbit.svg"),
]


def render(js, svg, size, tmp):
    out = os.path.join(tmp, f"{os.path.basename(svg)}_{size}.png")
    subprocess.run(["node", js, svg, out, str(size)], check=True)
    return Image.open(out).convert("RGBA")


def icon(js, fg_svg, size, shape, tmp):
    full = round(size * 108 / 72) * 2
    bg = render(js, os.path.join(ICON, "background.svg"), full, tmp)
    fg = render(js, fg_svg, full, tmp)
    img = Image.alpha_composite(bg, fg)
    c = round(full * 18 / 108)
    img = img.crop((c, c, full - c, full - c)).resize((size * 2, size * 2), Image.LANCZOS)
    m = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, img.width - 1, img.height - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=img.width * 0.28, fill=255)
    img.putalpha(m)
    return img.resize((size, size), Image.LANCZOS)


def main():
    js, out = sys.argv[1], sys.argv[2]
    tmp = tempfile.mkdtemp()
    col_w, row_h = 760, 300
    sheet = Image.new("RGB", (col_w * len(CANDIDATES), row_h * 2 + 60), "#FFFFFF")
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("segoeui.ttf", 26)
    except OSError:
        font = ImageFont.load_default()
    for i, (name, f) in enumerate(CANDIDATES):
        x0 = i * col_w
        d.text((x0 + 20, 14), name, fill="#111111", font=font)
        for row, wall in enumerate(("#E9ECF2", "#101114")):
            y0 = 60 + row * row_h
            d.rectangle([x0, y0, x0 + col_w - 10, y0 + row_h - 10], fill=wall)
            x = x0 + 16
            # full canvas with safe-zone guide
            full = Image.alpha_composite(render(js, os.path.join(ICON, "background.svg"), 216, tmp), render(js, os.path.join(ICON, f), 216, tmp))
            g = ImageDraw.Draw(full)
            r = 33 * 2
            g.ellipse([108 - r, 108 - r, 108 + r, 108 + r], outline=(255, 80, 80, 200), width=1)
            sheet.paste(full.resize((216, 216)), (x, y0 + 30), full.resize((216, 216)))
            x += 236
            for size, shape in ((192, "squircle"), (96, "circle"), (48, "circle"), (48, "squircle")):
                im = icon(js, os.path.join(ICON, f), size, shape, tmp)
                sheet.paste(im, (x, y0 + 30 + (192 - size) // 2), im)
                x += size + 18
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    main()
