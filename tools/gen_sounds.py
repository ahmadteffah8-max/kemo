"""Original electronic UI sounds + chiptune BGM for KEMO (no samples of any real voice)."""
import os, sys, wave
import numpy as np

SR = 22050
OUT = os.path.join(sys.argv[1] if len(sys.argv) > 1 else ".", "app/src/main/res/raw")
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(39)

def t_(dur): return np.arange(int(SR * dur)) / SR
def hz(semi_from_a4): return 440.0 * 2 ** (semi_from_a4 / 12)
NOTE = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
def note(name, octave):  # e.g. note("E",5)
    return hz(NOTE[name] + (octave - 4) * 12)

def adsr(n, a=0.004, d=0.05, s=0.6, r=0.05):
    e = np.ones(n)
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    na = max(na, 1)
    e[:na] = np.linspace(0, 1, na)
    if nd > 0 and na + nd < n:
        e[na:na + nd] = np.linspace(1, s, nd)
        e[na + nd:] = s
    if nr > 0 and nr < n:
        e[-nr:] *= np.linspace(1, 0, nr)
    return e

def voice(freq, dur, f1=800, f2=2800, vib=5.5, vibd=0.006, glide=0.0, glide_t=0.03, a=0.006, r=0.06, sustain=0.7):
    """Additive 'digital vocal' blip: harmonic stack shaped by two formants (vowel-like)."""
    t = t_(dur)
    f = np.full_like(t, freq)
    if glide:
        f = f * (2 ** (glide * np.clip(1 - t / glide_t, 0, 1) / 12))
    f = f * (1 + vibd * np.sin(2 * np.pi * vib * t) * np.clip(t / 0.08, 0, 1))
    phase = 2 * np.pi * np.cumsum(f) / SR
    y = np.zeros_like(t)
    for k in range(1, 22):
        fk = freq * k
        if fk > SR * 0.45: break
        w = np.exp(-((fk - f1) / 650) ** 2) + 0.55 * np.exp(-((fk - f2) / 900) ** 2) + 0.12 / k
        y += w * np.sin(k * phase) / (k ** 0.35)
    y /= np.max(np.abs(y)) + 1e-9
    return y * adsr(len(t), a, 0.05, sustain, r)

def bell(freq, dur, ratio=3.5, idx=2.0, decay=6.0):
    t = t_(dur)
    mod = np.sin(2 * np.pi * freq * ratio * t) * idx * np.exp(-decay * t)
    return np.sin(2 * np.pi * freq * t + mod) * np.exp(-decay * 0.6 * t) * adsr(len(t), 0.002, 0, 1, 0.02)

def noise(dur, lp=None):
    n = rng.standard_normal(int(SR * dur))
    if lp:
        k = max(int(SR / lp), 1)
        n = np.convolve(n, np.ones(k) / k, mode="same")
    return n / (np.max(np.abs(n)) + 1e-9)

def echo(y, delays=(0.09, 0.18), gains=(0.35, 0.18), tail=0.25):
    out = np.zeros(len(y) + int(SR * tail))
    out[:len(y)] += y
    for d, g in zip(delays, gains):
        i = int(SR * d)
        out[i:i + len(y)] += y * g
    return out

def crush(y, rate):
    step = max(int(SR / rate), 1)
    z = np.repeat(y[::step], step)[:len(y)]
    return z

def pad(y, n):
    out = np.zeros(n); out[:min(n, len(y))] = y[:n]; return out

def mix(*parts):
    n = max(len(p[0]) + int(p[1] * SR) for p in parts)
    out = np.zeros(n)
    for y, off in parts:
        i = int(off * SR); out[i:i + len(y)] += y
    return out

def save(name, y, peak=0.85):
    y = y / (np.max(np.abs(y)) + 1e-9) * peak
    # tiny fade out to avoid click
    f = int(0.004 * SR); y[-f:] *= np.linspace(1, 0, f)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype(np.int16).tobytes())
    print(f"{name}.wav {len(y)/SR:.2f}s")

# ---------- key clicks: one cute note per digit (pentatonic), different vowels ----------
SCALE = [("C", 5), ("D", 5), ("E", 5), ("G", 5), ("A", 5), ("C", 6), ("D", 6), ("E", 6), ("G", 6), ("A", 6)]
VOW = [(800, 1150), (550, 1900), (320, 2500), (480, 800), (650, 1100)]
for d in range(10):
    n, o = SCALE[d]
    f1, f2 = VOW[d % 5]
    y = voice(note(n, o), 0.13, f1, f2 + 600, glide=-3, glide_t=0.025, r=0.07, sustain=0.5)
    y += 0.18 * pad(bell(note(n, o) * 2, 0.10, 2.0, 1.0, 22), len(y))
    tick = np.zeros(len(y)); k = noise(0.006, 7000); tick[:len(k)] = k * np.linspace(1, 0, len(k)) * 0.25
    save(f"key_{d}", echo(y + tick, (0.07,), (0.22,), 0.1))

# operators: a two-note "pi-po" with a warmer vowel
for name, (a, b) in {"key_op": (("E", 5), ("B", 5)), "key_dot": (("G", 6), ("G", 6)), "key_paren": (("A", 5), ("E", 6))}.items():
    if name == "key_dot":
        y = voice(note(*a), 0.06, 400, 3200, r=0.04, sustain=0.5)
    elif name == "key_paren":
        y = bell(note(*a), 0.22, 3.0, 1.5, 9) * 0.8 + bell(note(*b), 0.22, 3.0, 1.5, 9) * 0.5
    else:
        y = mix((voice(note(*a), 0.08, 700, 2400, r=0.04), 0), (voice(note(*b), 0.12, 600, 2600, r=0.07, glide=-2), 0.07))
    save(name, echo(y, (0.08,), (0.2,), 0.12))

# ---------- delete: pixel disintegration ----------
parts = []
for i in range(9):
    f = rng.uniform(900, 2600) * (0.85 ** i)
    t = t_(0.045)
    sq = np.sign(np.sin(2 * np.pi * f * t)) * 0.5
    parts.append((sq * np.exp(-t * 55), i * 0.022))
parts.append((0.35 * noise(0.2, 3500) * np.exp(-t_(0.2) * 18), 0))
y = mix(*parts)
save("del_pixel", crush(y, 5500))

# ---------- result: magical ascending arpeggio with sparkles ----------
arp = [("C", 5), ("E", 5), ("G", 5), ("C", 6), ("E", 6), ("G", 6)]
parts = []
for i, (n, o) in enumerate(arp):
    parts.append((voice(note(n, o), 0.22, 700 + 100 * i, 2800, r=0.12, sustain=0.6) * 0.8, i * 0.075))
    parts.append((bell(note(n, o) * 2, 0.5, 3.5, 2.0, 7) * 0.35, i * 0.075))
parts.append((voice(note("C", 6), 0.7, 800, 3000, vibd=0.01, r=0.4, sustain=0.5) * 0.5, 0.45))
parts.append((bell(note("G", 6), 0.9, 3.5, 2.5, 5) * 0.4, 0.45))
save("result_chime", echo(mix(*parts), (0.12, 0.25, 0.4), (0.3, 0.2, 0.12), 0.5))

# ---------- clear: digital vanish ----------
n = int(SR * 0.5); t = np.arange(n) / SR
f = 3200 * np.exp(-t * 5.5)
sweep = np.sign(np.sin(2 * np.pi * np.cumsum(f) / SR)) * 0.35 * np.exp(-t * 4)
spark = sum(bell(note(*x), 0.28, 3.5, 1.5, 11) * 0.3 for x in [])
parts = [(sweep, 0), (0.25 * noise(0.5, 5000) * np.exp(-t * 7), 0)]
for i, (nn, oo) in enumerate([("A", 6), ("E", 6), ("C", 6), ("G", 5), ("E", 5)]):
    parts.append((bell(note(nn, oo), 0.3, 3.5, 1.2, 12) * 0.5, i * 0.05))
save("clear_vanish", crush(mix(*parts), 9000))

# ---------- startup jingle ----------
parts = []
for i, (nn, oo, v) in enumerate([("E", 5, (320, 2500)), ("G", 5, (550, 1900)), ("C", 6, (800, 1150)), ("E", 6, (650, 1100))]):
    parts.append((voice(note(nn, oo), 0.35, v[0], v[1], r=0.2, sustain=0.65), i * 0.17))
    parts.append((bell(note(nn, oo) * 2, 0.6, 3.5, 2.0, 6) * 0.3, i * 0.17))
parts.append((sum(voice(note(nn, oo), 0.9, 700, 2600, vibd=0.01, r=0.5, sustain=0.5) * 0.35 for nn, oo in [("C", 5), ("G", 5), ("E", 6)]), 0.7))
save("startup", echo(mix(*parts), (0.15, 0.3, 0.45), (0.3, 0.2, 0.12), 0.6))

# ---------- chiptune BGM: 8 bars, loops ----------
BPM = 118
step = 60 / BPM / 4                       # 16th note
bars = 8
total = int(SR * step * 16 * bars)
buf = np.zeros(total + SR)

def put(y, pos_steps):
    i = int(pos_steps * step * SR); buf[i:i + len(y)] += y[:max(0, len(buf) - i)]

def pulse(freq, dur, duty=0.25, vol=0.3, decay=0.0):
    t = t_(dur)
    ph = (freq * t) % 1
    y = np.where(ph < duty, 1.0, -1.0) * vol
    return y * adsr(len(t), 0.002, 0.02, 0.7, 0.02) * np.exp(-decay * t)

def tri(freq, dur, vol=0.45):
    t = t_(dur)
    ph = (freq * t) % 1
    y = (2 * np.abs(2 * ph - 1) - 1) * vol
    return y * adsr(len(t), 0.002, 0.02, 0.9, 0.02)

def kick():
    t = t_(0.14); f = 140 * np.exp(-t * 28) + 40
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 20) * 0.7

def snare():
    t = t_(0.12); return noise(0.12, 7000) * np.exp(-t * 28) * 0.35 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 35) * 0.25

def hat(o=False):
    t = t_(0.09 if o else 0.03); n = noise(len(t) / SR)
    n = n - np.convolve(n, np.ones(8) / 8, mode="same")
    return n * np.exp(-t * (30 if o else 90)) * 0.2

prog = [("A", [0, 3, 7]), ("F", [0, 4, 7]), ("C", [0, 4, 7]), ("G", [0, 4, 7])]   # semitone offsets
root_semi = {"A": -24, "F": -28, "C": -33, "G": -26}        # bass roots (A2,F2,C2..)
mel = {  # melody per 2-bar chord in 16th steps: (step, semitone from A4 , length)
    0: [(0, 12, 3), (4, 10, 2), (6, 7, 2), (8, 10, 4), (12, 7, 3)],
    1: [(0, 12, 3), (4, 9, 2), (6, 5, 2), (8, 9, 4), (12, 12, 3)],
    2: [(0, 7, 3), (4, 3, 2), (6, 7, 2), (8, 10, 4), (12, 12, 3)],
    3: [(0, 14, 3), (4, 10, 2), (6, 7, 2), (8, 5, 6), (14, 7, 2)],
}
for c, (chord, offs) in enumerate(prog):
    for half in range(2):
        bar0 = (c * 2 + half) * 16
        base = {"A": 0, "F": -4, "C": -9, "G": -2}[chord] + 12          # chord root above A4... in semitones
        for s in range(16):
            # arpeggio 16ths
            semi = base + offs[(s + (1 if s % 4 == 3 else 0)) % 3] + (12 if s % 8 >= 4 else 0)
            put(pulse(hz(semi), step * 0.9, 0.125, 0.12, decay=6), bar0 + s)
            # bass 8ths (triangle)
            if s % 2 == 0:
                put(tri(hz(root_semi[chord] + (12 if s % 8 == 6 else 0)), step * 1.8), bar0 + s)
            # drums
            if s % 8 == 0 or (s == 10): put(kick(), bar0 + s)
            if s % 8 == 4: put(snare(), bar0 + s)
            if s % 2 == 0: put(hat(s % 8 == 6), bar0 + s)
        if half == 0 or True:
            for (st, semi, ln) in mel[c]:
                if half == 1 and st == 12: semi += 2
                put(pulse(hz(semi + 12 if half else semi + 12), step * ln, 0.5, 0.16, decay=1.5), bar0 + st)
# wrap tail so the loop is seamless
tail = buf[total:]
buf = buf[:total]; buf[:len(tail)] += tail[:total]
save("kemo_bgm", buf, peak=0.7)
