"""Drive the Pleos Connect emulator through one LaneFit scenario with vehicle signals.

Uses the emulator's Virtual Console HTTP API, so gear, speed, turn signal, steering angle, brake and
seat occupancy reach the app through the Pleos SDK / AAOS. Lane speeds come from the data source chosen
on the parked screen (데이터 출처):

    차량 신호 (default)  the built-in lane profile, inside the profiled road sections only. Move the position
                         into a section with `adb emu geo fix <lon> <lat>` and give the app its lane (ramp merge,
                         a spoken "지금 3차로야", or `--ei lane N` on a debug build). Elsewhere there is no advice.
    예시 도로            made-up four-lane speeds, anywhere. The step names below (2-change, ...) match this source.

    adb forward tcp:8088 tcp:8088
    python3 scripts/drive_emulator.py [--shots DIR]

Open LaneFit first and pick the data source on the parked screen.
"""
import argparse
import math
import subprocess
import threading
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8088"


def call(method, path, **params):
    url = f"{BASE}{path}?{urllib.parse.urlencode(params)}"
    urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=3).read()


def heartbeat(stop):
    # The console answers writes with 503 emulator_inactive unless a browser session pings every second.
    while not stop.wait(1.0):
        try:
            call("POST", "/browser/session", client="lanefit-drive")
        except OSError as e:
            print("heartbeat failed:", e)


def controls(speed, steering=0, brake=0):
    call("PUT", "/vehicle/controls", speed=round(speed), accelerator=0 if brake else 15, brake=brake,
         steering=round(steering))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", help="directory for adb screenshots at each step")
    args = ap.parse_args()

    def step(name, wait_s):
        print(f"{time.strftime('%H:%M:%S')} {name}")
        time.sleep(wait_s)
        if args.shots:
            with open(f"{args.shots}/{name}.png", "wb") as f:
                subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, check=True)

    stop = threading.Event()
    call("POST", "/browser/session", client="lanefit-drive")
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    try:
        call("PUT", "/vehicle/seat", kind="occupied", area="passenger", value="false")
        call("PUT", "/vehicle/turn-signal", mode="Off")
        call("PUT", "/vehicle/gear", gear="Drive")
        controls(60)
        step("1-idle", 6)

        call("PUT", "/vehicle/turn-signal", mode="BlinkLeft")
        step("2-change", 4)  # 4 s of signal at 60 km/h is about 67 m

        # One 5 s sine of steering moves one lane at 60 km/h. The console's + is the app's left.
        t0 = time.monotonic()
        while (t := time.monotonic() - t0) < 5.0:
            controls(60, steering=7.36 * math.sin(2 * math.pi * t / 5.0))
            time.sleep(0.05)
        controls(60)
        call("PUT", "/vehicle/turn-signal", mode="Off")
        time.sleep(1.0)

        # Hard stop right after the change: 60 -> 30 km/h in 2 s is about 4 m/s2.
        t0 = time.monotonic()
        while (t := time.monotonic() - t0) < 2.0:
            controls(60 - 15 * t, brake=60)
            time.sleep(0.05)
        controls(30)
        step("3-risk", 3)

        controls(60)
        time.sleep(6)
        call("PUT", "/vehicle/seat", kind="occupied", area="passenger", value="true")
        step("4-passenger", 3)
    finally:
        controls(0, brake=30)
        call("PUT", "/vehicle/gear", gear="Park")
        call("PUT", "/vehicle/seat", kind="occupied", area="passenger", value="false")
        controls(0)
        step("5-report", 3)
        stop.set()


if __name__ == "__main__":
    main()
