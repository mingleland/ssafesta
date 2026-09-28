"""업로드 이미지 공개 전 육안 검토용 밀착 인화지(contact sheet)를 만든다."""
import glob
import os
import sys
from PIL import Image, ImageDraw

files = sorted(f for f in glob.glob(os.path.join(sys.argv[1], "**", "*"), recursive=True) if f.lower().endswith((".png", ".jpg", ".jpeg", ".gif", ".webp")))
per, tw, th = 12, 480, 300
for s in range(0, len(files), per):
    sheet = Image.new("RGB", (tw * 3, (th + 20) * 4), "white")
    dr = ImageDraw.Draw(sheet)
    for k, f in enumerate(files[s:s + per]):
        im = Image.open(f).convert("RGB")
        im.thumbnail((tw - 6, th - 6))
        x, y = (k % 3) * tw, (k // 3) * (th + 20)
        sheet.paste(im, (x + 3, y + 3))
        dr.text((x + 3, y + th), f"{s + k}: {os.path.basename(f)[:50]}", fill="black")
    out = os.path.join(sys.argv[2], f"sheet-{s // per:02d}.png")
    sheet.save(out)
    print(out)
print(len(files))

