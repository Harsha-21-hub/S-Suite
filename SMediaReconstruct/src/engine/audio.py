"""Audio boundary / beep-click detection and localized repair.

Detection is dependency-free: a short PCM window is extracted around each
boundary with FFmpeg and analyzed with the stdlib `array` module. A click is
flagged from a sharp sample-to-sample discontinuity at the seam relative to the
local signal, optionally corroborated by an RMS energy jump. Repair is a very
short (~1-5 ms) endpoint fade applied only at flagged boundaries — never a
timeline-wide crossfade, which caused cumulative drift previously.
"""
from __future__ import annotations

import array
import math
import tempfile
from pathlib import Path

from .ffmpeg import FF
from .util import inv_float

FULL_SCALE = 32768.0
WINDOW = 0.040          # seconds captured either side of a boundary
SR = 48000              # analysis sample rate (mono)
CLICK_FACTOR = 6.0      # seam delta vs local baseline delta
ABS_FLOOR = 0.14        # seam delta must also exceed this fraction of full scale
RMS_RATIO = 6.0         # energy discontinuity ratio flag


def _extract_pcm(ff: FF, media: Path, center: float) -> array.array:
    start = max(0.0, center - WINDOW)
    dur = WINDOW * 2
    tmp = Path(tempfile.mkdtemp(prefix="smr_pcm_")) / "seam.raw"
    ff.run(["-y", "-ss", inv_float(start), "-i", str(media), "-t", inv_float(dur),
            "-map", "0:a:0", "-ac", "1", "-ar", str(SR), "-f", "s16le",
            "-c:a", "pcm_s16le", str(tmp)], label="Boundary probe")
    data = tmp.read_bytes()
    try:
        tmp.unlink()
        tmp.parent.rmdir()
    except Exception:
        pass
    samples = array.array("h")
    samples.frombytes(data[: len(data) - (len(data) % 2)])
    return samples


def _rms(samples, lo: int, hi: int) -> float:
    lo = max(0, lo)
    hi = min(len(samples), hi)
    if hi <= lo:
        return 0.0
    acc = 0.0
    for i in range(lo, hi):
        v = samples[i] / FULL_SCALE
        acc += v * v
    return math.sqrt(acc / (hi - lo))


def analyze_boundary(ff: FF, media: Path, center: float) -> dict:
    """Return a verdict for one boundary: clean / suspicious with metrics."""
    try:
        s = _extract_pcm(ff, media, center)
    except Exception as e:
        return {"center": center, "verdict": "unknown", "error": str(e)}
    if len(s) < 8:
        return {"center": center, "verdict": "clean", "reason": "too-short"}

    # The seam sits near the window midpoint (center - WINDOW start offset).
    seam = min(len(s) - 1, int(round(WINDOW * SR)))
    seam = max(1, seam)

    seam_delta = abs(s[seam] - s[seam - 1]) / FULL_SCALE
    deltas = [abs(s[i] - s[i - 1]) / FULL_SCALE for i in range(max(1, seam - 200), min(len(s), seam + 200))]
    deltas_sorted = sorted(deltas)
    baseline = deltas_sorted[len(deltas_sorted) // 2] if deltas_sorted else 0.0

    left_rms = _rms(s, seam - int(0.02 * SR), seam)
    right_rms = _rms(s, seam, seam + int(0.02 * SR))
    lo, hi = sorted((left_rms + 1e-6, right_rms + 1e-6))
    rms_ratio = hi / lo

    click = seam_delta > ABS_FLOOR and seam_delta > CLICK_FACTOR * (baseline + 1e-4)
    energy_jump = rms_ratio > RMS_RATIO and hi > 0.02

    verdict = "suspicious" if (click and energy_jump) or (click and seam_delta > 0.30) else "clean"
    return {
        "center": round(center, 3), "verdict": verdict,
        "seam_delta": round(seam_delta, 4), "baseline": round(baseline, 4),
        "rms_ratio": round(rms_ratio, 2), "click": click, "energy_jump": energy_jump,
    }


def scan_boundaries(ff, media: Path, boundaries: list[float], log, on_progress=None) -> dict:
    scanned = clean = suspicious = 0
    flagged: list[float] = []
    details: list[dict] = []
    n = len(boundaries)
    for i, b in enumerate(boundaries, 1):
        r = analyze_boundary(ff, media, b)
        details.append(r)
        scanned += 1
        if r["verdict"] == "suspicious":
            suspicious += 1
            flagged.append(b)
            log(f"  boundary {b:.3f}s SUSPICIOUS  seam={r.get('seam_delta')} rms={r.get('rms_ratio')}")
        else:
            clean += 1
        if on_progress:
            on_progress(i / max(1, n) * 100.0)
    return {"scanned": scanned, "clean": clean, "suspicious": suspicious,
            "flagged": flagged, "details": details}


def endpoint_protect_filter(fade_ms: float = 3.0) -> str:
    """A tiny symmetric endpoint fade for one clip's audio: fade its head and
    tail by `fade_ms` only. Applied per-clip, so no cumulative timeline drift."""
    d = max(0.001, fade_ms / 1000.0)
    return (f"afade=t=in:st=0:d={inv_float(d)},"
            f"areverse,afade=t=in:st=0:d={inv_float(d)},areverse")
