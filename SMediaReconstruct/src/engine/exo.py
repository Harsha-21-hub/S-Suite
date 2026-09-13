"""EXO / fragmented-MP4 / DASH scanning and continuous reconstruction.

The continuous-stream reconstruction is the proven beep-free path: the audio
init segment plus its ordered fragments are concatenated into one continuous
compressed stream *before* any decode, which avoids the per-fragment AAC
decoder restarts that produced audible clicks at every segment boundary.
"""
from __future__ import annotations

import shutil
from dataclasses import dataclass, field
from pathlib import Path

from .ffmpeg import FF, FFMPEG_EXE, FFPROBE_EXE, ffmpeg_available
from .mp4 import (
    classify_init_with_ffprobe, fragment_timestamp, fragment_track_id,
    init_track_infos, safe_init_track_id,
)

PROTECTION_MARKERS = (b"pssh", b"tenc", b"sinf", b"schm", b"encv", b"enca",
                      b"senc", b"saiz", b"saio")


@dataclass
class ExoScan:
    root: Path
    files: list[Path]
    manifest: Path | None
    video_init: Path | None
    audio_init: Path | None
    video_track_id: int | None
    audio_track_id: int | None
    video_fragments: list[tuple[int, Path]] = field(default_factory=list)
    audio_fragments: list[tuple[int, Path]] = field(default_factory=list)
    mixed: bool = False


def scan_exo(root: Path, log) -> ExoScan:
    files = sorted(root.rglob("*.exo"))
    if not files:
        raise RuntimeError("No .exo files were found.")
    if not ffmpeg_available():
        raise RuntimeError("Bundled FFmpeg/FFprobe are missing from the application build.")

    manifest = None
    init_candidates: list[tuple[Path, list[dict], int | None]] = []

    total_files = len(files)
    log(f"Scanning {total_files} .exo files for initialization segments…")
    for i, p in enumerate(files, 1):
        try:
            head = p.read_bytes()[:8192]
        except Exception:
            continue
        if b"<MPD" in head:
            manifest = p
            continue
        if i % 500 == 0 or i == total_files:
            log(f"  scanned {i}/{total_files} files for init segments")
        if len(head) < 8 or head[4:8] != b"ftyp":
            continue
        streams = classify_init_with_ffprobe(p)
        tid = safe_init_track_id(p)
        if streams:
            init_candidates.append((p, streams, tid))

    if not init_candidates:
        raise RuntimeError(
            "EXO scan found no readable MP4 initialization segments. "
            "The .exo cache may be incomplete or protected."
        )

    # Determine which init defines the video track and which the audio track.
    # Primary source: the media handler ('vide'/'soun') read straight from each
    # init's trak boxes — deterministic, and it also handles a single MIXED init
    # that carries BOTH tracks (video on one track id, audio on another). FFprobe
    # stream types and raw codec byte-signatures are fallbacks when an init is
    # unusual or FFprobe can't open it.
    VIDEO_SIGS = (b"avc1", b"avc3", b"hev1", b"hvc1", b"dvav", b"dvhe", b"vp09", b"av01")
    AUDIO_SIGS = (b"mp4a", b"ac-3", b"ec-3", b"Opus", b"fLaC", b"dtsc", b"dec3")

    video_inits, audio_inits = [], []
    video_track_ids, audio_track_ids = set(), set()
    video_init = audio_init = None

    for p, streams, tid in init_candidates:
        infos = init_track_infos(p)
        try:
            raw = p.read_bytes()
        except Exception:
            raw = b""
        types = {x.get("codec_type") for x in streams}
        matched = False
        # 1) per-track handler ids (separate OR mixed init)
        for info in infos:
            h = info.get("handler")
            if h == "vide":
                video_track_ids.add(info["track_id"]); video_init = video_init or p; matched = True
            elif h == "soun":
                audio_track_ids.add(info["track_id"]); audio_init = audio_init or p; matched = True
        if matched:
            if any(i.get("handler") == "vide" for i in infos):
                video_inits.append((p, streams, tid))
            if any(i.get("handler") == "soun" for i in infos):
                audio_inits.append((p, streams, tid))
            continue
        # 2) fallback: ffprobe stream types / codec byte-signatures + first track id
        is_v = ("video" in types) or any(s in raw for s in VIDEO_SIGS)
        is_a = ("audio" in types) or any(s in raw for s in AUDIO_SIGS)
        if is_v:
            video_inits.append((p, streams, tid)); video_init = video_init or p
            if tid is not None:
                video_track_ids.add(tid)
        if is_a:
            audio_inits.append((p, streams, tid)); audio_init = audio_init or p
            if tid is not None:
                audio_track_ids.add(tid)

    if video_init and not video_track_ids:
        video_track_ids.add(1)
    if audio_init and not audio_track_ids:
        audio_track_ids.add(2)
    mixed_init = (video_init is not None and video_init == audio_init)

    vf: list[tuple[int, Path]] = []
    af: list[tuple[int, Path]] = []
    log("Classifying fragments by track id (tfhd) and timeline (tfdt)…")
    for i, p in enumerate(files, 1):
        if i % 500 == 0 or i == total_files:
            log(f"  classified {i}/{total_files} fragments  ·  video={len(vf)} audio={len(af)}")
        try:
            head = p.read_bytes()[:4096]
        except Exception:
            continue
        if b"moof" not in head:
            continue
        tid = fragment_track_id(head)
        ts = fragment_timestamp(head)
        if tid is None or ts is None:
            continue
        if tid in video_track_ids and tid not in audio_track_ids:
            vf.append((ts, p))
        elif tid in audio_track_ids and tid not in video_track_ids:
            af.append((ts, p))
        elif tid in video_track_ids and tid in audio_track_ids:
            kinds = {x.get("codec_type") for x in classify_init_with_ffprobe(p)}
            if "video" in kinds and "audio" not in kinds:
                vf.append((ts, p))
            elif "audio" in kinds and "video" not in kinds:
                af.append((ts, p))

    vf.sort(key=lambda x: (x[0], x[1].name))
    af.sort(key=lambda x: (x[0], x[1].name))

    log(f"EXO files: {len(files)}")
    log(f"Manifest: {manifest or 'not found'}")
    log(f"Readable init candidates: {len(init_candidates)}")
    for p, streams, tid in init_candidates:
        desc = ", ".join(f"{s.get('codec_type')}:{s.get('codec_name') or s.get('codec_tag') or '?'}" for s in streams)
        log(f"  INIT {p.name} | track={tid} | {desc}")
    log(f"Video track IDs: {sorted(video_track_ids) if video_track_ids else 'none'}")
    log(f"Audio track IDs: {sorted(audio_track_ids) if audio_track_ids else 'none'}")
    log(f"Video init: {video_init or 'not found'}")
    log(f"Audio init: {audio_init or 'not found'}")
    log(f"A/V layout: {'MIXED single init (both tracks)' if mixed_init else 'SEPARATE video/audio inits'}")
    log(f"Video fragments: {len(vf)}")
    log(f"Audio fragments: {len(af)}")

    if not video_init:
        raise RuntimeError("EXO scan could not find a video initialization segment.")
    if not audio_init:
        raise RuntimeError("EXO scan could not find an audio initialization segment.")
    if not vf:
        raise RuntimeError("Video init found, but no matching video fragments were located.")
    if not af:
        raise RuntimeError("Audio init found, but no matching audio fragments were located.")

    for p, label in ((video_init, "video"), (audio_init, "audio")):
        data = p.read_bytes()
        if any(m in data for m in PROTECTION_MARKERS):
            raise RuntimeError(f"Protected/encrypted {label} media detected. DRM bypass is not supported.")

    return ExoScan(
        root=root, files=files, manifest=manifest,
        video_init=video_init, audio_init=audio_init,
        video_track_id=min(video_track_ids) if video_track_ids else None,
        audio_track_id=min(audio_track_ids) if audio_track_ids else None,
        video_fragments=vf, audio_fragments=af, mixed=mixed_init,
    )


def binary_reconstruct(init: Path, frags: list[tuple[int, Path]], out: Path, log,
                       on_progress=None, base: int = 0, grand_total: int = 0) -> Path:
    out.parent.mkdir(parents=True, exist_ok=True)
    total = len(frags)
    gt = grand_total or total
    with out.open("wb") as w:
        with init.open("rb") as r:
            shutil.copyfileobj(r, w, 4 * 1024 * 1024)
        for i, (_, p) in enumerate(frags, 1):
            with p.open("rb") as r:
                shutil.copyfileobj(r, w, 4 * 1024 * 1024)
            if on_progress and (i % 50 == 0 or i == total):
                on_progress(base + i, gt)
            if i % 250 == 0 or i == total:
                log(f"  assembled {i}/{total} fragments")
    return out


class ExoEngine:
    def __init__(self, ff: FF, log):
        self.ff, self.log = ff, log

    def reconstruct(self, scan: ExoScan, work: Path, on_progress=None) -> tuple[Path, Path]:
        # MIXED: a single init carries both tracks. Rebuild ONE continuous file
        # from the init plus ALL fragments in timeline order — this is the whole
        # movie (video + audio) in one stream. Both returned paths point to it.
        if scan.mixed:
            allf = sorted(scan.video_fragments + scan.audio_fragments,
                          key=lambda x: (x[0], x[1].name))
            av = binary_reconstruct(scan.video_init, allf, work / "continuous_av.mp4",
                                    self.log, on_progress=on_progress, base=0,
                                    grand_total=len(allf))
            info = self.ff.probe(av)
            types = {s.get("codec_type") for s in info.get("streams", [])}
            if "video" not in types:
                raise RuntimeError("Mixed reconstruction produced no video stream.")
            if "audio" not in types:
                raise RuntimeError("Mixed reconstruction produced no audio stream.")
            return av, av
        nv, na = len(scan.video_fragments), len(scan.audio_fragments)
        grand = nv + na
        v = binary_reconstruct(scan.video_init, scan.video_fragments,
                               work / "continuous_video.mp4", self.log,
                               on_progress=on_progress, base=0, grand_total=grand)
        a = binary_reconstruct(scan.audio_init, scan.audio_fragments,
                               work / "continuous_audio.mp4", self.log,
                               on_progress=on_progress, base=nv, grand_total=grand)
        vi, ai = self.ff.probe(v), self.ff.probe(a)
        if not any(s.get("codec_type") == "video" for s in vi.get("streams", [])):
            raise RuntimeError("Reconstructed video could not be opened by FFprobe.")
        if not any(s.get("codec_type") == "audio" for s in ai.get("streams", [])):
            raise RuntimeError("Reconstructed audio could not be opened by FFprobe.")
        return v, a

    def mux(self, video: Path, audio: Path, out: Path, cfg, duration: float | None = None):
        """Write the final movie.

        COPY FIRST   → stream-copy the continuous H.264 + original AAC (lossless,
                       ~source size). No encoding, so hardware is not used here.
        NORMALIZE ALL→ re-encode the continuous video with the selected encoder
                       (NVENC on GPU when available, else libx264) and keep the
                       original continuous AAC. This is the path that uses the GPU.
        """
        from .batching import decode_args, video_encode_args

        fmt = cfg.output_format
        same = (video == audio)   # MIXED: both maps come from one combined file
        if cfg.quality == "normalize_all":
            if same:
                args = ["-y", *decode_args(cfg), "-i", str(video),
                        "-map", "0:v:0", "-map", "0:a:0",
                        *video_encode_args(cfg), "-c:a", "copy", "-shortest"]
            else:
                args = ["-y", *decode_args(cfg), "-i", str(video), "-i", str(audio),
                        "-map", "0:v:0", "-map", "1:a:0",
                        *video_encode_args(cfg), "-c:a", "copy", "-shortest"]
            if fmt == "mp4":
                args += ["-movflags", "+faststart"]
            self.ff.run(args + [str(out)], duration=duration, label="Normalize (re-encode video)")
            return
        # COPY FIRST (default): lossless remux of the continuous streams.
        if same:
            args = ["-y", "-i", str(video), "-map", "0:v:0", "-map", "0:a:0",
                    "-c:v", "copy", "-c:a", "copy", "-shortest"]
        else:
            args = ["-y", "-i", str(video), "-i", str(audio), "-map", "0:v:0",
                    "-map", "1:a:0", "-c:v", "copy", "-c:a", "copy", "-shortest"]
        if fmt == "mp4":
            args += ["-movflags", "+faststart"]
        self.ff.run(args + [str(out)], duration=duration, label="Final mux (copy-first)")
