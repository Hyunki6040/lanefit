#!/usr/bin/env python3
"""LaneFit ad sound pack. Every sample is synthesised here (no third-party audio), so we own all rights.

Regenerate from the repo root:
    uv run -q --with numpy --with scipy python -I video/scripts/make_audio.py
Writes video/public/{sfx,music}/*.wav (48 kHz, 16-bit, stereo) and video/public/audio-manifest.json.
Deterministic: fixed seeds, same bytes every run.
"""
import json
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48000
OUT = Path(__file__).resolve().parent.parent / "public"
BPM, BARS, LIFT_BAR = 88, 35, 17
SPB = 60 / BPM
BAR = 4 * SPB
CEIL_DBTP = -1.2  # ceiling before 16-bit rounding; asserted <= -1.0 after
PRE = 0.003  # clicks start 3 ms in, so the mandatory fade-in never dulls the transient


# ---------- dsp helpers ----------
def tt(dur):
    return np.arange(int(round(dur * SR))) / SR


def midi(m):
    return 440 * 2 ** ((m - 69) / 12)


def db(x):
    return 20 * np.log10(max(float(x), 1e-9))


def filt(x, kind, fc, order=2):
    return signal.sosfilt(signal.butter(order, fc, kind, fs=SR, output="sos"), x, axis=0)


def add(buf, x, at):
    i = int(round(at * SR))
    n = min(len(x), len(buf) - i)
    if n > 0:
        buf[i:i + n] += x[:n]


def pan(x, p):
    """Mono -> stereo, p in [-1, 1], equal power."""
    a = (p + 1) * np.pi / 4
    return np.column_stack([x * np.cos(a), x * np.sin(a)]) * np.sqrt(2)


def wet(x, rt, seed):
    """Synthetic room: one decorrelated noise-tail IR per ear. Band-limited to 250 Hz-5 kHz so bass stays mono."""
    rng = np.random.default_rng(seed)
    t = tt(rt * 1.2)
    ears = []
    for _ in range(2):
        ir = filt(filt(rng.standard_normal(len(t)) * np.exp(-6.9 * t / rt), "hp", 250), "lp", 5000)
        ir = np.concatenate([np.zeros(int(0.006 * SR)), ir / np.sqrt((ir ** 2).sum())])
        ears.append(signal.fftconvolve(x, ir)[:len(x)])
    return np.column_stack(ears)


def swept(dur, fc, width, rng):
    """Noise with a gaussian band (width in octaves) whose centre follows fc(u), u = 0..1."""
    n = int(round(dur * SR))
    f, t, z = signal.stft(rng.standard_normal(n + 2048), SR, nperseg=1024, noverlap=768)
    centre = fc(np.clip(t / dur, 0, 1))
    gain = np.exp(-0.5 * (np.log2(np.maximum(f, 20)[:, None] / centre[None, :]) / width) ** 2)
    return signal.istft(z * gain, SR, nperseg=1024, noverlap=768)[1][:n]


def smooth(u):
    return u * u * (3 - 2 * u)


# ---------- loudness (ITU-R BS.1770 K-weighting, 48 kHz coefficients) ----------
_K = (signal.tf2sos([1.53512485958697, -2.69169618940638, 1.19839281085285], [1, -1.69065929318241, 0.73248077421585]),
      signal.tf2sos([1, -2, 1], [1, -1.99004745483398, 0.99007225036621]))


def _blocks(x, hop):
    y = signal.sosfilt(_K[1], signal.sosfilt(_K[0], x, axis=0), axis=0)
    p = (y ** 2).sum(axis=1)
    n = int(0.4 * SR)
    p = np.pad(p, (0, max(0, n - len(p))))  # shorter than 400 ms: energy over one 400 ms window
    cs = np.concatenate([[0], np.cumsum(p)])
    i = np.arange(0, len(p) - n + 1, int(hop * SR))
    return (cs[i + n] - cs[i]) / n


def _lufs(ms):
    return -0.691 + 10 * np.log10(np.maximum(ms, 1e-12))


def momentary_max(x):
    return float(_lufs(_blocks(x, 0.01).max()))


def integrated(x):
    z = _blocks(x, 0.1)
    z = z[_lufs(z) > -70]
    z = z[_lufs(z) > _lufs(z.mean()) - 10]
    return float(_lufs(z.mean()))


def true_peak(x):
    return float(np.abs(signal.resample_poly(x, 4, 1, axis=0)).max())


def finalize(x, dur, target, fi=0.003, fo=0.010, measure=momentary_max):
    """Trim, DC-free, faded, loudness-normalised, true-peak-limited stereo."""
    n = int(round(dur * SR))
    x = np.repeat(x[:, None], 2, axis=1) if x.ndim == 1 else x
    x = filt(np.pad(x, ((0, max(0, n - len(x))), (0, 0)))[:n], "hp", 20)
    a, b = int(fi * SR), int(fo * SR)
    w = np.ones(n)
    w[:a] = 0.5 - 0.5 * np.cos(np.pi * np.arange(a) / a)
    w[n - b:] = 0.5 + 0.5 * np.cos(np.pi * (np.arange(b) + 1) / b)
    x = x * w[:, None]
    x = x - w[:, None] * x.sum(axis=0) / w.sum()  # exact zero mean without lifting the faded edges
    x = x * 10 ** ((target - measure(x)) / 20)
    return x * min(1.0, 10 ** (CEIL_DBTP / 20) / true_peak(x))


# ---------- instruments ----------
def mallet(f, vel=1.0, tau=0.42):
    """Marimba-like bar: modes near 1 : 4 : 10, upper modes die fast, softer hits are darker."""
    tau *= (330 / f) ** 0.5
    t = tt(min(7 * tau, 3.0))
    x = sum(a * np.exp(-t / (tau * k)) * np.sin(2 * np.pi * f * r * t)
            for r, a, k in ((1, 1, 1), (3.98, 0.20 * vel, 0.16), (9.9, 0.04 * vel, 0.05)))
    return vel * x * (1 - np.exp(-t / 0.0015))


def felt(f, vel=1.0, dur=2.5, ring=1.0):
    """Felt-piano-like key: few harmonics, two slightly detuned strings, soft hammer."""
    t = tt(dur)
    tau = ring * 1.5 * (220 / f) ** 0.4
    x = np.zeros(len(t))
    for h in range(1, 8):
        fh = f * h * np.sqrt(1 + 0.0004 * h * h)
        if fh > 2200:
            break
        for cents in (-1.5, 1.5):
            x += h ** -2.3 * np.exp(-t * h ** 0.7 / tau) * np.sin(2 * np.pi * fh * 2 ** (cents / 1200) * t)
    rel = int(0.12 * SR)
    x[-rel:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(rel) / rel)
    return vel * x / 2.6 * (0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.012, 0, 1)))


def sub(f, dur, vel=1.0):
    """Round sub bass: sine plus a little 2nd/3rd harmonic so it survives small speakers."""
    t = tt(dur + 0.12)
    env = (0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.02, 0, 1))) * np.exp(-t / 1.3)
    env *= 0.5 + 0.5 * np.cos(np.pi * np.clip((t - dur) / 0.12, 0, 1))
    w = 2 * np.pi * f * t
    return vel * env * (np.sin(w) + 0.30 * np.sin(2 * w) + 0.08 * np.sin(3 * w)) / 1.3


def shaker(vel, rng):
    t = tt(0.1)
    x = rng.standard_normal(len(t)) * (1 - np.exp(-t / 0.004)) * np.exp(-t / 0.016)
    return vel * filt(filt(x, "hp", 5500), "lp", 11000)


def padnote(f, dur):
    t = tt(dur)
    env = (0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.9, 0, 1))) * (0.5 + 0.5 * np.cos(np.pi * np.clip((t - dur + 0.8) / 0.8, 0, 1)))
    return np.column_stack([env * (np.sin(2 * np.pi * f * d * t) + 0.12 * np.sin(4 * np.pi * f * d * t))
                            for d in (2 ** (3 / 1200), 2 ** (-3 / 1200))])


def bell(f, dur, tau, bright=1.0, warm=0.3):
    """Glass/felt chime: soft 6 ms attack, mostly fundamental, a warm sub-octave, short-lived upper partials."""
    t = tt(dur)
    parts = ((0.5, warm, 1.1), (1, 1, 1), (2, 0.16, 0.55), (3, 0.05, 0.35), (4.02, 0.10 * bright, 0.22), (5.4, 0.03 * bright, 0.12))
    x = sum(a * np.exp(-t / (tau * k)) * np.sin(2 * np.pi * f * r * t) for r, a, k in parts)
    return x * (0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.006, 0, 1)))


def click(dur, modes, band, burst_tau, burst_amp, rng, bounce=(0.0, 0.0)):
    """Struck-housing click: a short contact burst rings the housing modes (f, amp, tau); plus one contact bounce."""
    t = tt(dur - PRE)
    burst = rng.standard_normal(len(t)) * np.exp(-t / burst_tau)
    body = signal.fftconvolve(burst, sum(a * np.exp(-t / tau) * np.sin(2 * np.pi * f * t) for f, a, tau in modes))[:len(t)]
    snap = filt(filt(burst, "hp", band[0]), "lp", band[1])
    body = filt(body, "hp", 180)  # the random burst otherwise leaks a different amount of thump into every click
    x = body / np.abs(body).max() + burst_amp * snap / np.abs(snap).max()
    k = int(bounce[0] * SR)
    if k:
        x[k:] += bounce[1] * x[:-k]
    return np.concatenate([np.zeros(int(PRE * SR)), x])


def thud(dur, f0, f1, t_pitch, tau, drive, noise_fc, noise_amt, rng, octave=0.0):
    """Pitch-dropping sine through soft saturation plus a low noise thump. Mono."""
    t = tt(dur)
    ph = 2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t / t_pitch)) / SR
    x = np.tanh(drive * (np.sin(ph) + octave * np.sin(2 * ph)) * np.exp(-t / tau)) / np.tanh(drive) * (1 - np.exp(-t / 0.003))
    return x + noise_amt * filt(rng.standard_normal(len(t)) * np.exp(-t / 0.025), "lp", noise_fc)


def airy(dur, fc, width, peak_at, rng, sweep_pan=0.15):
    """Stereo band-swept noise with a bell envelope peaking at peak_at (0..1)."""
    u = tt(dur) / dur
    env = np.sin(np.pi * u ** (np.log(0.5) / np.log(peak_at))) ** 2
    mid, side = swept(dur, fc, width, rng), filt(swept(dur, fc, width, rng), "hp", 500)
    a = np.pi / 4 + (u - 0.5) * sweep_pan
    return np.column_stack([(mid + 0.4 * side) * np.cos(a), (mid - 0.4 * side) * np.sin(a)]) * env[:, None]


def roomy(x, rt, mix, seed):
    return x[:, None] + mix * wet(x, rt, seed)


# ---------- sound effects ----------
def sfx():
    rng = np.random.default_rng(2026)
    out = {}

    def bend(lo, pk, hi, at):
        return lambda u: np.where(u < at, lo * (pk / lo) ** (u / at), pk * (hi / pk) ** ((u - at) / (1 - at)))

    out["sfx/whoosh_short.wav"] = (finalize(airy(0.35, bend(500, 2600, 900, 0.55), 1.0, 0.55, rng), 0.35, -20, 0.02, 0.04),
                                   "Airy pass-by for quick cuts, card swipes, text wipes. Peak at ~0.19 s: put that on the cut.")
    out["sfx/whoosh_long.wav"] = (finalize(airy(0.9, bend(180, 1700, 350, 0.6), 1.15, 0.6, rng), 0.9, -18, 0.05, 0.12),
                                  "Deeper, slower whoosh for big camera moves and scene transitions. Peak at ~0.54 s.")

    relay = [(1850, 0.8, 0.009), (2750, 1.0, 0.006), (3900, 0.5, 0.004), (5400, 0.25, 0.0025), (420, 0.5, 0.007)]
    tick = click(0.09, relay, (2000, 8000), 0.0008, 0.5, rng, (0.0035, 0.3))
    tock = click(0.09, [(f * 0.74, a, tau * 1.25) for f, a, tau in relay[:4]] + [(300, 0.55, 0.009)],
                 (1200, 5000), 0.001, 0.4, rng, (0.005, 0.2))
    out["sfx/tick.wav"] = (finalize(tick, 0.09, -25), "Brand sound: turn-signal relay 'tick' (contact closes). Dry, centre. Place ON the beat; pair with tock half a beat later.")
    out["sfx/tock.wav"] = (finalize(tock, 0.09, -25), "Brand sound: turn-signal relay 'tock' (contact opens), lower and duller. Place on the off-beat, 0.341 s after tick.")

    t = tt(0.2)
    ph = 2 * np.pi * np.cumsum(330 + 190 * np.exp(-t / 0.018)) / SR
    pop = (np.sin(ph) * np.exp(-t / 0.032) + 0.12 * np.sin(2 * ph) * np.exp(-t / 0.02)) * (1 - np.exp(-t / 0.002))
    pop += 0.25 * filt(filt(rng.standard_normal(len(t)) * np.exp(-t / 0.006), "hp", 700), "lp", 2600)
    out["sfx/pop.wav"] = (finalize(roomy(np.concatenate([np.zeros(int(PRE * SR)), pop]), 0.18, 0.12, 11), 0.2, -22, 0.003, 0.03), "Soft cork-like pop for a card or chip appearing.")

    def phrase(dur, notes, rt, mix, seed):
        buf = np.zeros(int(dur * SR))
        for at, m, vel, tau, bright in notes:
            add(buf, vel * bell(midi(m), dur - at, tau, bright), at)
        return roomy(buf, rt, mix, seed)

    out["sfx/chime_stay.wav"] = (finalize(phrase(0.9, [(0, 81, 0.8, 0.26, 0.6), (0.24, 74, 1.0, 0.34, 0.8)], 0.7, 0.22, 21), 0.9, -18, 0.004, 0.08),
                                 "The 'Stay' answer: calm warm two-note chime, A5 falling a fifth to the tonic D5 (second note at 0.24 s).")
    out["sfx/chime_info.wav"] = (finalize(phrase(0.6, [(0, 76, 0.4, 0.12, 1.0), (0.07, 81, 1.0, 0.22, 1.7)], 0.5, 0.2, 22), 0.6, -18, 0.004, 0.06),
                                 "Speed / info callout: brighter single A5 with a quick rising E5 grace note (main note at 0.07 s).")
    out["sfx/chime_done.wav"] = (finalize(phrase(1.4, [(0, 69, 0.85, 0.28, 0.8), (0.19, 74, 0.9, 0.30, 0.8), (0.38, 78, 1.0, 0.42, 1.0), (0.38, 62, 0.35, 0.5, 0.3)],
                                                 0.9, 0.24, 23), 1.4, -18, 0.004, 0.12),
                                 "Trip report / completion: three-note resolved phrase A4-D5-F#5 landing on a D chord (notes at 0, 0.19, 0.38 s).")

    out["sfx/impact_soft.wav"] = (finalize(thud(0.5, 170, 73.42, 0.04, 0.09, 1.5, 600, 0.6, rng, 0.35), 0.5, -18, 0.003, 0.05),
                                  "Soft low thud for a title or card landing. Mono.")
    out["sfx/sub_hit.wav"] = (finalize(thud(1.2, 110, 36.71, 0.06, 0.28, 2.0, 220, 0.6, rng, 0.7), 1.2, -18, 0.003, 0.08),
                              "Deep sub hit with a short tail for a section change or logo reveal. Mono; mostly felt on full-range speakers.")

    u = tt(1.5) / 1.5
    ph = 2 * np.pi * np.cumsum(146.83 * 4 ** u) / SR
    tone = (np.sin(ph) + 0.3 * np.sin(2 * ph))[:, None]
    noise = np.column_stack([swept(1.5, lambda v: 250 * 12.8 ** v, 0.7, rng) for _ in range(2)])
    noise = filt(noise, "lp", 150).mean(axis=1, keepdims=True) + filt(noise, "hp", 150)  # decorrelated highs, mono lows
    riser = (noise + 0.10 * tone) * (u ** 2.2)[:, None]
    out["sfx/riser.wav"] = (finalize(riser, 1.5, -18, 0.05, 0.02), "Filtered-noise + tone riser (D3 to D5), builds to its end at 1.5 s: end it on the cut or on a hit.")

    hit = phrase(1.5, [(0, 62, 0.8, 0.4, 0.5), (0, 69, 0.7, 0.4, 0.5), (0, 74, 0.7, 0.36, 0.6), (0, 78, 0.5, 0.32, 0.6)], 1.3, 0.6, 24)
    out["sfx/swell_rev.wav"] = (finalize(hit[::-1], 1.5, -18, 0.15, 0.012), "Reversed D-major swell that ends abruptly at 1.5 s: end it exactly on a cut, then silence or a hit.")

    out["sfx/ui_tap.wav"] = (finalize(click(0.06, [(320, 1.0, 0.005), (2300, 0.35, 0.002)], (1500, 5000), 0.0008, 0.3, rng), 0.06, -31),
                             "Tiny fingertip tap for a button press or toggle.")

    u = tt(0.4) / 0.4
    glide = np.sin(2 * np.pi * np.cumsum(293.66 * 1.5 ** smooth(u)) / SR) * np.sin(np.pi * u) ** 2
    slide = airy(0.4, lambda v: 500 * 2.6 ** smooth(v), 0.45, 0.5, rng, 0) + 0.05 * glide[:, None]
    out["sfx/slide.wav"] = (finalize(slide, 0.4, -22, 0.03, 0.05), "Smooth narrow-band slide for the car or marker moving one lane. No pan baked in: pan it in the mix to match direction.")

    out["sfx/count.wav"] = (finalize(click(0.03, [(2600, 1.0, 0.0016), (1500, 0.6, 0.0025)], (2000, 7000), 0.0005, 0.4, rng), 0.03, -33),
                            "Very short tick for number count-ups; repeat at most ~20 per second.")
    return out


# ---------- music ----------
CHORDS = {  # marimba arpeggio tones, felt-key voicing (shared top notes for smooth voice leading), bass root
    "D": ([62, 69, 74, 78], [50, 57, 64, 66], 38),
    "Bm": ([59, 66, 69, 74], [47, 57, 62, 66], 35),
    "G": ([59, 62, 67, 74], [43, 59, 62, 66], 31),
    "A": ([57, 64, 69, 73], [45, 57, 61, 64], 33),
}


def bed():
    rng = np.random.default_rng(88)
    n = int(round(BARS * BAR * SR))
    mar, pad, shk = np.zeros((n, 2)), np.zeros((n, 2)), np.zeros((n, 2))
    keys, low = np.zeros(n), np.zeros(n)
    prog = ["D", "Bm", "G", "A"] * 8 + ["G", "A", "D"]
    for b, name in enumerate(prog):
        arp, voicing, root = CHORDS[name]
        t0 = b * BAR
        last, lift = b == BARS - 1, LIFT_BAR - 1 <= b < 32
        sparse = b < 4 or b in (32, 33)
        groove = not sparse and not last

        def at(beat):  # downbeats stay exactly on the grid; everything else lands up to 5 ms late
            return t0 + beat * SPB + (rng.uniform(0, 0.005) if beat else 0)

        if last:
            hits = [(i * 0.25, i, 0.8) for i in range(4)]
        elif sparse:
            hits = [(0, 0, 0.75), (1.5, 2, 0.55), (2.5, 1, 0.6)]
        else:
            hits = [(0, 0, 1.0), (0.75, 2, 0.55), (1.5, 1, 0.75), (2.5, 3, 0.85), (3.5, 2, 0.55)]
            if b % 2:
                hits = [(0, 0, 1.0), (1.0, 1, 0.6), (1.5, 2, 0.75), (2.5, 1, 0.8), (3.25, 3, 0.55)]
        for k, (beat, i, vel) in enumerate(hits):
            add(mar, pan(mallet(midi(arp[i]), vel * rng.uniform(0.9, 1)), (-1) ** k * 0.18), at(beat))
        if lift:
            for beat, i in ((1.0, 1), (3.0, 2)):
                add(mar, pan(mallet(midi(arp[i] + 12), 0.4, 0.3), 0.3), at(beat))
            for m in voicing[2:]:
                add(pad, padnote(midi(m), BAR + 0.8), t0)

        for j, m in enumerate(voicing + ([69] if last else [])):
            add(keys, felt(midi(m), 0.55 if sparse else 0.8, BAR), t0 + j * 0.008)
        if groove:
            for m in voicing[2:]:
                add(keys, felt(midi(m), 0.4, 1.5 * SPB), at(2.5))
            add(low, sub(midi(root), 1.75 * SPB), t0)
            add(low, sub(midi(root), 0.5 * SPB, 0.7), t0 + 2.5 * SPB)
            if lift:
                add(low, sub(midi(root), 0.4 * SPB, 0.55), t0 + 3.5 * SPB)
            for s in range(16):
                vel = (0.22 if lift else 0) if s % 2 else (1.0 if s % 4 == 2 else 0.45)
                if vel:
                    add(shk, pan(shaker(vel * rng.uniform(0.85, 1), rng), 0.25), t0 + s / 4 * SPB + rng.uniform(0, 0.004))
        elif b >= 32:
            add(low, sub(midi(root), 3 * SPB, 0.9), t0)

    dry = mar * 0.9 + keys[:, None] * 0.5 + pad * 0.12
    mix = dry + 0.22 * wet(dry.mean(axis=1), 1.3, 3) + low[:, None] * 0.55 + shk * 0.12
    return finalize(mix, BARS * BAR, -22, 0.003, 1.5 * SPB, integrated)


def end_hit():
    rng = np.random.default_rng(4)
    n = int(4.0 * SR)
    buf = np.zeros(n)
    for j, m in enumerate([38, 45, 50, 57, 62, 66, 69]):
        add(buf, felt(midi(m), 0.8, 4.0, 1.8), PRE + j * 0.006)
    for j, m in enumerate([74, 78, 81, 86]):
        add(buf, 0.5 * mallet(midi(m), 0.8, 0.9), PRE + 0.02 + j * 0.012)
    low = np.zeros(n)
    add(low, 1.1 * thud(4.0, 150, 73.42, 0.05, 0.7, 1.8, 180, 0.5, rng), PRE)
    return finalize(roomy(buf, 2.6, 0.3, 5) + low[:, None], 4.0, -16, 0.003, 0.6)


# ---------- write + self-check ----------
def write(rel, x, desc, **extra):
    pcm = np.round(x * 32767).astype(np.int16)
    path = OUT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(path, SR, pcm)
    y = pcm / 32768
    tp, dc = db(true_peak(y)), float(np.abs(y.mean(axis=0)).max())
    assert pcm.shape[1] == 2 and tp <= -1.0, (rel, tp)
    assert dc < 1e-4, (rel, dc)
    assert np.abs(y[[0, -1]]).max() < 1e-3, (rel, "edge not silent")
    assert db(np.abs(y[:int(0.003 * SR)]).max()) < db(np.abs(y).max()) - 6, (rel, "no fade-in")
    meas = integrated if "bpm" in extra else momentary_max
    return {"name": Path(rel).stem, "file": rel, "duration_s": round(len(y) / SR, 4), "peak_dbfs": round(db(np.abs(y).max()), 2),
            "true_peak_dbtp": round(tp, 2), "lufs": round(meas(y), 1), "description": desc, **extra}


def main():
    files = [write(rel, x, desc) for rel, (x, desc) in sfx().items()]
    files.append(write("music/bed.wav", bed(),
                       "Music bed under the voice-over: marimba, felt keys, sub bass, light shaker. Starts on beat 1 at t=0; "
                       "ends on a ringing D chord faded to silence, so it loops on the bar grid or fades anywhere.",
                       bpm=BPM, seconds_per_beat=round(SPB, 6), seconds_per_bar=round(BAR, 6), bars=BARS, key="D major",
                       time_signature="4/4", lift_bar=LIFT_BAR, lift_time_s=round((LIFT_BAR - 1) * BAR, 4),
                       sections=[{"name": "intro (sparse)", "bars": "1-4", "start_s": 0},
                                 {"name": "groove", "bars": "5-16", "start_s": round(4 * BAR, 4)},
                                 {"name": "lift", "bars": "17-32", "start_s": round(16 * BAR, 4)},
                                 {"name": "wind-down", "bars": "33-34", "start_s": round(32 * BAR, 4)},
                                 {"name": "final D chord", "bars": "35", "start_s": round(34 * BAR, 4)}]))
    files.append(write("music/end_hit.wav", end_hit(), "End card: full D major chord with a low hit, rings about 4 s. Put its start on the final downbeat and fade the bed under it."))
    manifest = {
        "sample_rate": SR, "channels": 2, "bit_depth": 16,
        "licence": "All audio synthesised by video/scripts/make_audio.py; no third-party samples.",
        "loudness_note": "lufs = BS.1770 max momentary (400 ms) for SFX and end_hit, integrated for the bed. Clicks (tick, tock, ui_tap, count) start 3 ms into the file.",
        "blinker": {"flashes_per_minute": BPM, "tick_on": "beat", "tock_on": "half-beat", "half_beat_s": round(SPB / 2, 6)},
        "files": files,
    }
    (OUT / "audio-manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    for f in files:
        print(f"{f['file']:24s} {f['duration_s']:8.3f}s  peak {f['peak_dbfs']:6.2f} dBFS  tp {f['true_peak_dbtp']:6.2f}  {f['lufs']:6.1f} LUFS")


if __name__ == "__main__":
    main()
