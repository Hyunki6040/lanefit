"""고정 CCTV 영상에서 차로별 흐름(통과 대수, 화면상 속도)을 읽는 분석 도구.

방법: 차로마다 사다리꼴 영역을 정해 두고, 연속 프레임의 광학 흐름(Farneback)에서
움직이는 화소만 골라 차로별 중앙 속도(px/s)를 구한다. 통과 대수는 차로 가운데 검지선의
점유가 꺼졌다 켜지는 횟수로 센다.

실행
  자체 검증(정답을 아는 합성 영상):  uv run -q --with opencv-python-headless --with numpy python -I scripts/analyze_cctv.py --selftest
  실제 영상:  uv run -q --with opencv-python-headless --with numpy --with matplotlib python -I scripts/analyze_cctv.py data/raw/its-cctv/jukjeon/clip.mp4

한계
  - 속도는 화면상 px/s 다. 같은 높이 띠에서 차로끼리 견주는 값이고 km/h 가 아니다.
    # ponytail: 차선 점선 주기(20m)로 호모그래피를 잡으면 km/h 로 바꿀 수 있다. 카메라마다 보정이 필요해 지금은 하지 않는다.
  - 차로 영역은 카메라마다 손으로 잡는다(아래 CAMERAS). 카메라가 회전하면 다시 잡아야 한다.
  - 야간 영상에서는 전조등 빛 번짐이 옆 차로 영역까지 움직임으로 잡혀 통과 대수를 실제보다 많이 센다
    (경부선 죽전 야간 30초로 확인). 낮 영상, 차량 검출 모델, 카메라별 차로 보정이 있어야 대수를 믿을 수 있다.
  - 영상 원본은 저장소에 넣지 않는다.
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
MOVING_PX_PER_FRAME = 0.8   # 이보다 느린 화소는 배경으로 본다
MIN_MOVING_SHARE = 0.03     # 차로 영역의 3% 이상이 움직일 때만 속도 표본으로 쓴다
OCCUPIED_SHARE = 0.25       # 검지선 화소의 25% 이상이 움직이면 차가 지나가는 중
GAP_FRAMES = 4              # 이만큼 비어야 다음 차로 센다

# 차로 경계선의 x좌표를 위/아래 두 높이에서 적는다(왼쪽부터). 경계가 N+1개면 차로는 N개.
CAMERAS = {
    # 국가교통정보센터 공개 CCTV · 경부선 죽전(서울 방향, 화면 오른쪽 차도). 1차로는 버스전용.
    "jukjeon": {"y_top": 270, "y_bottom": 370,
                "x_top": [173, 213, 253, 295, 340, 383], "x_bottom": [57, 125, 188, 248, 313, 377]},
    # Higgsfield 로 만든 연출용 CCTV 컷(영상 편집의 오버레이 좌표를 뽑는 데만 쓴다. 분석 결과로 인용하지 않는다).
    "hf_cctv": {"dy": 0, "y_top": 110, "y_bottom": 864, "x_top": [180, 212, 250, 290, 330], "x_bottom": [-15, 150, 300, 455, 620]},
    "selftest": {"y_top": 60, "y_bottom": 180, "x_top": [40, 100, 160, 220], "x_bottom": [40, 100, 160, 220]},
}


def lane_masks(cam, shape):
    masks = []
    for i in range(len(cam["x_top"]) - 1):
        quad = np.array([[cam["x_top"][i], cam["y_top"]], [cam["x_top"][i + 1], cam["y_top"]],
                         [cam["x_bottom"][i + 1], cam["y_bottom"]], [cam["x_bottom"][i], cam["y_bottom"]]], np.int32)
        mask = np.zeros(shape, np.uint8)
        cv2.fillPoly(mask, [quad], 1)
        masks.append(mask.astype(bool))
    return masks


def analyze(frames, fps, cam, boxes=None):
    """frames: 회색조 프레임 이터러블. 차로별 {count, speed_px_s, samples} 와 프레임별 속도 시계열을 돌려준다."""
    prev, masks, lines = None, None, None
    speeds, counts, gap, series = [], [], [], []
    for gray in frames:
        if prev is None:
            prev = gray
            masks = lane_masks(cam, gray.shape)
            mid = (cam["y_top"] + cam["y_bottom"]) // 2
            band = np.zeros(gray.shape, bool)
            band[mid - 2:mid + 3] = True
            lines = [m & band for m in masks]
            speeds = [[] for _ in masks]
            counts = [0] * len(masks)
            gap = [GAP_FRAMES] * len(masks)
            continue
        flow = cv2.calcOpticalFlowFarneback(prev, gray, None, 0.5, 3, 21, 3, 5, 1.1, 0)
        prev = gray
        mag = np.hypot(flow[..., 0], flow[..., 1])
        # 반대편 차도의 전조등 반사는 아래로 흐른다. 이 차도의 진행 방향(위쪽)으로 움직이는 화소만 쓴다.
        moving = mag > MOVING_PX_PER_FRAME
        if cam.get("dy", -1):   # dy 0 이면 방향을 가리지 않는다
            moving &= flow[..., 1] * cam.get("dy", -1) > 0.7 * mag
        row = []
        if boxes is not None:   # 영상 편집용: 프레임마다 움직이는 덩어리의 상자 [x, y, w, h, 차로]
            blob = cv2.morphologyEx(moving.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
            n, _, stats, cents = cv2.connectedComponentsWithStats(blob)
            found = []
            for k in range(1, n):
                x, y, w, h, area = (int(v) for v in stats[k])
                cx, cy = int(cents[k][0]), int(cents[k][1])
                lane = next((i + 1 for i, m in enumerate(masks) if m[cy, cx]), 0)
                if area >= 250 and lane:
                    found.append([x, y, w, h, lane])
            boxes.append(found)
        for i, mask in enumerate(masks):
            sel = moving & mask
            v = float(np.median(mag[sel])) * fps if sel.sum() >= MIN_MOVING_SHARE * mask.sum() else None
            if v is not None:
                speeds[i].append(v)
            row.append(v)
            occupied = moving[lines[i]].mean() >= OCCUPIED_SHARE
            if occupied and gap[i] >= GAP_FRAMES:
                counts[i] += 1
            gap[i] = 0 if occupied else gap[i] + 1
        series.append(row)
    lanes = [{"lane": i + 1, "count": counts[i], "samples": len(s),
              "speed_px_s": round(float(np.median(s)), 1) if s else None} for i, s in enumerate(speeds)]
    return lanes, series


def read_gray(path):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        sys.exit(f"영상을 열 수 없습니다: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0

    def frames():
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            yield cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        cap.release()
    return fps, frames()


def selftest():
    """세 차로에 서로 다른 속도(4, 8, 2 px/frame)의 차를 흘려 보내고 추정이 맞는지 본다."""
    fps, truth, period = 30.0, [4, 8, 2], [40, 30, 80]   # period: 몇 프레임마다 한 대
    n_frames = 240
    rng = np.random.default_rng(7)
    texture = rng.integers(60, 255, (30, 36), dtype=np.uint8)   # 광학 흐름이 잡을 무늬

    def frames():
        for t in range(n_frames):
            img = np.full((240, 260), 30, np.uint8)
            for lane, (v, p) in enumerate(zip(truth, period)):
                for start in range(0, n_frames, p):
                    y = 230 - (t - start) * v
                    if t >= start and 0 <= y < 200:
                        img[y:y + 30, 52 + lane * 60:88 + lane * 60] = texture
            yield img
    lanes, _ = analyze(frames(), fps, CAMERAS["selftest"])
    for lane, v, p in zip(lanes, truth, period):
        expect_count = sum(1 for s in range(0, n_frames, p) if s + (230 - 120) / v < n_frames)
        err = abs(lane["speed_px_s"] - v * fps) / (v * fps)
        print(f"차로 {lane['lane']}: 속도 {lane['speed_px_s']} px/s (정답 {v * fps:.0f}, 오차 {err:.0%}), 대수 {lane['count']} (정답 {expect_count})")
        assert err < 0.15, "속도 오차 15% 초과"
        assert lane["count"] == expect_count, "통과 대수 불일치"
    order = [l["lane"] for l in sorted(lanes, key=lambda l: -l["speed_px_s"])]
    assert order == [2, 1, 3], order
    print("자체 검증 통과")


def draw(lanes, series, fps, title, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    for f in ("AppleGothic", "Apple SD Gothic Neo", "NanumGothic"):
        if any(f == x.name for x in font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = f
            break
    colors = ["#9aa3ad", "#1f6feb", "#12a594", "#f08c00", "#d6336c", "#7048e8"]
    fig, (a, b) = plt.subplots(1, 2, figsize=(13, 4.6), gridspec_kw={"width_ratios": [1.6, 1]})
    for i, lane in enumerate(lanes):
        pts = [(t / fps, row[i]) for t, row in enumerate(series) if row[i] is not None]
        a.scatter([p[0] for p in pts], [p[1] for p in pts], s=6, color=colors[i % 6], label=f"{lane['lane']}차로")
    a.set(xlabel="시간 (초)", ylabel="화면상 속도 (px/s)", title=title)
    a.grid(alpha=0.25)
    a.legend(ncol=len(lanes), frameon=False, loc="upper center")
    names = [f"{l['lane']}차로" for l in lanes]
    b.bar(names, [l["speed_px_s"] or 0 for l in lanes], color=colors[:len(lanes)])
    for i, l in enumerate(lanes):
        b.text(i, (l["speed_px_s"] or 0) + 2, f"{l['count']}대", ha="center", fontsize=10)
    b.set(ylabel="중앙 속도 (px/s)", title="차로별 흐름 · 막대 위는 통과 대수")
    b.grid(alpha=0.25, axis="y")
    fig.text(0.5, 0.005, "영상: 국가교통정보센터 공개 CCTV. 속도는 화면상 값이며 km/h 가 아니다. 차로끼리 견주는 상대 속도 분석.",
             ha="center", fontsize=9, color="#555")
    fig.tight_layout(rect=(0, 0.03, 1, 1))
    fig.savefig(out, dpi=140)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--selftest":
        return selftest()
    path = Path(sys.argv[1])
    name = sys.argv[2] if len(sys.argv) > 2 else path.parent.name
    if name not in CAMERAS:
        sys.exit(f"차로 영역이 없는 카메라입니다: {name} (CAMERAS 에 추가하세요)")
    fps, frames = read_gray(path)
    if len(sys.argv) > 4 and sys.argv[3] == "--boxes":
        boxes = []
        lanes, _ = analyze(frames, fps, CAMERAS[name], boxes)
        Path(sys.argv[4]).write_text(json.dumps({"fps": fps, "lanes": lanes, "boxes": boxes}), encoding="utf-8")
        return print(f"{len(boxes)} 프레임, 상자 {sum(map(len, boxes))}개 -> {sys.argv[4]}")
    lanes, series = analyze(frames, fps, CAMERAS[name])
    fastest = max((l["speed_px_s"] or 0) for l in lanes) or 1
    for l in lanes:
        l["relative"] = round((l["speed_px_s"] or 0) / fastest, 2)
    summary = {"camera": name, "source": "국가교통정보센터(its.go.kr) 공개 CCTV", "seconds": round((len(series) + 1) / fps, 1),
               "fps": fps, "unit": "px/s (화면상 속도, km/h 아님)", "lanes": lanes}
    (ROOT / "docs/research/cctv-lane-flow.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    draw(lanes, series, fps, f"실제 CCTV {summary['seconds']:.0f}초에서 읽은 차로별 흐름 · {name}", ROOT / "docs/media/cctv-lane-flow.png")


if __name__ == "__main__":
    main()
