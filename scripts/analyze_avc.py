"""한국도로공사 AVC 차로별 15분 평균속도로 '옆 차로로 옮기면 10km에서 몇 초 이득인가'를 계산한다.

입력: data/raw/ex-avc-lane-15min/avc_*_all-day.json (공개 OpenAPI 응답 그대로)
출력: docs/research/avc-lane-gap.json (요약), docs/media/lane-gap.png (그림), video/public/data/avc.json (영상용)

실행: uv run -q --with matplotlib python -I scripts/analyze_avc.py
한계: 한 지점의 지점 속도(15분 집계) 하루치다. 구간 통행시간이 아니며 실시간도 아니다.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data/raw/ex-avc-lane-15min/avc_20261110_site-0010AVI3962_all-day.json"
HORIZON_KM = 10.0
MIN_CARS = 20          # 15분에 승용차 20대 미만이면 평균속도를 믿지 않는다
BUS_LANE = 1           # 경부선 신갈JC–서울TG 1차로는 버스전용차로
THRESHOLDS = {"Rush": 60, "Flow": 120, "Calm": 180}


def load(path):
    rows = json.loads(path.read_text(encoding="utf-8"))["list"]
    table = {}  # hhmm -> {lane: (speed, cars)}
    for r in rows:
        speed, cars = float(r["avgSped1"]), float(r["trfv1"])
        if speed > 0 and cars >= MIN_CARS:
            table.setdefault(r["totlHhmm"], {})[int(r["crgwNo"])] = (speed, cars)
    return rows[0], table


def gain_seconds(v_now, v_next):
    """v_now 차로에서 v_next 차로로 옮겼을 때 HORIZON_KM 구간에서 아끼는 초(음수면 손해)."""
    return (HORIZON_KM / v_now - HORIZON_KM / v_next) * 3600


def main():
    if not SRC.exists():
        sys.exit(f"입력 파일이 없습니다: {SRC}")
    meta, table = load(SRC)
    moves = []  # (hhmm, from, to, gain)
    for hhmm, lanes in sorted(table.items()):
        for lane, (v, _) in lanes.items():
            for nxt in (lane - 1, lane + 1):
                if lane == BUS_LANE or nxt == BUS_LANE or nxt not in lanes:
                    continue
                moves.append((hhmm, lane, nxt, gain_seconds(v, lanes[nxt][0])))
    if not moves:
        sys.exit("비교할 인접 차로 쌍이 없습니다")
    n = len(moves)
    gains = sorted(g for *_, g in moves)
    share = {k: sum(g >= t for g in gains) / n for k, t in THRESHOLDS.items()}
    best = max(moves, key=lambda m: m[3])
    summary = {
        "source": "한국도로공사 고속도로 공공데이터 포털 AVC 원시자료 OpenAPI",
        "site": meta["avcId"], "section": meta["cnznNm"], "route": meta["routeNm"], "date": meta["totlDates"],
        "horizon_km": HORIZON_KM, "min_cars_per_15min": MIN_CARS, "bus_lane_excluded": BUS_LANE,
        "slots": len(table), "adjacent_moves": n,
        "median_gain_s": round(gains[n // 2], 1),
        "share_no_gain": round(sum(g <= 0 for g in gains) / n, 3),
        "share_under_60s": round(sum(g < 60 for g in gains) / n, 3),
        "share_over_threshold": {k: round(v, 3) for k, v in share.items()},
        "best_move": {"hhmm": best[0], "from": best[1], "to": best[2], "gain_s": round(best[3], 1)},
    }
    out = ROOT / "docs/research/avc-lane-gap.json"
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    series = {str(l): [[h, table[h][l][0]] for h in sorted(table) if l in table[h]] for l in range(1, 6)}
    vid = ROOT / "video/public/data"
    vid.mkdir(parents=True, exist_ok=True)
    (vid / "avc.json").write_text(json.dumps({"summary": summary, "series": series}, ensure_ascii=False), encoding="utf-8")
    draw(series, moves, summary)


def draw(series, moves, s):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    for f in ("AppleGothic", "Apple SD Gothic Neo", "NanumGothic"):
        if any(f == x.name for x in font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = f
            break
    plt.rcParams["axes.unicode_minus"] = False
    colors = {"1": "#9aa3ad", "2": "#1f6feb", "3": "#12a594", "4": "#f08c00", "5": "#d6336c"}
    fig, (a, b) = plt.subplots(1, 2, figsize=(14, 5.2), gridspec_kw={"width_ratios": [1.5, 1]})
    for lane, pts in series.items():
        if not pts:
            continue
        x = [int(h[:2]) + int(h[2:]) / 60 for h, _ in pts]
        label = f"{lane}차로" + (" (버스전용)" if lane == "1" else "")
        a.plot(x, [v for _, v in pts], color=colors[lane], lw=2, label=label, alpha=0.55 if lane == "1" else 1)
    a.set(xlim=(0, 24), xticks=range(0, 25, 3), xlabel="시각", ylabel="승용차 평균속도 (km/h)",
          title=f"같은 지점, 같은 시각에도 차로마다 속도가 다르다\n{s['route']} {s['section']} · {s['date'][:4]}-{s['date'][4:6]}-{s['date'][6:]}")
    a.grid(alpha=0.25)
    a.legend(ncol=5, loc="lower center", frameon=False)
    gains = [g for *_, g in moves]
    b.hist(gains, bins=40, color="#1f6feb", alpha=0.85)
    for name, t in THRESHOLDS.items():
        b.axvline(t, color="#d6336c", ls="--", lw=1)
        b.text(t + 3, b.get_ylim()[1] * 0.92, f"{name} {t}초", rotation=90, va="top", fontsize=9, color="#d6336c")
    b.set(xlabel=f"옆 차로로 옮길 때 {int(s['horizon_km'])}km에서 아끼는 시간 (초)", ylabel="경우의 수",
          title=f"옮겨도 1분을 못 아끼는 경우 {s['share_under_60s']:.0%}\n인접 차로 이동 {s['adjacent_moves']}건")
    b.grid(alpha=0.25)
    fig.text(0.5, 0.005, "자료: 한국도로공사 고속도로 공공데이터 포털 AVC 원시자료(15분 집계, 지점 속도). 1지점 하루치이며 실시간 자료가 아니다.",
             ha="center", fontsize=9, color="#555")
    fig.tight_layout(rect=(0, 0.03, 1, 1))
    fig.savefig(ROOT / "docs/media/lane-gap.png", dpi=140)


if __name__ == "__main__":
    main()
