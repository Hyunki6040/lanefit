"""Draw the README figures in docs/media/ with Pleos tokens and Pretendard.

    uv run -q --with pillow python -I scripts/readme_figures.py

Every number drawn here is a design value or an assumption from docs/submission/proposal.md, not a measurement.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/media"
SCREENS = ROOT / "docs/design/screens"
FONT_DIR = Path.home() / "Library/Fonts"

BG, CARD, TEXT, SUB, LINE = "#F7F8FA", "#FFFFFF", "#131417", "#6B6E76", "#E0E1E6"
HIGH, GREEN, RED, AMBER = "#EDEEF2", "#02C265", "#FE3D16", "#FFB800"


def font(weight, size):
    return ImageFont.truetype(str(FONT_DIR / f"Pretendard-{weight}.otf"), size)


def canvas(w, h, color=BG):
    im = Image.new("RGB", (w, h), color)
    return im, ImageDraw.Draw(im)


def card(im, box, radius=36, fill=CARD, shadow=True):
    if shadow:
        sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
        x0, y0, x1, y1 = box
        ImageDraw.Draw(sh).rounded_rectangle((x0, y0 + 10, x1, y1 + 10), radius, fill=(19, 20, 23, 28))
        blurred = sh.filter(ImageFilter.GaussianBlur(18))
        im.paste(blurred, (0, 0), blurred)
    ImageDraw.Draw(im).rounded_rectangle(box, radius, fill=fill)


def paste_round(im, src, box, radius=28):
    x0, y0, x1, y1 = box
    pic = Image.open(src).convert("RGB")
    pic.thumbnail((x1 - x0, y1 - y0), Image.LANCZOS)
    mask = Image.new("L", pic.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *pic.size), radius, fill=255)
    card(im, (x0, y0, x0 + pic.width, y0 + pic.height), radius)
    im.paste(pic, (x0, y0), mask)
    return pic.size


def pill(d, xy, text, fill, color=CARD, f=None):
    f = f or font("Bold", 26)
    x, y = xy
    w = d.textlength(text, font=f)
    d.rounded_rectangle((x, y, x + w + 40, y + 50), 25, fill=fill)
    d.text((x + 20, y + 25), text, font=f, fill=color, anchor="lm")
    return x + w + 40


def hero():
    im, d = canvas(1600, 760)
    d.text((80, 110), "LaneFit · 레인핏", font=font("SemiBold", 30), fill=SUB)
    d.multiline_text((80, 170), "깜빡이를 켜면,\n옮길 만한지\n한 문장으로.", font=font("Bold", 76), fill=TEXT, spacing=14)
    d.text((80, 470), "Pleos Connect 읽기 전용 차선 비서", font=font("Medium", 32), fill=TEXT)
    d.text((80, 516), "Android Automotive · 안전 운전", font=font("Regular", 26), fill=SUB)
    x = pill(d, (80, 590), "기본 답은 Stay", GREEN)
    pill(d, (x + 16, 590), "차량 제어 없음", TEXT)
    paste_round(im, SCREENS / "sim-stay.png", (720, 90, 1540, 680))
    im.save(OUT / "hero.png", optimize=True)


def arrow(d, x0, y, x1, color=SUB):
    d.line((x0, y, x1 - 14, y), fill=color, width=4)
    d.polygon([(x1, y), (x1 - 18, y - 11), (x1 - 18, y + 11)], fill=color)


def flow():
    im, d = canvas(1600, 900)
    d.text((80, 70), "운전자는 아무것도 누르지 않습니다", font=font("Bold", 44), fill=TEXT)
    d.text((80, 130), "원래 하던 동작, 방향지시등이 곧 질문입니다.", font=font("Regular", 28), fill=SUB)
    steps = [
        ("1", "방향지시등", "운전자가 켠다"),
        ("2", "내 차선 추정", "조향각·차속으로 추적"),
        ("3", "앞으로 10km", "차로 간 예상 시간 차"),
        ("4", "모드 기준 비교", "Calm 3분 · Flow 2분 · Rush 1분"),
    ]
    w, gap, y = 320, 50, 220
    for i, (n, title, sub) in enumerate(steps):
        x = 80 + i * (w + gap)
        card(im, (x, y, x + w, y + 200), 28)
        d.ellipse((x + 28, y + 28, x + 76, y + 76), fill=TEXT)
        d.text((x + 52, y + 52), n, font=font("Bold", 26), fill=CARD, anchor="mm")
        d.text((x + 28, y + 104), title, font=font("Bold", 32), fill=TEXT)
        d.text((x + 28, y + 152), sub, font=font("Regular", 22), fill=SUB)
        if i < len(steps) - 1:
            arrow(d, x + w + 6, y + 100, x + w + gap - 6)
    # Two outcomes under step 4, Stay first because it is the common one.
    outcomes = [
        (HIGH, GREEN, "유지", "옮겨도 40초 차이예요,", "유지하세요", "기준에 못 미치면 (대부분)"),
        (CARD, TEXT, "속도 정보", "왼쪽이 2분 빨라요", "", "기준을 넘을 때만, 10km당 횟수 제한"),
    ]
    for i, (bg, tag, label, l1, l2, note) in enumerate(outcomes):
        x = 80 + i * 740
        card(im, (x, 500, x + 700, 800), 32, fill=bg)
        pill(d, (x + 36, 530), label, tag, f=font("Bold", 24))
        d.text((x + 36, 620), l1, font=font("Medium" if l2 else "Bold", 34 if l2 else 44), fill=TEXT)
        if l2:
            d.text((x + 36, 668), l2, font=font("Bold", 48), fill=TEXT)
        d.text((x + 36, 752), note, font=font("Regular", 22), fill=SUB)
    d.line((80 + 3 * (w + gap) + w // 2, 420, 80 + 3 * (w + gap) + w // 2, 470), fill=SUB, width=4)
    d.line((430, 470, 1190, 470), fill=SUB, width=4)
    for x in (430, 1190):
        d.line((x, 470, x, 488), fill=SUB, width=4)
        d.polygon([(x, 500), (x - 11, 482), (x + 11, 482)], fill=SUB)
    d.text((80, 850), "옆 차와의 간격·진입 가능 여부는 판단하지 않습니다. 그 판단은 운전자의 몫입니다.",
           font=font("Regular", 22), fill=SUB)
    im.save(OUT / "flow.png", optimize=True)


def modes():
    im, d = canvas(1600, 760)
    d.text((80, 70), "운전자마다 다른 기준, 세 가지 모드", font=font("Bold", 44), fill=TEXT)
    d.text((80, 130), "차량 주행 모드를 따라 자동으로 바뀝니다. 직접 고정할 수도 있습니다.", font=font("Regular", 28), fill=SUB)
    rows = [
        ("Calm", 3, 1, "ECO 주행 · 동승자 탑승", GREEN),
        ("Flow", 2, 2, "NORMAL 주행 · 기본값", TEXT),
        ("Rush", 1, 3, "SPORT 주행", RED),
    ]
    base, unit = 670, 80
    for i, (name, minutes, cap, link, color) in enumerate(rows):
        x = 120 + i * 480
        card(im, (x, 210, x + 400, 700), 32)
        d.text((x + 36, 246), name, font=font("Bold", 40), fill=TEXT)
        d.text((x + 36, 302), link, font=font("Regular", 22), fill=SUB)
        top = base - minutes * unit
        d.rounded_rectangle((x + 36, top, x + 156, base), 18, fill=color)
        d.text((x + 96, top - 30), f"{minutes}분+", font=font("Bold", 30), fill=TEXT, anchor="mm")
        d.text((x + 186, base - 80), "변경 안내 기준", font=font("Regular", 20), fill=SUB)
        d.text((x + 186, base - 50), "예상 단축 시간", font=font("Medium", 22), fill=TEXT)
        for k in range(3):
            fill = color if k < cap else LINE
            d.rounded_rectangle((x + 186 + k * 52, 350, x + 226 + k * 52, 390), 10, fill=fill)
        d.text((x + 186, 402), f"10km당 최대 {cap}회", font=font("Medium", 22), fill=TEXT)
    d.text((80, 720), "모든 모드 공통: 변경 후 60초는 다시 안내하지 않음 · 확신도 0.6 미만이면 침묵",
           font=font("Regular", 21), fill=SUB)
    im.save(OUT / "modes.png", optimize=True)


def risk():
    im, d = canvas(1600, 860)
    d.text((80, 70), "바꾼 뒤에는, 얼마나 안전하게 바꿨는지", font=font("Bold", 44), fill=TEXT)
    d.text((80, 130), "차선을 바꿀 때마다 0~100점 위험 지수와 한 줄 코칭을 보여 줍니다.", font=font("Regular", 28), fill=SUB)
    card(im, (80, 200, 1520, 500), 32)
    d.text((120, 236), "신호 선행 거리 · 고속도로 기준", font=font("Bold", 30), fill=TEXT)
    x0, x1, y = 140, 1460, 360
    scale = (x1 - x0) / 120
    d.rounded_rectangle((x0, y - 14, x1, y + 14), 14, fill=HIGH)
    d.rounded_rectangle((x0, y - 14, x0 + 61 * scale, y + 14), 14, fill=AMBER)
    for m in (0, 30, 61, 100):
        x = x0 + m * scale
        d.line((x, y + 20, x, y + 40), fill=SUB, width=3)
        d.text((x, y + 58), f"{m}m", font=font("Medium", 22), fill=TEXT, anchor="mm")
    lx = x0 + 100 * scale
    d.line((lx, y - 70, lx, y + 14), fill=GREEN, width=5)
    d.text((lx, y - 92), "법정 기준 100m", font=font("Bold", 24), fill=GREEN, anchor="mm")
    d.text((x0 + 30 * scale, y - 40), "일반도로 30m", font=font("Regular", 20), fill=SUB, anchor="mm")
    d.text((x0 + 61 * scale, y - 92), "이번 변경 61m", font=font("Bold", 24), fill=TEXT, anchor="mm")
    d.text((120, 448), "시속 100km면 신호 후 약 3.6초 뒤에 옮겨야 100m를 지킵니다. (도로교통법 시행령 별표 2)",
           font=font("Regular", 21), fill=SUB)
    factors = [
        ("신호 선행 거리", "법정 기준에 못 미친 만큼"), ("신호 없음", "가장 큰 감점"),
        ("너무 빠른 이동", "점등 후 1초 안"), ("급조향", "조향각 변화율"),
        ("변경 후 급제동", "변경 뒤 3초 안"), ("연속 변경", "직전 60초 안"),
    ]
    for i, (title, sub) in enumerate(factors):
        x, yy = 80 + (i % 3) * 486, 540 + (i // 3) * 140
        card(im, (x, yy, x + 456, yy + 116), 24)
        d.text((x + 30, yy + 28), title, font=font("Bold", 28), fill=TEXT)
        d.text((x + 30, yy + 72), sub, font=font("Regular", 22), fill=SUB)
    d.text((80, 826), "30점 미만 낮음 · 60점 미만 보통 · 그 이상 높음. 점수는 참고용 추정치입니다.",
           font=font("Regular", 21), fill=SUB)
    im.save(OUT / "risk.png", optimize=True)


def mau():
    im, d = canvas(1600, 700)
    d.text((80, 70), "예상 MAU (가정 기반 추정)", font=font("Bold", 44), fill=TEXT)
    d.text((80, 130), "탑재 차량 × 고속도로 정기 이용 40% × 설치율 × 월간 활성 70%", font=font("Regular", 28), fill=SUB)
    bars = [("2027년 말", 1.7, "60만 대 · 설치 10%"), ("2028년 말", 6.3, "150만 대 · 설치 15%"), ("2030년", 21, "500만 대 · 설치 15%")]
    base, top = 600, 230
    for i, (year, v, note) in enumerate(bars):
        x = 160 + i * 470
        h = (base - top) * v / 21
        d.rounded_rectangle((x, base - h, x + 200, base), 20, fill=GREEN if i == 2 else TEXT)
        d.text((x + 100, base - h - 34), f"약 {v:g}만", font=font("Bold", 34), fill=TEXT, anchor="mm")
        d.text((x + 230, base - 60), year, font=font("Bold", 28), fill=TEXT)
        d.text((x + 230, base - 22), note, font=font("Regular", 22), fill=SUB)
    d.line((100, base, 1500, base), fill=LINE, width=3)
    d.text((80, 640), "2030년 탑재 대수: 그룹 발표 Pleos Connect 약 2,000만 대 목표 × 국내 25% 가정. 나머지 입력값은 근거 자료 없는 가정입니다.",
           font=font("Regular", 21), fill=SUB)
    im.save(OUT / "mau.png", optimize=True)


def screens():
    """Driving, risk and report side by side; then night and portrait."""
    im, d = canvas(1600, 470)
    for i, (name, label) in enumerate([("sim-stay", "Stay 카드"), ("car-risk", "위험 지수"), ("car-report", "주차 후 리포트")]):
        x = 60 + i * 506
        paste_round(im, SCREENS / f"{name}.png", (x, 40, x + 470, 380), 20)
        d.text((x + 235, 425), label, font=font("Bold", 26), fill=TEXT, anchor="mm")
    im.save(OUT / "screens.png", optimize=True)
    im, d = canvas(1600, 640)
    paste_round(im, SCREENS / "sim-stay-dark.png", (60, 40, 820, 590), 20)
    paste_round(im, SCREENS / "sim-risk-dark.png", (860, 40, 1240, 315), 16)
    paste_round(im, SCREENS / "parked-dark.png", (860, 335, 1240, 610), 16)
    paste_round(im, SCREENS / "portrait-stay.png", (1270, 40, 1560, 610), 16)
    im.save(OUT / "screens-night-portrait.png", optimize=True)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for draw in (hero, flow, modes, risk, mau, screens):
        draw()
    print("wrote", ", ".join(sorted(p.name for p in OUT.glob("*.png"))))
