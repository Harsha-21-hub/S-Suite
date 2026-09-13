"""Dynamic input detection: mixed A/V, separate streams, or EXO caches."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .ffmpeg import FF
from .util import numeric_sort_key

VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".m4v", ".webm", ".ts", ".m2ts", ".avi"}
AUDIO_EXTS = {".m4a", ".aac", ".mp3", ".wav", ".flac", ".opus", ".ogg", ".wma"}
MEDIA_EXTS = VIDEO_EXTS | AUDIO_EXTS | {".mp4", ".mkv"}
EXO_EXT = ".exo"


@dataclass
class MediaFile:
    path: Path
    duration: float
    video: dict | None
    audio: dict | None


def collect(paths: list[Path], recursive: bool) -> list[Path]:
    out: list[Path] = []
    for p in paths:
        if p.is_file():
            out.append(p)
        elif p.is_dir():
            it = p.rglob("*") if recursive else p.glob("*")
            out.extend(x for x in it if x.is_file())
    seen, result = set(), []
    for p in sorted(out, key=lambda x: str(x).lower()):
        s = str(p.resolve())
        if s not in seen:
            seen.add(s)
            result.append(p)
    return result


def probe_media(ff: FF, files: list[Path], log) -> dict:
    media: list[MediaFile] = []
    for p in files:
        if p.suffix.lower() not in MEDIA_EXTS:
            continue
        try:
            info = ff.probe(p)
        except Exception:
            continue
        streams = info.get("streams", [])
        fmt = info.get("format", {})
        v = next((s for s in streams if s.get("codec_type") == "video"), None)
        a = next((s for s in streams if s.get("codec_type") == "audio"), None)
        d = float(fmt.get("duration", 0) or 0)
        if v or a:
            media.append(MediaFile(p, d, v, a))
    mixed = [m for m in media if m.video and m.audio]
    vo = [m for m in media if m.video and not m.audio]
    ao = [m for m in media if m.audio and not m.video]
    log(f"Media files: {len(media)} | Mixed A/V: {len(mixed)} | Video-only: {len(vo)} | Audio-only: {len(ao)}")
    return {"media": media, "mixed": mixed, "video_only": vo, "audio_only": ao}


def signature(m: MediaFile):
    v = None if not m.video else (
        m.video.get("codec_name"), m.video.get("width"), m.video.get("height"),
        m.video.get("r_frame_rate"), m.video.get("pix_fmt"),
    )
    a = None if not m.audio else (
        m.audio.get("codec_name"), m.audio.get("sample_rate"),
        m.audio.get("channels"), m.audio.get("channel_layout"),
    )
    return v, a


def compatible(items: list[MediaFile]) -> bool:
    return bool(items) and all(signature(x) == signature(items[0]) for x in items[1:])


def pair_separate(vs: list[MediaFile], aas: list[MediaFile], log) -> list[tuple[MediaFile, MediaFile]]:
    if len(vs) != len(aas):
        raise RuntimeError(
            f"Separate A/V count mismatch: {len(vs)} video vs {len(aas)} audio files. "
            "The source appears incomplete."
        )
    sv = sorted(vs, key=lambda x: numeric_sort_key(x.path))
    sa = sorted(aas, key=lambda x: numeric_sort_key(x.path))
    keys_v = [re.sub(r"[^a-z0-9]+", "", re.sub(r"(video|vid)", "", x.path.stem.lower())) for x in sv]
    keys_a = [re.sub(r"[^a-z0-9]+", "", re.sub(r"(audio|aud|sound)", "", x.path.stem.lower())) for x in sa]
    if keys_v != keys_a:
        log("Filename pairing was not exact; using deterministic sorted pairing because counts match.")
    return list(zip(sv, sa))
