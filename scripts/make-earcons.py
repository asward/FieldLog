#!/usr/bin/env python3
"""Generate FieldLog's earcons (short UI sounds) as 16-bit mono WAVs.

Usage: python3 scripts/make-earcons.py android/app/src/main/res/raw

Sounds are synthesised so they're reproducible and license-free. Each is a
sine sweep or chord with a quick attack and exponential decay, quiet enough
to sit under speech.
"""
import math
import struct
import sys
import wave
from pathlib import Path

RATE = 22050


def tone(f0, f1, ms, amp=0.5, decay=6.0, harmonics=((1, 1.0), (2, 0.25))):
    n = int(RATE * ms / 1000)
    out = []
    phase = [0.0] * len(harmonics)
    for i in range(n):
        t = i / n
        f = f0 + (f1 - f0) * t
        env = min(1.0, i / (RATE * 0.004)) * math.exp(-decay * t)
        s = 0.0
        for k, (mult, weight) in enumerate(harmonics):
            phase[k] += 2 * math.pi * f * mult / RATE
            s += weight * math.sin(phase[k])
        out.append(amp * env * s / sum(w for _, w in harmonics))
    return out


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def chord(freqs, ms, amp=0.45, decay=4.0):
    parts = [tone(f, f, ms, amp / len(freqs), decay, ((1, 1.0), (3, 0.08))) for f in freqs]
    return [sum(p[i] for p in parts) for i in range(len(parts[0]))]


def buzz(ms, f=150, amp=0.5):
    n = int(RATE * ms / 1000)
    out = []
    for i in range(n):
        t = i / n
        env = min(1.0, i / (RATE * 0.003)) * (1 - t) ** 1.5
        # Square-ish wave, softened.
        s = math.tanh(3 * math.sin(2 * math.pi * f * i / RATE))
        out.append(amp * env * s)
    return out


SOUNDS = {
    # Mic ready: soft, short rise.
    "fb_ready": tone(520, 700, 110, amp=0.30),
    # Entering a mode: clear rise.
    "fb_enter": tone(600, 900, 120, amp=0.45),
    # Entering TAG: two rises.
    "fb_enter_double": tone(600, 900, 90, amp=0.45) + silence(40) + tone(700, 1050, 90, amp=0.45),
    # Leaving a mode: fall.
    "fb_exit": tone(900, 560, 130, amp=0.45),
    # Not allowed / failed: low buzz.
    "fb_buzz": buzz(220),
    # Committed: major third chime.
    "fb_chime": chord([880, 1108.7], 380),
    # Pushed: a fifth above.
    "fb_chime2": chord([1318.5, 1661.2], 380),
}


def write(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "android/app/src/main/res/raw")
    out.mkdir(parents=True, exist_ok=True)
    for name, samples in SOUNDS.items():
        write(out / f"{name}.wav", samples)
        print(f"{name}.wav  {len(samples) / RATE * 1000:.0f} ms")


if __name__ == "__main__":
    main()
