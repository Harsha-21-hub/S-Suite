"""ISO-BMFF (MP4) box parsing helpers.

These are used to classify EXO/DASH initialization segments by their actual
trak/tkhd/mdia/hdlr contents and to associate moof fragments with real track
IDs and timestamps (tfhd/tfdt) — never by guessing from codec-name strings.
"""
from __future__ import annotations

import struct
import subprocess
from pathlib import Path

from .ffmpeg import FFPROBE_EXE, _NO_WINDOW


def iter_boxes(data: bytes, start: int = 0, end: int | None = None):
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        header = 8
        if size == 1:
            if pos + 16 > end:
                return
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            return
        yield typ, pos, size, header
        pos += size


def find_nested(data: bytes, wanted: bytes):
    for typ, pos, size, header in iter_boxes(data):
        payload = data[pos + header:pos + size]
        if typ == wanted:
            return payload
        found = find_nested(payload, wanted)
        if found is not None:
            return found
    return None


def find_all_nested(data: bytes, wanted: bytes) -> list[bytes]:
    found: list[bytes] = []
    for typ, pos, size, header in iter_boxes(data):
        payload = data[pos + header:pos + size]
        if typ == wanted:
            found.append(payload)
        found.extend(find_all_nested(payload, wanted))
    return found


def init_track_infos(path: Path) -> list[dict]:
    try:
        data = path.read_bytes()
    except Exception:
        return []
    infos = []
    for trak in find_all_nested(data, b"trak"):
        tkhd = find_nested(trak, b"tkhd")
        mdia = find_nested(trak, b"mdia")
        hdlr = find_nested(mdia or b"", b"hdlr")
        if not tkhd or not hdlr or len(hdlr) < 12:
            continue
        version = tkhd[0]
        if version == 1 and len(tkhd) >= 24:
            track_id = struct.unpack(">I", tkhd[20:24])[0]
        elif len(tkhd) >= 16:
            track_id = struct.unpack(">I", tkhd[12:16])[0]
        else:
            continue
        handler = hdlr[8:12].decode("ascii", errors="ignore")
        infos.append({"track_id": track_id, "handler": handler})
    return infos


def safe_init_track_id(path: Path) -> int | None:
    """Read track_ID from tkhd deterministically (no codec-string matching)."""
    try:
        data = path.read_bytes()
    except Exception:
        return None
    tracks = find_all_nested(data, b"trak")
    if not tracks:
        infos = init_track_infos(path)
        return infos[0]["track_id"] if infos else None
    for trak in tracks:
        tkhd = find_nested(trak, b"tkhd")
        if not tkhd:
            continue
        version = tkhd[0]
        if version == 1 and len(tkhd) >= 24:
            return struct.unpack(">I", tkhd[20:24])[0]
        if len(tkhd) >= 16:
            return struct.unpack(">I", tkhd[12:16])[0]
    return None


def fragment_track_id(data: bytes) -> int | None:
    p = find_nested(data, b"tfhd")
    if p is None or len(p) < 8:
        return None
    return struct.unpack(">I", p[4:8])[0]


def fragment_timestamp(data: bytes) -> int | None:
    p = find_nested(data, b"tfdt")
    if p is None or len(p) < 8:
        return None
    if p[0] == 1:
        if len(p) < 12:
            return None
        return struct.unpack(">Q", p[4:12])[0]
    return struct.unpack(">I", p[4:8])[0]


def classify_init_with_ffprobe(path: Path) -> list[dict]:
    """Use FFprobe on an actual init segment to determine real stream types.

    Safer than searching for 'avc1'/'mp4a' bytes anywhere in the file.
    """
    try:
        import json
        p = subprocess.run(
            [str(FFPROBE_EXE), "-v", "error", "-print_format", "json",
             "-show_streams", str(path)],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", creationflags=_NO_WINDOW,
        )
        if p.returncode != 0:
            return []
        data = json.loads(p.stdout or "{}")
        return [
            {
                "index": s.get("index"), "codec_type": s.get("codec_type"),
                "codec_name": s.get("codec_name"), "codec_tag": s.get("codec_tag_string"),
                "width": s.get("width"), "height": s.get("height"),
                "sample_rate": s.get("sample_rate"), "channels": s.get("channels"),
                "r_frame_rate": s.get("r_frame_rate"),
            }
            for s in data.get("streams", [])
            if s.get("codec_type") in ("video", "audio")
        ]
    except Exception:
        return []
