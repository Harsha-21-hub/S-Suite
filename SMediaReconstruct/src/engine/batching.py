"""Clip-based pipeline: dynamic normalization, batches of 50, master concat.

Durations are always measured per clip (never assumed to be 4 s). The batch
count is derived from the real file count. Copy-first uses stream-copy concat
so nothing is re-encoded when the clips are already compatible.
"""
from __future__ import annotations

import os
from pathlib import Path

from .audio import endpoint_protect_filter
from .detection import MediaFile
from .ffmpeg import FF
from .util import inv_float, numeric_sort_key

BATCH_SIZE = 50


def batch_count(n: int, size: int = BATCH_SIZE) -> int:
    return (n + size - 1) // size


def use_gpu(cfg) -> bool:
    """GPU encode is used only when requested AND actually available.

    cfg.gpu_ok is set by the pipeline after a real NVENC capability probe.
    """
    return bool(getattr(cfg, "gpu_ok", False)) and cfg.hardware in ("gpu_max", "cpu_gpu_max")


def decode_args(cfg) -> list[str]:
    """Optional decode acceleration, placed before an input.

    - gpu_max      : decode on the GPU (`-hwaccel cuda`) → minimal CPU load.
    - cpu_gpu_max  : decode + filter on the CPU while NVENC encodes on the GPU,
                     so both processors are worked (this is the "CPU + GPU" mode).
    - cpu_only     : no acceleration.
    """
    if getattr(cfg, "gpu_ok", False) and cfg.hardware == "gpu_max":
        return ["-hwaccel", "cuda"]
    return []


def video_encode_args(cfg) -> list[str]:
    """Choose encoder from hardware + size options."""
    large = cfg.size == "large"
    if use_gpu(cfg):
        return ["-c:v", "h264_nvenc", "-preset", "p7", "-tune", "hq",
                "-cq:v", "15" if large else "21", "-b:v", "0",
                "-temporal-aq", "1", "-aq-strength", "15",
                "-rc-lookahead", "32", "-bf", "4"]
    # CPU (either chosen, or GPU requested but unavailable → safe fallback)
    return ["-c:v", "libx264", "-preset", "slow" if large else "medium",
            "-crf", "15" if large else "21", "-threads", "0"]


def audio_encode_args(cfg) -> list[str]:
    if cfg.size == "large" and cfg.output_format == "mkv":
        return ["-c:a", "flac"]          # lossless in MKV
    return ["-c:a", "aac", "-b:a", "512k" if cfg.size == "large" else "320k"]


class ClipEngine:
    def __init__(self, ff: FF, log):
        self.ff, self.log = ff, log

    # -- lossless copy path ------------------------------------------------
    def copy_concat(self, items: list[MediaFile], out: Path, label="Lossless copy concat"):
        items = sorted(items, key=lambda x: numeric_sort_key(x.path))
        tmp = out.parent / f".concat_{os.getpid()}_{out.stem}.txt"
        lines = [f"file '{str(m.path.resolve()).replace(chr(92), '/').replace(chr(39), chr(39)+chr(92)+chr(39)+chr(39))}'"
                 for m in items]
        tmp.write_text("\n".join(lines) + "\n", encoding="ascii")
        dur = sum(m.duration for m in items) or None
        try:
            self.ff.run(["-y", "-f", "concat", "-safe", "0", "-i", str(tmp),
                         "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", str(out)],
                        duration=dur, label=label)
        finally:
            tmp.unlink(missing_ok=True)

    # -- normalized filter concat (dynamic per-clip durations) ------------
    def filter_concat(self, items: list[MediaFile], out: Path, cfg,
                      protect_indices: set[int] | None = None,
                      target_fps: str | None = None, target_sr: int = 48000):
        items = sorted(items, key=lambda x: numeric_sort_key(x.path))
        n = len(items)
        protect = protect_indices or set()
        inputs: list[str] = []
        for m in items:
            inputs += ["-i", str(m.path)]

        vparts, aparts = [], []
        for i in range(n):
            vf = f"[{i}:v]setpts=N/(FRAME_RATE*TB)"
            if target_fps:
                vf = f"[{i}:v]fps={target_fps},setpts=N/(FRAME_RATE*TB)"
            vparts.append(vf + f"[v{i}]")
            af = f"[{i}:a]aresample={target_sr}:resampler=soxr"
            if i in protect:
                af += "," + endpoint_protect_filter(3.0)
            af += ",asetpts=N/SR/TB" + f"[a{i}]"
            aparts.append(af)

        vlabels = "".join(f"[v{i}]" for i in range(n))
        alabels = "".join(f"[a{i}]" for i in range(n))
        filt = (";".join(vparts) + ";" + vlabels + f"concat=n={n}:v=1:a=0[v];"
                + ";".join(aparts) + ";" + alabels + f"concat=n={n}:v=0:a=1[a]")

        args = ["-y", *inputs, "-filter_complex", filt, "-map", "[v]", "-map", "[a]",
                *video_encode_args(cfg), *audio_encode_args(cfg)]
        if cfg.output_format == "mp4":
            args += ["-movflags", "+faststart"]
        dur = sum(m.duration for m in items) or None
        self.ff.run(args + [str(out)], duration=dur, label="Normalized concat")

    # -- batch build -------------------------------------------------------
    def build_batches(self, items: list[MediaFile], work: Path, cfg, normalize: bool,
                      protect_indices: set[int] | None, emit_batch=None, preview=None) -> list[Path]:
        items = sorted(items, key=lambda x: numeric_sort_key(x.path))
        total = len(items)
        count = batch_count(total)
        bdir = work / "batches"
        bdir.mkdir(parents=True, exist_ok=True)
        protect = protect_indices or set()
        outs: list[Path] = []
        for b in range(count):
            s = b * BATCH_SIZE
            e = min(s + BATCH_SIZE, total)
            chunk = items[s:e]
            bout = bdir / f"batch_{b + 1:03d}.mkv"
            local_protect = {i - s for i in protect if s <= i < e}
            if normalize or local_protect:
                self.filter_concat(chunk, bout, cfg, protect_indices=local_protect)
            else:
                self.copy_concat(chunk, bout, label=f"Batch {b + 1}/{count} copy")
            outs.append(bout)
            if emit_batch:
                emit_batch(b + 1, count)
            if preview:
                preview(bout)
        return outs

    def concat_masters(self, batches: list[Path], out: Path, label="Master concat"):
        tmp = out.parent / f".master_{os.getpid()}.txt"
        lines = [f"file '{str(p.resolve()).replace(chr(92), '/').replace(chr(39), chr(39)+chr(92)+chr(39)+chr(39))}'"
                 for p in batches]
        tmp.write_text("\n".join(lines) + "\n", encoding="ascii")
        try:
            self.ff.run(["-y", "-f", "concat", "-safe", "0", "-i", str(tmp),
                         "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", str(out)], label=label)
        finally:
            tmp.unlink(missing_ok=True)

    def last_frame_jpeg(self, media: Path, out: Path) -> Path | None:
        """Grab the final visible frame of a segment for the live preview cell."""
        try:
            self.ff.run(["-y", "-sseof", "-0.4", "-i", str(media), "-frames:v", "1",
                         "-vf", "scale=480:-2", "-q:v", "4", str(out)], label="Preview frame")
            return out if out.exists() else None
        except Exception:
            return None
