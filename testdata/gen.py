"""테스트용 만화 이미지 생성. 페이지 번호를 크게 그려서 스크린샷으로 순서 확인 가능."""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.join(os.path.dirname(__file__), "Comics", "원피스")

def font(sz):
    for p in (r"C:\Windows\Fonts\malgun.ttf", r"C:\Windows\Fonts\arial.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()

def page(path, label, w=1000, h=1400, bg=(245, 240, 230)):
    im = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(im)
    d.rectangle([8, 8, w - 8, h - 8], outline=(60, 60, 60), width=4)
    f = font(min(w, h) // 4)
    tb = d.textbbox((0, 0), label, font=f)
    d.text(((w - (tb[2] - tb[0])) / 2, (h - (tb[3] - tb[1])) / 2), label, fill=(30, 30, 30), font=f)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    print(path)

# 1권: 자연 정렬(9 < 10 < 11), 이름 섞임(cover), 그리고 좌우 양면 스캔본(가로가 긴 05)
v1 = os.path.join(BASE, "제1권")
page(f"{v1}/cover.png", "COVER")
for n in (1, 2, 3, 9, 10, 11):
    page(f"{v1}/{n}.jpg", str(n))
page(f"{v1}/05.jpg", "4-5", w=2000, h=1400, bg=(230, 235, 245))  # 양면(비 1.43)

# 2권: '다음 폴더' 이동 대상
v2 = os.path.join(BASE, "제2권")
for n in (1, 2, 3):
    page(f"{v2}/{n:03d}.jpg", f"v2-{n}")

# 10권: 폴더 자연 정렬 확인 (제2권 < 제10권)
v10 = os.path.join(BASE, "제10권")
for n in (1, 2):
    page(f"{v10}/{n}.jpg", f"v10-{n}")
