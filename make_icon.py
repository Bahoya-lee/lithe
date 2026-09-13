from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
import os

SRC = r"C:\Users\Musta\OneDrive\Desktop\谷歌猪\assets\states\large\idle.png"
RES_DIR = r"C:\Users\Musta\OneDrive\Desktop\LitheApp\app\src\main\res"
FG_OUT = os.path.join(RES_DIR, "drawable-nodpi", "ic_launcher_foreground.png")
PREVIEW_OUT = r"C:\Users\Musta\OneDrive\Desktop\LitheApp\icon-preview.png"

BG = (70, 224, 168, 255)  # #46E0A8 更亮的薄荷绿
CANVAS = 432

img = Image.open(SRC).convert("RGBA")
bbox = img.getbbox()
if bbox is None:
    bbox = (0, 0, img.width, img.height)

pig = img.crop(bbox)
pig = ImageEnhance.Brightness(pig).enhance(1.10)
pig = ImageEnhance.Color(pig).enhance(1.10)

# Adaptive-icon foreground: transparent canvas, pig fits inside the safe zone.
foreground = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
max_size = int(CANVAS * 0.62)
ratio = pig.width / pig.height
if ratio >= 1:
    w = max_size
    h = int(w / ratio)
else:
    h = max_size
    w = int(h * ratio)
pig = pig.resize((w, h), Image.LANCZOS)
offset = ((CANVAS - w) // 2, (CANVAS - h) // 2)

# 给猪加一层柔和的白色光晕，让图标更亮、更突出
glow = Image.new("L", (CANVAS, CANVAS), 0)
d = ImageDraw.Draw(glow)
cx, cy = CANVAS // 2, CANVAS // 2
r = int(max_size * 0.72)
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
glow = glow.filter(ImageFilter.GaussianBlur(radius=int(CANVAS * 0.09)))
glow = glow.point(lambda p: int(p * 0.55))
halo = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 255))
halo.putalpha(glow)

foreground.paste(halo, (0, 0), halo)
foreground.paste(pig, offset, pig)

os.makedirs(os.path.dirname(FG_OUT), exist_ok=True)
foreground.save(FG_OUT)

# Full preview: same pig centered on the brand-green rounded app background.
preview = Image.new("RGBA", (CANVAS, CANVAS), BG)
preview.paste(halo, (0, 0), halo)
preview.paste(pig, offset, pig)
preview.save(PREVIEW_OUT)

print("source_bounds", bbox)
print("pig_size", pig.size)
print("saved", FG_OUT)
print("saved", PREVIEW_OUT)
