"""Verification helpers used after every major step."""
from __future__ import annotations

from pathlib import Path

from .ffmpeg import FF


def stream_summary(ff: FF, media: Path) -> dict:
    info = ff.probe(media)
    streams = info.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    d = float(info.get("format", {}).get("duration", 0) or 0)
    size = int(info.get("format", {}).get("size", 0) or 0)
    return {"video": v, "audio": a, "duration": d, "size": size}


def verify_has_av(ff: FF, media: Path, require_audio: bool = True) -> dict:
    s = stream_summary(ff, media)
    if not s["video"]:
        raise RuntimeError(f"Verification failed: no video stream in {media.name}.")
    if require_audio and not s["audio"]:
        raise RuntimeError(f"Verification failed: no audio stream in {media.name}.")
    if s["duration"] <= 0:
        raise RuntimeError(f"Verification failed: {media.name} has no valid duration.")
    return s


def describe(s: dict) -> str:
    v, a = s["video"], s["audio"]
    vp = f"{v.get('codec_name')} {v.get('width')}x{v.get('height')} {v.get('r_frame_rate')}" if v else "no video"
    ap = f"{a.get('codec_name')} {a.get('sample_rate')}Hz {a.get('channels')}ch" if a else "no audio"
    return f"{vp} | {ap}"
