#!/usr/bin/env python3
"""Generate XenoLauncher adaptive/legacy/notification icons from design/xeno_icon_source.png."""
from pathlib import Path
from collections import deque
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design" / "xeno_icon_source.png"
RES = ROOT / "ZalithLauncher" / "src" / "main" / "res"
BG = (255, 246, 214, 255)          # light warm yellow
SAFE = 280                          # 432px canvas, ~65% (72dp of 108dp)


def remove_white_background(src: Image.Image) -> Image.Image:
    img = src.convert("RGBA")
    w, h = img.size
    px = img.load()
    near = lambda p: p[3] > 0 and p[0] >= 244 and p[1] >= 244 and p[2] >= 244 and (max(p[:3]) - min(p[:3])) <= 8
    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if not seen[y * w + x] and near(px[x, y]):
                seen[y * w + x] = 1; q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if not seen[y * w + x] and near(px[x, y]):
                seen[y * w + x] = 1; q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 0 <= nx < w and 0 <= ny < h and not seen[ny*w+nx] and near(px[nx, ny]):
                seen[ny*w+nx] = 1; q.append((nx, ny))
    mask = Image.new("L", (w, h), 255)
    mp = mask.load()
    for y in range(h):
        row = y*w
        for x in range(w):
            if seen[row + x]:
                mp[x, y] = 0
    # cut 1px white fringe, then soften the edge
    mask = mask.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.6))
    img.putalpha(mask)
    bbox = img.getbbox()
    return img.crop(bbox)


def fit_on_canvas(cube: Image.Image, size: int, content: int, bg=None, round_mask=False, silhouette=False) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), bg or (0, 0, 0, 0))
    scale = content / max(cube.size)
    nw, nh = max(1, round(cube.width*scale)), max(1, round(cube.height*scale))
    art = cube.resize((nw, nh), Image.LANCZOS)
    if silhouette:
        alpha = art.getchannel("A")
        art = Image.new("RGBA", art.size, (0, 0, 0, 255))
        art.putalpha(alpha)
    canvas.alpha_composite(art, ((size-nw)//2, (size-nh)//2))
    if round_mask:
        m = Image.new("L", (size, size), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(m).ellipse((0, 0, size-1, size-1), fill=255)
        canvas.putalpha(Image.composite(canvas.getchannel("A"), Image.new("L", (size, size), 0), m))
    return canvas


def save(img, rel):
    path = RES / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", path.relative_to(ROOT))


src = Image.open(SRC).convert("RGBA")
cube = remove_white_background(src)
print("cube size", cube.size)

# adaptive icon: 108dp foreground/monochrome at all densities
for name, size in [("mdpi",108),("hdpi",162),("xhdpi",216),("xxhdpi",324),("xxxhdpi",432)]:
    fg = fit_on_canvas(cube, size, round(size*SAFE/432))
    save(fg, f"drawable-{name}/ic_launcher_foreground.png")
    mono = fit_on_canvas(cube, size, round(size*SAFE/432), silhouette=True)
    save(mono, f"drawable-{name}/ic_launcher_monochrome.png")


# legacy launcher icons
for name, size in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    icon = fit_on_canvas(cube, size, round(size*0.78), bg=BG)
    save(icon, f"mipmap-{name}/ic_launcher.png")
    round_icon = fit_on_canvas(cube, size, round(size*0.72), bg=BG, round_mask=True)
    save(round_icon, f"mipmap-{name}/ic_launcher_round.png")

# about page logo / play-style preview
about = fit_on_canvas(cube, 512, 360, bg=BG)
save(about, "drawable-nodpi/img_launcher.png")
preview = ROOT / "design" / "xeno_icon_512.png"
preview.parent.mkdir(parents=True, exist_ok=True)
about.save(preview, optimize=True)
print("wrote", preview.relative_to(ROOT))

# notification small icon: white silhouette, 24dp
for name, size in [("mdpi",24),("hdpi",36),("xhdpi",48),("xxhdpi",72),("xxxhdpi",96)]:
    base = fit_on_canvas(cube, size, round(size*0.9), silhouette=True)
    white = Image.new("RGBA", base.size, (255,255,255,0))
    white.putalpha(base.getchannel("A"))
    save(white, f"drawable-{name}/ic_notification.png")

# background color
(RES / "values" / "ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    '    <color name="ic_launcher_background">#FFF6D6</color>\n</resources>\n'
)

# adaptive icon xml keep monochrome; remove old vector drawables with the same names
for p in [RES/"drawable"/"ic_launcher_foreground.xml", RES/"drawable"/"ic_launcher_monochrome.xml"]:
    if p.exists(): p.unlink(); print("removed", p.relative_to(ROOT))
