"""Cut the demo video (16:9) and short-form versions (9:16) from emulator screen recordings.

    uv run -q --with pillow python -I scripts/make_videos.py REC_DIR OUT_DIR [VARIANT ...]

REC_DIR holds sim-sr.mp4 (in-app 66 s simulation) and car.mp4 (Virtual Console vehicle signals), both
`adb shell screenrecord` of the 2560x1440 Pleos Connect emulator. Every frame of the app is a real recording;
captions are drawn with Pillow because this ffmpeg build has no drawtext/subtitles filter.
Narration is macOS `say -v Yuna`.
"""
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT_DIR = Path.home() / "Library/Fonts"
BG, TEXT, SUB, GREEN = "#F7F8FA", "#131417", "#6B6E76", "#02C265"
CROP = "crop=1682:1210:853:90"  # app window inside the emulator frame
FPS = 30
SHORT_MAX_S = 60  # contest rule for the short-form: 30 s to 1 min, vertical
SPOKEN = {"Pleos Connect": "플레오스 커넥트", "Calm": "캄", "LaneFit": "레인핏", "Virtual Console": "가상 콘솔"}
LABEL = {
    "sim": "Pleos Connect 에뮬레이터 실행 화면 · 앱 내 시뮬레이션 데이터",
    "car": "Pleos Connect 에뮬레이터 실행 화면 · Virtual Console 차량 신호 + 예시 도로 데이터",
}


def clip(src, start, dur, title, sub, say="", speed=1.0):
    return dict(src=src, start=start, dur=dur, title=title, sub=sub, say=say, speed=speed)


def card(title, sub, say="", dur=3.0):
    return dict(src=None, dur=dur, title=title, sub=sub, say=say, speed=1.0)


STAY = clip("sim", 6, 9, "깜빡이를 켜면, 답은 한 문장", "옮겨도 40초 차이예요, 유지하세요",
            "왼쪽 깜빡이를 켰습니다. 옮겨도 40초 차이라서, 답은 유지하세요.")
CHANGE = clip("sim", 30.5, 8.5, "옮길 만할 때만 속도 정보", "왼쪽이 2분 빨라요 · 옆 차 간격은 운전자가 확인",
              "차이가 모드 기준을 넘을 때만, 어느 쪽이 얼마나 빠른지 말합니다.")
RISK = clip("sim", 39, 9, "바꾼 뒤에는 위험 지수", "신호를 얼마나 일찍 켰는지 · 한 줄 코칭",
            "차선을 바꾸면 신호를 얼마나 일찍 켰는지 따져서, 위험 지수와 코칭을 보여 줍니다.")
CALM = clip("sim", 47.5, 7, "동승자가 타면 Calm 모드", "3분 이상 차이 날 때만 안내",
            "조수석에 동승자가 타면, 3분 이상 차이 날 때만 안내하는 Calm 모드로 바뀝니다.")
REPORT = clip("sim", 64, 6, "주차하면 주행 리포트", "빨리 간 것보다 덜 바꾼 것을 칭찬",
              "주차하면, 덜 바꾸고 부드럽게 바꾼 주행을 리포트로 보여 줍니다.")
CAR = clip("car", 8, 22, "차량 신호로도 같은 흐름", "방향지시등 · 조향각 · 브레이크 · 착석 신호",
           "Virtual Console로 넣은 차량 신호에서도 같은 흐름이 동작합니다.", speed=2.0)

VARIANTS = {
    "LaneFit_demo_16x9": dict(portrait=False, voice=True, beats=[
        card("깜빡이를 켜면,\n옮길 만한지 한 문장으로", "LaneFit · 레인핏 · Pleos Connect 차선 비서",
             "레인핏은 방향지시등을 켜는 순간, 차선을 옮길 만한지 한 문장으로 알려 주는 Pleos Connect 앱입니다.", 4),
        clip("sim", 1, 5, "내 차선은 앱이 추정합니다", "조향각과 차속으로 차선 이동을 따라감",
             "주행 중에는 조향각과 차속으로 내 차선을 따라갑니다."),
        STAY, CHANGE, RISK, CALM, REPORT, CAR,
        card("덜 바꿔도 늦지 않습니다", "차량을 제어하지 않는 읽기 전용 앱 · 공모전 프로토타입",
             "덜 바꿔도 늦지 않습니다. LaneFit.", 3.5),
    ]),
    "LaneFit_short_A_question": dict(portrait=True, voice=True, beats=[
        card("옆 차선,\n진짜 빠를까?", "LaneFit · 레인핏", "옆 차선이 빨라 보여서 옮겼는데, 정말 빨랐을까요?", 3),
        STAY, CHANGE, RISK, CALM, REPORT,
        card("덜 바꿔도\n늦지 않습니다", "깜빡이만 켜세요 · LaneFit", "덜 바꿔도 늦지 않습니다. LaneFit.", 3),
    ]),
    "LaneFit_short_B_stay": dict(portrait=True, voice=True, beats=[
        dict(STAY, title="깜빡이를 켰더니\n\"유지하세요\"?",
             say="깜빡이를 켜자 차가 답합니다. 옮겨도 40초 차이예요, 유지하세요."),
        dict(CHANGE, title="옮길 만하면\n그때만 말해요"),
        dict(RISK, title="바꾼 뒤엔\n얼마나 안전했는지"),
        dict(CAR, title="차량 신호로도\n동작합니다", dur=20),
        REPORT,
        card("운전자는\n깜빡이만", "차량은 제어하지 않아요 · LaneFit", "운전자는 깜빡이만 켜면 됩니다. LaneFit.", 3),
    ]),
    "LaneFit_short_C_captions_only": dict(portrait=True, voice=False, beats=[
        card("옆 차선,\n진짜 빠를까?", "LaneFit · 레인핏", dur=2.5),
        STAY, CHANGE, RISK, CALM, REPORT,
        card("덜 바꿔도\n늦지 않습니다", "깜빡이만 켜세요 · LaneFit", dur=3),
    ]),
}


def font(weight, size):
    return ImageFont.truetype(str(FONT_DIR / f"Pretendard-{weight}.otf"), size)


def wrap(d, text, f, width):
    out = []
    for para in text.split("\n"):
        line = ""
        for word in para.split(" "):
            t = f"{line} {word}".strip()
            if line and d.textlength(t, font=f) > width:
                out.append(line)
                line = word
            else:
                line = t
        out.append(line)
    return "\n".join(out)


def layout(portrait):
    """Canvas size and where the app video sits: (W, H, x, y, w, h). App aspect is 1682:1210."""
    return (1080, 1920, 20, 600, 1040, 748) if portrait else (1920, 1080, 362, 20, 1196, 860)


def draw_bg(beat, portrait, path):
    W, H, x, y, w, h = layout(portrait)
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    if beat["src"] is None:
        big, small = (font("Bold", 110), font("Medium", 46)) if portrait else (font("Bold", 88), font("Medium", 36))
        title = wrap(d, beat["title"], big, W - 160)
        d.multiline_text((W / 2, H * 0.36), title, font=big, fill=TEXT, anchor="mm", align="center", spacing=24)
        d.text((W / 2, H * (0.58 if portrait else 0.66)), beat["sub"], font=small, fill=GREEN, anchor="mm")
    elif portrait:
        d.text((60, 150), "LaneFit · 레인핏", font=font("SemiBold", 34), fill=SUB)
        d.multiline_text((60, 210), wrap(d, beat["title"], font("Bold", 76), 960), font=font("Bold", 76),
                         fill=TEXT, spacing=16)
        d.rounded_rectangle((x - 4, y - 4, x + w + 4, y + h + 4), 12, fill="#E0E1E6")
        d.text((60, y + h + 22), LABEL[beat["src"]], font=font("Regular", 24), fill=SUB)
        d.multiline_text((60, y + h + 90), wrap(d, beat["sub"], font("Medium", 50), 960), font=font("Medium", 50),
                         fill=TEXT, spacing=14)
    else:
        d.text((W / 2, y + h + 46), beat["title"], font=font("Bold", 42), fill=TEXT, anchor="mm")
        d.text((W / 2, y + h + 104), beat["sub"], font=font("Regular", 32), fill=TEXT, anchor="mm")
        d.text((W - 40, H - 28), LABEL[beat["src"]], font=font("Regular", 22), fill=SUB, anchor="rm")
    im.save(path)


def run(cmd):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if r.returncode:
        sys.exit(f"failed: {' '.join(map(str, cmd))}\n{r.stderr[-2000:]}")
    return r.stdout


def duration(path):
    return float(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path]))


def narrate(text, path):
    for en, ko in SPOKEN.items():
        text = text.replace(en, ko)
    run(["say", "-v", "Yuna", "-r", "200", "-o", path, text])
    return duration(path)


def segment(beat, portrait, voice, rec, work, i):
    W, H, x, y, w, h = layout(portrait)
    bg = work / f"{i:02d}.png"
    draw_bg(beat, portrait, bg)
    shown = beat["dur"] / beat["speed"]
    narr = work / f"{i:02d}.aiff"
    total = max(shown, narrate(beat["say"], narr) + 0.9) if voice and beat["say"] else shown
    cmd = ["ffmpeg", "-y", "-loop", "1", "-framerate", str(FPS), "-t", f"{total:.2f}", "-i", bg]
    filt = "[0:v]format=yuv420p[out]"
    if beat["src"]:
        src = rec / ("sim-sr.mp4" if beat["src"] == "sim" else "car.mp4")
        cmd += ["-ss", str(beat["start"]), "-t", str(beat["dur"]), "-i", src]
        filt = (f"[1:v]{CROP},setpts=(PTS-STARTPTS)/{beat['speed']},scale={w}:{h},fps={FPS},"
                f"tpad=stop_mode=clone:stop_duration={total - shown:.2f}[v];"
                f"[0:v][v]overlay={x}:{y}:eof_action=repeat,format=yuv420p[out]")
    a = len([c for c in cmd if c == "-i"])
    if voice and beat["say"]:
        cmd += ["-i", narr]
        filt += f";[{a}:a]adelay=300:all=1,apad,atrim=0:{total:.2f},aformat=sample_rates=48000:channel_layouts=stereo[a]"
        amap = "[a]"
    else:
        cmd += ["-f", "lavfi", "-t", f"{total:.2f}", "-i", "anullsrc=r=48000:cl=stereo"]
        amap = f"{a}:a"
    out = work / f"{i:02d}.mp4"
    run(cmd + ["-filter_complex", filt, "-map", "[out]", "-map", amap, "-t", f"{total:.2f}", "-r", str(FPS),
               "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", out])
    return out


def build(name, spec, rec, out_dir):
    work = out_dir / "work" / name
    work.mkdir(parents=True, exist_ok=True)
    parts = [segment(b, spec["portrait"], spec["voice"], rec, work, i) for i, b in enumerate(spec["beats"])]
    listing = work / "list.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts))
    joined = work / "joined.mp4"
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listing, "-c", "copy", joined])
    final = out_dir / f"{name}.mp4"
    audio = ["-af", "loudnorm=I=-14:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "160k", "-ar", "48000"] \
        if spec["voice"] else ["-c:a", "copy"]
    run(["ffmpeg", "-y", "-i", joined, "-c:v", "copy", *audio, "-movflags", "+faststart", final])
    secs = duration(final)
    if spec["portrait"] and not 30 <= secs <= SHORT_MAX_S:
        sys.exit(f"{name}: {secs:.1f} s is outside the 30-60 s short-form rule")
    print(f"{final.name}: {secs:.1f} s")


if __name__ == "__main__":
    rec, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    only = sys.argv[3:] or list(VARIANTS)
    for name in only:
        build(name, VARIANTS[name], rec, out_dir)
