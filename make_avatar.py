from PIL import Image
import os

SRC = r"C:\Users\Musta\OneDrive\Desktop\谷歌猪\assets\states\large\idle.png"
OUTS = [
    r"C:\Users\Musta\OneDrive\Desktop\减肥记录\pig-avatar.png",
    r"C:\Users\Musta\OneDrive\Desktop\LitheApp\app\src\main\assets\www\pig-avatar.png",
]

img = Image.open(SRC).convert("RGBA")
bbox = img.getbbox() or (0, 0, img.width, img.height)
pig = img.crop(bbox)

size = 96
canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
ratio = pig.width / pig.height
if ratio >= 1:
    w = size
    h = int(w / ratio)
else:
    h = size
    w = int(h * ratio)
pig = pig.resize((w, h), Image.LANCZOS)
offset = ((size - w) // 2, (size - h) // 2)
canvas.paste(pig, offset, pig)

for out in OUTS:
    os.makedirs(os.path.dirname(out), exist_ok=True)
    canvas.save(out)
    print("saved", out, canvas.size)
