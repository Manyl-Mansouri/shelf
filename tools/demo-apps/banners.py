"""Renders a 640x360 TV banner per line of apps.tsv. Stand-ins for local testing only."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"

def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

def background(spec):
    stops = [hex_rgb(c) for c in spec.split(",")]
    img = Image.new("RGB", (W, H), stops[0])
    if len(stops) == 2:
        a, b = stops
        px = img.load()
        for y in range(H):
            for x in range(W):
                t = (x / W) * 0.6 + (y / H) * 0.4
                px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return img

def fit_font(draw, text, max_w, max_h):
    size = max_h
    while size > 12:
        font = ImageFont.truetype(FONT, size, index=1)  # Helvetica Neue Bold
        l, t, r, b = draw.textbbox((0, 0), text, font=font)
        if r - l <= max_w and b - t <= max_h:
            return font
        size -= 2
    return ImageFont.truetype(FONT, 12, index=1)

out = Path(sys.argv[2])
for line in Path(sys.argv[1]).read_text().splitlines():
    if not line.strip() or line.startswith("#"):
        continue
    pkg, label, bg, fg, text = line.split("\t")
    img = background(bg)
    d = ImageDraw.Draw(img)
    font = fit_font(d, text, W * 0.78, H * (0.30 if len(text) < 12 else 0.16))
    l, t, r, b = d.textbbox((0, 0), text, font=font)
    d.text(((W - (r - l)) / 2 - l, (H - (b - t)) / 2 - t), text, font=font, fill=hex_rgb(fg))
    img.save(out / f"{pkg}.png")
