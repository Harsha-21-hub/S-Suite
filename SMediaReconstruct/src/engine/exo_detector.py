"""Content-based classification of ExoPlayer `.exo` cache objects.

`.exo` is only a cache-file extension — it is NOT a media/container format.
An ExoPlayer download cache can hold fragmented-MP4/DASH segments, MPEG-TS
(HLS) segments, HLS master/media playlists, WebVTT subtitles, or a DASH
manifest — all with the same `.exo` suffix. Every object is therefore
classified individually by inspecting its actual bytes, never by its filename.

The byte scan here is deliberately cheap (a small head per file). Per-stream
FFprobe typing of the real media objects happens later, in the normalisation
layer, where each usable media object becomes a `MediaSegment`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

# -- classes ---------------------------------------------------------------
CLASS_TS = "mpegts"          # MPEG-TS media segment (HLS)
CLASS_FMP4 = "fmp4"          # fragmented / plain MP4 media (DASH, fMP4)
CLASS_HLS_MASTER = "hls_master"
CLASS_HLS_MEDIA = "hls_media"
CLASS_WEBVTT = "webvtt"
CLASS_DASH_MPD = "dash_mpd"
CLASS_UNKNOWN = "unknown"

MEDIA_CLASSES = (CLASS_TS, CLASS_FMP4)
_HEAD = 8192                 # bytes read from the front of each object


def looks_like_mpegts(data: bytes) -> bool:
    """True when `data` begins a run of MPEG-TS packets.

    Handles the standard 188-byte packet layout and the 192-byte (M2TS,
    4-byte timecode prefix) layout. Requires at least three aligned sync
    bytes so a stray 0x47 in some other container is not misread as TS.
    """
    n = len(data)
    if n < 188:
        return False
    for base, stride in ((0, 188), (4, 192)):
        if base < n and data[base] == 0x47:
            hits = 1
            pos = base + stride
            while pos < n and pos < base + stride * 6:
                if data[pos] != 0x47:
                    break
                hits += 1
                pos += stride
            if hits >= 3:
                return True
    return False


def classify_exo_file(path: Path) -> str:
    """Return one of the CLASS_* constants for a single `.exo` object."""
    try:
        head = path.read_bytes()[:_HEAD]
    except Exception:
        return CLASS_UNKNOWN
    if not head:
        return CLASS_UNKNOWN

    stripped = head.lstrip(b"\xef\xbb\xbf \t\r\n")   # tolerate BOM / whitespace

    # 1) Text playlists / subtitles (checked first; their first bytes are ASCII).
    if stripped[:7] == b"#EXTM3U":
        if b"EXT-X-STREAM-INF" in head or b"EXT-X-MEDIA:" in head:
            return CLASS_HLS_MASTER
        if (b"EXTINF" in head or b"EXT-X-TARGETDURATION" in head
                or b"EXT-X-PLAYLIST-TYPE" in head or b".ts" in head):
            return CLASS_HLS_MEDIA
        return CLASS_HLS_MASTER
    if stripped[:6] == b"WEBVTT":
        return CLASS_WEBVTT
    if b"<MPD" in head:
        return CLASS_DASH_MPD

    # 2) Fragmented / plain MP4 (ISO-BMFF): ftyp/styp/moof/sidx boxes.
    if len(head) >= 8 and head[4:8] in (b"ftyp", b"styp"):
        return CLASS_FMP4
    if head[4:8] in (b"moof", b"sidx", b"free", b"skip", b"moov"):
        return CLASS_FMP4
    if b"moof" in head[:4096] or b"styp" in head[:4096]:
        return CLASS_FMP4

    # 3) MPEG-TS media.
    if looks_like_mpegts(head):
        return CLASS_TS

    return CLASS_UNKNOWN


def parse_hls_media_playlist(path: Path) -> dict | None:
    """Parse an HLS *media* playlist into ordered per-segment metadata.

    Returns {"segments":[{"index","uri","duration"}...], "segment_count",
    "durations", "total_duration", "target"} or None. This is real ordered
    metadata; whether it can be MAPPED onto the cached `.exo` objects is a
    separate question answered by `map_playlist_to_exo` (below), because
    ExoPlayer names cache files by content-id hash, not by these URIs.
    """
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return None
    if "#EXTM3U" not in text:
        return None
    segments: list[dict] = []
    pending: float | None = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF:"):
            body = line[len("#EXTINF:"):].split(",", 1)[0].strip()
            try:
                pending = float(body)
            except ValueError:
                pending = None
        elif line.startswith("#"):
            continue
        else:
            segments.append({"index": len(segments), "uri": line,
                             "duration": pending if pending is not None else 0.0})
            pending = None
    durs = [s["duration"] for s in segments]
    tgt = re.search(r"#EXT-X-TARGETDURATION:\s*([0-9]+)", text)
    return {
        "segments": segments,
        "segment_count": len(segments),
        "durations": durs,
        "total_duration": sum(durs),
        "target": int(tgt.group(1)) if tgt else None,
    }


def find_cache_index(root: Path) -> Path | None:
    """Locate an ExoPlayer CachedContentIndex file, if one exists near the
    cache. Only its presence is reported here; parsing is attempted elsewhere."""
    names = ("cached_content_index.exi", "cached_content_index.cci")
    for base in (root, *root.parents[:3]):
        for n in names:
            p = base / n
            if p.is_file():
                return p
    return None


def map_playlist_to_exo(playlist_info: dict | None, media_files: list,
                        cache_index: Path | None, log) -> list | None:
    """Attempt to order EXO objects by the playlist's segment sequence.

    A reliable mapping requires the CachedContentIndex (content-id → source
    URI). Without a parseable index we CANNOT correlate playlist URIs to the
    hash-named cache files, so this returns None and the caller falls back to
    the media timeline. It never invents a URI→EXO mapping.
    """
    if not playlist_info or not cache_index:
        return None
    # A verified binary CachedContentIndex parser is not available here, so we
    # do not guess a mapping. Be explicit rather than pretend.
    log(f"Found cache index {cache_index.name}, but a verified URI→content-id "
        "mapping is not available; not inventing one.")
    return None


@dataclass
class ExoInventory:
    root: Path
    total: int = 0
    ts_media: list[Path] = field(default_factory=list)
    fmp4_media: list[Path] = field(default_factory=list)
    hls_master: list[Path] = field(default_factory=list)
    hls_media: list[Path] = field(default_factory=list)
    webvtt: list[Path] = field(default_factory=list)
    dash_mpd: list[Path] = field(default_factory=list)
    unknown: list[Path] = field(default_factory=list)

    @property
    def media_count(self) -> int:
        return len(self.ts_media) + len(self.fmp4_media)

    def best_media_playlist(self) -> Path | None:
        """The HLS media playlist that describes the most segments, if any."""
        best, best_n = None, -1
        for p in self.hls_media:
            info = parse_hls_media_playlist(p)
            if info and info["segment_count"] > best_n:
                best, best_n = p, info["segment_count"]
        return best


def scan_exo_inventory(files: list[Path], log=lambda *_: None) -> ExoInventory:
    """Classify every `.exo` object by content and log a breakdown."""
    _BUCKET = {
        CLASS_TS: "ts_media", CLASS_FMP4: "fmp4_media",
        CLASS_HLS_MASTER: "hls_master", CLASS_HLS_MEDIA: "hls_media",
        CLASS_WEBVTT: "webvtt", CLASS_DASH_MPD: "dash_mpd",
        CLASS_UNKNOWN: "unknown",
    }
    root = files[0].parent if files else Path(".")
    inv = ExoInventory(root=root, total=len(files))
    total = len(files)
    log(f"Classifying {total} .exo objects by actual content (not filename)…")
    for i, p in enumerate(files, 1):
        getattr(inv, _BUCKET[classify_exo_file(p)]).append(p)
        if i % 500 == 0 or i == total:
            log(f"  classified {i}/{total} objects")
    log("EXO cache inventory:")
    log(f"  MPEG-TS media      : {len(inv.ts_media)}")
    log(f"  fMP4/MP4 media      : {len(inv.fmp4_media)}")
    log(f"  HLS master playlist : {len(inv.hls_master)}")
    log(f"  HLS media playlist  : {len(inv.hls_media)}")
    log(f"  WebVTT subtitle     : {len(inv.webvtt)}")
    log(f"  DASH manifest (MPD) : {len(inv.dash_mpd)}")
    log(f"  Unknown/unreadable  : {len(inv.unknown)}")
    for p in inv.unknown:
        log(f"    UNKNOWN object (skipped): {p}")
    return inv
