# Soft Apple TV style navigation and select sounds for res/raw (16-bit mono WAV, 48 kHz like the Fire TV output).
# Usage: python3 make_appletv_sfx.py launcher/app/src/main/res/raw
# nav: a very short, muted "tick" (a damped high partial over a softer octave below and a 1 ms breath of noise).
# select: a rounder, lower "tock" with a slightly longer tail. Both kept quiet on purpose.
import math, random, struct, sys, wave

RATE = 48000
out = sys.argv[1]
random.seed(3)


def write(name, s, gain):
    peak = max(abs(x) for x in s) or 1
    with wave.open(f"{out}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(x / peak * gain * 32767)) for x in s))


def tone(ms, parts, attack_ms=0.4, noise=0.0, noise_tau=1.0):
    n = int(RATE * ms / 1000)
    ph = [0.0] * len(parts)
    s = []
    for i in range(n):
        t = i / RATE * 1000
        v = 0.0
        for k, (f0, f1, amp, tau) in enumerate(parts):
            ph[k] += 2 * math.pi * (f0 + (f1 - f0) * min(1.0, t / ms)) / RATE
            v += amp * math.sin(ph[k]) * math.exp(-t / tau)
        v += noise * (random.random() * 2 - 1) * math.exp(-t / noise_tau)
        s.append(v * min(1.0, t / attack_ms))
    return s


def lowpass(s, a):
    o, y = [], 0.0
    for x in s:
        y += a * (x - y); o.append(y)
    return o


def room(s, mix, tail_ms):
    n = len(s) + int(RATE * tail_ms / 1000)
    dry = s + [0.0] * (n - len(s))
    wet = [0.0] * n
    for d_ms, fb in ((17.3, 0.35), (23.9, 0.3), (31.1, 0.25)):
        d = int(RATE * d_ms / 1000); b = [0.0] * n
        for i in range(n):
            b[i] = dry[i] + (fb * b[i - d] if i >= d else 0.0)
            wet[i] += b[i] / 3
    wet = lowpass(wet, 0.2)
    # fade the tail so it ends at silence
    return [(x + mix * w) * min(1.0, (n - i) / (RATE * 0.01)) for i, (x, w) in enumerate(zip(dry, wet))]


nav = tone(40, [(2350, 2250, 1.0, 5), (1175, 1150, 0.45, 8)], attack_ms=0.5, noise=0.25, noise_tau=0.8)
write("nav", room(lowpass(nav, 0.45), 0.18, 60), 0.30)
sel = tone(90, [(740, 700, 1.0, 22), (1480, 1420, 0.3, 10), (370, 360, 0.35, 30)], attack_ms=1.0, noise=0.15, noise_tau=1.2)
write("select", room(lowpass(sel, 0.35), 0.22, 110), 0.40)
