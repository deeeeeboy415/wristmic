"""Draws the WristMic launcher icon at every density (run once; output goes in res/)."""
import os
from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
HERE = os.path.dirname(os.path.abspath(__file__))


def icon(px):
    s = px * 4  # draw big, shrink for smooth edges
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    grad = Image.new("RGBA", (s, s))
    top, bottom = (45, 212, 191), (15, 118, 110)
    gd = ImageDraw.Draw(grad)
    for y in range(s):
        t = y / (s - 1)
        gd.line([(0, y), (s, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    cx, cy, r = s / 2, s / 2, s * 0.5
    w, h = r * 0.34, r * 0.62
    top_y = cy - r * 0.40
    d.rounded_rectangle([cx - w / 2, top_y, cx + w / 2, top_y + h], radius=w / 2, fill="white")
    stroke = max(2, int(r * 0.075))
    ar = w * 0.95
    acy = top_y + h * 0.52
    d.arc([cx - ar, acy - ar, cx + ar, acy + ar], 0, 180, fill="white", width=stroke)
    stem_top, stem_bottom = acy + ar, acy + ar + r * 0.17
    d.line([cx, stem_top - stroke / 2, cx, stem_bottom], fill="white", width=stroke)
    d.line([cx - w * 0.62, stem_bottom, cx + w * 0.62, stem_bottom], fill="white", width=stroke)
    return img.resize((px, px), Image.LANCZOS)


for density, px in SIZES.items():
    out = os.path.join(HERE, "res", "mipmap-" + density)
    os.makedirs(out, exist_ok=True)
    icon(px).save(os.path.join(out, "ic_launcher.png"), optimize=True)
icon(512).save(os.path.join(HERE, "icon-preview.png"))
print("icons written")
