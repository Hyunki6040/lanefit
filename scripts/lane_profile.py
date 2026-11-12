"""한국도로공사 AVC 차로별 15분 평균속도로 앱에 싣는 차로 속도 프로필을 만들고, 날짜를 하나씩 빼고 맞혀 보는 백테스트를 돌린다.

  받기    python3 -I scripts/lane_profile.py fetch   공개 OpenAPI → data/raw/ex-avc-lane-15min/
  만들기  python3 -I scripts/lane_profile.py build   → app/src/main/assets/lane_profile.txt, docs/research/lane-profile-backtest.json

프로필: 지점·평일/주말·15분 칸·차로별 승용차 평균속도의 날짜 중앙값.
백테스트: 하루를 빼고 만든 프로필로 그날을 예측한다. 앱과 똑같이 내 차로의 실제 속도에 수준을 맞춘 뒤
옆 차로 속도를 추정하고, "10km에서 몇 초 이득인가"와 모드별 판단(변경 안내 / 유지)을 실제 값과 견준다.
"""
import json
import statistics
import sys
import time
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data/raw/ex-avc-lane-15min"
# key=test 는 포털 문서에 실린 공개 샘플 키다.
API = "https://data.ex.co.kr/openapi/avcinfo/avcOg15DataList?key=test&type=json&routeNo=0010&totlDates={day}&avcId={site}"
FIRST, LAST = date(2026, 10, 28), date(2026, 11, 11)
HORIZON_KM = 10.0
MIN_CARS = 20            # 15분에 승용차 20대 미만이면 평균속도를 믿지 않는다
BUS_HOURS = (28, 84)     # 07:00~21:00 의 15분 칸. 이 시간대에 버스가 승용차보다 많은 차로는 버스전용차로로 보고 뺀다
MIN_DAYS = 3             # 프로필 한 칸을 채우는 최소 날짜 수
SCALE = (0.4, 1.3)       # 앱의 수준 보정 범위 (engine/RoadProfile.kt 의 값과 같아야 한다)
THRESHOLDS = {"Rush": 60, "Flow": 120, "Calm": 180}

# 구간 이름과 방향은 원시자료의 cnznNm, drctClssCd(E: 이정이 커지는 방향, S: 작아지는 방향)에서 확인했다.
# 도로공사 AVC 설치정보에는 이 지점들의 좌표가 없다. 좌표가 있는 앞뒤 지점(이정 388.0 / 393.2 / 397.4km)을
# 이정으로 직선 보간해 검지기 앞뒤 1.5km 를 구간으로 잡는다. a → b 가 진행 방향이다.
KM_POINTS = {388.0: (37.22863, 127.10574), 393.2: (37.27500, 127.10376), 397.4: (37.31284, 127.10356)}
SITES = {
    "0010AVI3908": ("경부선 기흥IC→수원신갈IC", 390.76, +1),
    "0010AVI3917": ("경부선 수원신갈IC→기흥IC", 391.70, -1),
    "0010AVI3962": ("경부선 신갈JC→서울TG", 396.15, +1),
    "0010AVI3961": ("경부선 서울TG→신갈JC", 396.12, -1),
}


def point_at(km):
    kms = sorted(KM_POINTS)
    lo = max((k for k in kms[:-1] if k <= km), default=kms[0])
    hi = min(k for k in kms if k > lo)
    f = (km - lo) / (hi - lo)
    (la0, lo0), (la1, lo1) = KM_POINTS[lo], KM_POINTS[hi]
    return round(la0 + f * (la1 - la0), 5), round(lo0 + f * (lo1 - lo0), 5)


def days():
    d = FIRST
    while d <= LAST:
        yield d
        d += timedelta(days=1)


def fetch():
    RAW.mkdir(parents=True, exist_ok=True)
    for site in SITES:
        for d in days():
            out = RAW / f"avc_{d:%Y%m%d}_{site}.json"
            if out.exists():
                continue
            body = urllib.request.urlopen(API.format(day=f"{d:%Y%m%d}", site=site), timeout=30).read()
            rows = json.loads(body)
            if rows.get("code") != "SUCCESS":
                sys.exit(f"{site} {d}: {rows.get('message')}")
            out.write_bytes(body)
            print(out.name, rows["count"])
            time.sleep(1.0)  # 공개 포털에 부담을 주지 않는다


def load(site):
    """({day: {slot: {lane: speed}}}, 차로 수, 버스전용차로 집합)."""
    by_day, cars, buses = {}, {}, {}
    for d in days():
        path = RAW / f"avc_{d:%Y%m%d}_{site}.json"
        if not path.exists():
            continue
        for r in json.loads(path.read_text(encoding="utf-8"))["list"]:
            lane, slot = int(r["crgwNo"]), (int(r["totlHhmm"][:2]) * 60 + int(r["totlHhmm"][2:])) // 15
            speed, n_car, n_bus = float(r["avgSped1"]), float(r["trfv1"]), float(r["trfv2"])
            if BUS_HOURS[0] <= slot < BUS_HOURS[1]:
                cars[lane] = cars.get(lane, 0) + n_car
                buses[lane] = buses.get(lane, 0) + n_bus
            if speed > 0 and n_car >= MIN_CARS:
                by_day.setdefault(d, {}).setdefault(slot, {})[lane] = speed
    if not cars:
        return {}, 0, set()
    return by_day, max(cars), {lane for lane in cars if buses[lane] > cars[lane]}


def profile(by_day, lanes, kind, skip=None):
    """kind: 'weekday' / 'weekend'. [slot][lane-1] = 중앙값 또는 None."""
    use = [d for d in by_day if d != skip and (d.weekday() >= 5) == (kind == "weekend")]
    table = []
    for slot in range(96):
        row = []
        for lane in range(1, lanes + 1):
            vals = [by_day[d][slot][lane] for d in use if lane in by_day[d].get(slot, {})]
            row.append(statistics.median(vals) if len(vals) >= MIN_DAYS else None)
        table.append(row)
    return table


def gain_s(v_here, v_there):
    return (HORIZON_KM / max(v_here, 5.0) - HORIZON_KM / max(v_there, 5.0)) * 3600


def backtest(by_day, lanes, bus_lanes):
    """(예측 이득, 실제 이득) 쌍. 예측은 앱과 같은 식: 프로필 × (내 차로 실제 속도 / 내 차로 프로필)."""
    pairs = []
    for d in sorted(by_day):
        table = profile(by_day, lanes, "weekend" if d.weekday() >= 5 else "weekday", skip=d)
        for slot, actual in by_day[d].items():
            for lane, v_here in actual.items():
                for nxt in (lane - 1, lane + 1):
                    if lane in bus_lanes or nxt in bus_lanes or nxt not in actual:
                        continue
                    p_here, p_there = table[slot][lane - 1], table[slot][nxt - 1]
                    if p_here is None or p_there is None:
                        continue
                    f = min(max(v_here / p_here, SCALE[0]), SCALE[1])
                    pairs.append((gain_s(p_here * f, p_there * f), gain_s(v_here, actual[nxt])))
    return pairs


def summarize(pairs):
    n = len(pairs)
    actual = [a for _, a in pairs]
    out = {
        "moves": n,
        "actual_median_gain_s": round(statistics.median(actual), 1),
        "actual_share_no_gain": round(sum(a <= 0 for a in actual) / n, 3),
        "actual_share_under_60s": round(sum(a < 60 for a in actual) / n, 3),
        "median_abs_error_s": round(statistics.median(abs(p - a) for p, a in pairs), 1),
        "modes": {},
    }
    for mode, t in THRESHOLDS.items():
        said = [(p, a) for p, a in pairs if p >= t]
        kept = [(p, a) for p, a in pairs if p < t]
        out["modes"][mode] = {
            "threshold_s": t,
            "agreement": round(sum((p >= t) == (a >= t) for p, a in pairs) / n, 3),
            "change_said": len(said),
            "change_said_share": round(len(said) / n, 3),
            "change_right": round(sum(a >= t for _, a in said) / len(said), 3) if said else None,
            "change_not_a_loss": round(sum(a > 0 for _, a in said) / len(said), 3) if said else None,
            "stay_right": round(sum(a < t for _, a in kept) / len(kept), 3) if kept else None,
            "actual_over_threshold_share": round(sum(a >= t for _, a in pairs) / n, 3),
        }
    return out


def build():
    lines, all_pairs, per_site = [], [], {}
    for site, (name, km, direction) in SITES.items():
        by_day, lanes, bus_lanes = load(site)
        if len(by_day) < MIN_DAYS + 1:
            sys.exit(f"{site}: {len(by_day)}일치뿐입니다. 먼저 fetch 를 실행하세요")
        (la_a, lo_a), (la_b, lo_b) = point_at(km - 1.5 * direction), point_at(km + 1.5 * direction)
        lines.append(f"S|{site}|{name}|{la_a}|{lo_a}|{la_b}|{lo_b}|{lanes}|{','.join(map(str, sorted(bus_lanes)))}")
        for kind, tag in (("weekday", "W"), ("weekend", "E")):
            for slot, row in enumerate(profile(by_day, lanes, kind)):
                # 버스전용차로 칸은 비워 둔다: 앱은 속도가 없는 차로로는 안내하지 않는다.
                cells = ("" if v is None or lane in bus_lanes else f"{v:.0f}" for lane, v in enumerate(row, 1))
                lines.append(f"{tag}|{slot}|" + ",".join(cells))
        pairs = backtest(by_day, lanes, bus_lanes)
        all_pairs += pairs
        per_site[site] = {"name": name, "days": len(by_day), "lanes": lanes, "bus_lanes": sorted(bus_lanes), **summarize(pairs)}
    asset = ROOT / "app/src/main/assets/lane_profile.txt"
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text("\n".join(lines) + "\n", encoding="utf-8")
    result = {
        "source": "한국도로공사 고속도로 공공데이터 포털 AVC 원시자료 OpenAPI (15분 집계, 지점 속도)",
        "period": [FIRST.isoformat(), LAST.isoformat()], "horizon_km": HORIZON_KM, "min_cars_per_15min": MIN_CARS,
        "method": "leave-one-day-out", "all": summarize(all_pairs), "sites": per_site,
    }
    out = ROOT / "docs/research/lane-profile-backtest.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("wrote", asset.relative_to(ROOT), f"({asset.stat().st_size} bytes)", "and", out.relative_to(ROOT))


if __name__ == "__main__":
    assert abs(gain_s(60, 80) - 150) < 1e-6 and point_at(393.2) == KM_POINTS[393.2]  # self-check
    {"fetch": fetch, "build": build}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
