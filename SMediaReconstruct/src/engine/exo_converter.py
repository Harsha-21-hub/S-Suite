"""Format-agnostic EXO ingestion → common MediaSegment model → normalized asset.

Every usable media object is probed with FFprobe into a `MediaSegment` that
retains its real container, stream layout, codecs, and per-stream timing
(start_pts / start_time / time_base / duration / stream indices). Segments are
grouped by their *actual detected properties* into representations and ordered
by a *validated* media timeline. The selected representation is then assembled
into a normalized CONTINUOUS asset (for MPEG-TS, the ordered segment bytes are
concatenated into one continuous transport stream) which the shared
reconstruction/finalize stage remuxes once.

Why continuous and not per-segment MP4 clips: remuxing each ~4 s segment into
its own MP4 bakes AAC encoder priming into every clip, so re-concatenating
thousands of them re-introduces an audible click at every boundary. Assembling
one continuous stream *before* any decode is what keeps the audio gapless — the
same principle the proven fragmented-MP4 path uses.

Hard rules enforced here:

* A required media object that cannot be probed FAILS the job with its exact
  path — never silently skipped.
* Ordering is by a *validated* timeline (strictly increasing, no gaps/overlaps
  beyond tolerance, consistent stream identity, and — when an HLS media
  playlist is present — durations matching the playlist). Content-id order is
  a fallback that is itself validated. The code says which basis it used and
  never claims a stronger guarantee than it verified.
* Different renditions (different codec/resolution/fps) are detected as
  separate representations; the resolver selects one coherent rendition rather
  than mixing them.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .ffmpeg import FF
from .util import numeric_sort_key

GAP_TOL = 0.75          # seconds of slack allowed at a segment boundary
DUR_TOL = 0.60          # per-segment duration tolerance vs playlist


def _fps(r_frame_rate) -> float | None:
    try:
        num, den = str(r_frame_rate).split("/")
        den = float(den)
        return float(num) / den if den else None
    except Exception:
        try:
            return float(r_frame_rate)
        except Exception:
            return None


def _tb_seconds(pts, time_base) -> float | None:
    try:
        num, den = str(time_base).split("/")
        return int(pts) * (float(num) / float(den))
    except Exception:
        return None


@dataclass
class MediaSegment:
    source: Path
    container: str                    # "mpegts" | "fmp4" | "mp4"
    media_type: str                   # "combined_av" | "video_only" | "audio_only"
    sequence: int = -1
    start_time: float | None = None
    start_pts: int | None = None
    time_base: str | None = None
    duration: float = 0.0
    video_codec: str | None = None
    audio_codec: str | None = None
    width: int | None = None
    height: int | None = None
    fps: float | None = None
    video_stream_index: int | None = None
    audio_stream_index: int | None = None
    normalized_path: Path | None = None

    def timeline_pos(self) -> float | None:
        if self.start_time is not None:
            return self.start_time
        if self.start_pts is not None and self.time_base:
            return _tb_seconds(self.start_pts, self.time_base)
        return None

    def video_signature(self):
        """Full logical A/V rendition identity, or None for audio-only segments.

        Audio codec is part of the identity so otherwise-identical video
        renditions with different audio codecs are never merged.
        """
        if self.media_type == "audio_only":
            return None
        return (
            self.video_codec,
            self.width,
            self.height,
            round(self.fps or 0.0, 2),
            self.audio_codec,
        )

    def describe(self) -> str:
        if self.media_type == "audio_only":
            return f"audio-only {self.audio_codec}"
        return (f"{self.video_codec} {self.width}x{self.height} "
                f"@{round(self.fps or 0.0, 2)}fps"
                + (f" + {self.audio_codec}" if self.audio_codec else ""))


def probe_segment(ff: FF, path: Path, container: str) -> MediaSegment:
    """Probe one media object into a MediaSegment. Raises if unreadable or if
    it carries no audio/video (caller treats that as a hard failure)."""
    info = ff.probe(path)                         # raises RuntimeError on error
    streams = info.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if not v and not a:
        raise RuntimeError(f"no audio or video stream in {path}")
    fmt = info.get("format", {})
    prim = v or a
    start_time = None
    for src in (fmt.get("start_time"), (prim or {}).get("start_time")):
        try:
            if src not in (None, "N/A"):
                start_time = float(src)
                break
        except (TypeError, ValueError):
            pass
    start_pts = None
    try:
        sp = (prim or {}).get("start_pts")
        if sp not in (None, "N/A"):
            start_pts = int(sp)
    except (TypeError, ValueError):
        start_pts = None
    dur = float(fmt.get("duration", 0) or 0)
    media_type = ("combined_av" if v and a else "video_only" if v else "audio_only")
    return MediaSegment(
        source=path, container=container, media_type=media_type,
        start_time=start_time, start_pts=start_pts,
        time_base=(prim or {}).get("time_base"),
        duration=dur,
        video_codec=(v or {}).get("codec_name"),
        audio_codec=(a or {}).get("codec_name"),
        width=(v or {}).get("width"), height=(v or {}).get("height"),
        fps=_fps((v or {}).get("r_frame_rate")) if v else None,
        video_stream_index=(v or {}).get("index"),
        audio_stream_index=(a or {}).get("index"),
    )


def build_ts_segments(ff: FF, media: list[Path], log) -> list[MediaSegment]:
    """Probe every MPEG-TS media object into a MediaSegment list (unordered).
    An unreadable required object fails the job (never skipped)."""
    segs: list[MediaSegment] = []
    total = len(media)
    log(f"Probing {total} MPEG-TS media object(s) into the segment model…")
    for i, p in enumerate(media, 1):
        try:
            segs.append(probe_segment(ff, p, "mpegts"))
        except Exception as e:
            raise RuntimeError(
                f"Required MPEG-TS media object is unreadable and will NOT be "
                f"skipped:\n  {p}\n  reason: {e}")
        if i % 500 == 0 or i == total:
            log(f"  probed {i}/{total} segments")
    return segs


# -- representation grouping ----------------------------------------------
def video_representations(segments: list[MediaSegment]) -> dict:
    """Group video-bearing segments by the full logical A/V signature.

    The signature is `(video_codec, width, height, fps, audio_codec)`, so
    different audio codecs remain separate renditions even when the video
    properties are identical.
    """
    groups: dict = {}
    for s in segments:
        sig = s.video_signature()
        if sig is None:
            continue
        groups.setdefault(sig, []).append(s)
    return groups


def describe_signature(sig, source: str, count: int) -> str:
    """Human description of a rendition signature. Accepts the 4-tuple video
    signature (codec, w, h, fps) or the 5-tuple rendition signature that also
    carries the audio codec (codec, w, h, fps, acodec)."""
    codec, w, h, fps = sig[0], sig[1], sig[2], sig[3]
    acodec = sig[4] if len(sig) > 4 else None
    audio = f" + {acodec}" if acodec else ""
    return f"{source}: {codec} {w}x{h} @{fps}fps{audio}  ×{count} segment(s)"


def video_sig_from_stream(v: dict, audio_codec: str | None = None):
    """Full A/V rendition signature from a probed ffprobe video stream.

    `audio_codec` is supplied when the audio stream is available from the same
    init or from a separate audio initialization object.
    """
    return (
        v.get("codec_name"),
        v.get("width"),
        v.get("height"),
        round(_fps(v.get("r_frame_rate")) or 0.0, 2),
        audio_codec,
    )


@dataclass
class ReconResult:
    """The SINGLE contract every format adapter returns. Both the MPEG-TS and
    the fragmented-MP4 adapters produce this, so the common finalize stage is
    truly format-agnostic. `video`/`audio` are continuous normalized assets
    (they may be the same file for combined A/V); the adapter may use whatever
    lossless internal assembly is correct for its container — a normalized
    media abstraction does NOT mandate physical per-segment MP4 files."""
    video: Path
    audio: Path
    duration: float
    boundaries: list
    metadata: dict


# -- ordering + timeline validation ---------------------------------------
def _order_by_timeline(segments):
    if any(s.timeline_pos() is None for s in segments):
        return None
    return sorted(segments, key=lambda s: (s.timeline_pos(), numeric_sort_key(s.source)))


def _order_by_id(segments):
    return sorted(segments, key=lambda s: numeric_sort_key(s.source))


def validate_timeline(ordered: list[MediaSegment], playlist_info: dict | None) -> list[str]:
    """Return a list of concrete problems with this ordering (empty = valid)."""
    issues: list[str] = []
    if not ordered:
        return ["no media segments"]

    # Consistent stream identity across the representation.
    sigs = {s.video_signature() for s in ordered if s.video_signature() is not None}
    if len(sigs) > 1:
        issues.append(f"inconsistent video identity across segments ({len(sigs)} distinct)")

    # Timeline geometry, only when every segment has a position.
    pos = [s.timeline_pos() for s in ordered]
    if all(p is not None for p in pos) and len(ordered) > 1:
        for i in range(len(ordered) - 1):
            if pos[i + 1] <= pos[i]:
                issues.append(f"non-increasing timeline at #{i} ({pos[i]:.3f}→{pos[i+1]:.3f})")
                break
        for i in range(len(ordered) - 1):
            boundary = pos[i] + (ordered[i].duration or 0.0)
            delta = pos[i + 1] - boundary
            if delta > GAP_TOL:
                issues.append(f"gap ~{delta:.1f}s after {ordered[i].source.name}")
                break
            if delta < -GAP_TOL:
                issues.append(f"overlap ~{-delta:.1f}s after {ordered[i].source.name}")
                break

    # Playlist corroboration (count + per-segment durations, in order).
    if playlist_info and playlist_info.get("segment_count"):
        exp = playlist_info["segment_count"]
        if len(ordered) != exp:
            issues.append(f"segment count {len(ordered)} != HLS playlist {exp}")
        pdur = playlist_info.get("durations") or []
        if len(pdur) == len(ordered):
            for i, (seg, pd) in enumerate(zip(ordered, pdur)):
                if seg.duration and abs(seg.duration - pd) > DUR_TOL:
                    issues.append(
                        f"duration mismatch vs playlist at #{i} "
                        f"({seg.duration:.2f}s vs {pd:.2f}s)")
                    break
    return issues


def resolve_order(segments: list[MediaSegment], playlist_info: dict | None,
                  playlist_mapping, log) -> list[MediaSegment]:
    """Order segments with a clear, validated precedence:

      1. explicit HLS playlist→EXO mapping, when one is actually available;
      2. otherwise a media-timeline order that passes validation;
      3. otherwise a content-id order that passes validation;
      4. otherwise, if there is positive evidence of a broken timeline
         (gap / overlap / count- or duration-mismatch), FAIL;
      5. otherwise (no signal to validate) fall back to content-id and say so.
    """
    if playlist_mapping:
        log("Segment order: HLS playlist→EXO mapping.")
        ordered = playlist_mapping
    else:
        log("HLS playlist order unavailable for direct EXO mapping; "
            "using media timeline ordering.")
        by_tl = _order_by_timeline(segments)
        by_id = _order_by_id(segments)
        last_issues: list[str] = []
        chosen = None
        for name, cand in (("media timeline", by_tl), ("content-id", by_id)):
            if cand is None:
                continue
            issues = validate_timeline(cand, playlist_info)
            if not issues:
                log(f"Segment order: {name} (validated).")
                chosen = cand
                break
            last_issues = issues
        if chosen is None:
            positional = by_tl is not None
            has_playlist = bool(playlist_info and playlist_info.get("segment_count"))
            if positional or has_playlist:
                raise RuntimeError(
                    "Timeline validation failed — the cache is inconsistent or "
                    "incomplete:\n  - " + "\n  - ".join(last_issues) +
                    "\nReconstruction stopped rather than produce a broken movie.")
            log("Segment order: content-id fallback (no timeline/playlist "
                "signal available to validate).")
            chosen = by_id
        ordered = chosen

    for i, s in enumerate(ordered):
        s.sequence = i
    return ordered


# -- continuous reconstruction (gapless) ----------------------------------
def concat_segments(segments: list[MediaSegment], dst: Path, log,
                    on_progress=None) -> Path:
    """Concatenate the ordered MPEG-TS segment BYTES into one continuous stream.

    MPEG-TS is a byte-concatenable transport format, and the cached `.exo`
    objects are the raw segment bytes, so this reproduces the original
    continuous download exactly. Doing this BEFORE any decode is what keeps the
    audio gapless: there are no per-segment MP4/AAC decoder restarts (the very
    thing that caused a click at every ~4 s boundary when segments were remuxed
    individually and re-concatenated). One remux of this file then yields the
    whole movie. This mirrors the proven fragmented-MP4 continuous path.
    """
    import shutil
    dst.parent.mkdir(parents=True, exist_ok=True)
    total = len(segments)
    with dst.open("wb") as w:
        for i, seg in enumerate(segments, 1):
            with seg.source.open("rb") as r:
                shutil.copyfileobj(r, w, 4 * 1024 * 1024)
            if on_progress and (i % 20 == 0 or i == total):
                on_progress(i, total)
            if i % 500 == 0 or i == total:
                log(f"  assembled {i}/{total} segments")
    return dst
