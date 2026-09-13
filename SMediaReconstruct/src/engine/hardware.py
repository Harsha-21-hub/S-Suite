"""Hardware-encoder capability detection.

The hardware option is only meaningful if the machine can actually encode on
the GPU. Listing an encoder in `ffmpeg -encoders` is not enough — the NVENC
runtime can still fail at launch (no driver, no NVENC session, laptop dGPU
disabled). So we do a real 0.2 s encode test and cache the result. If it
fails, the pipeline falls back to libx264 and says so, instead of silently
"doing nothing on the GPU".
"""
from __future__ import annotations

import subprocess

from .ffmpeg import FFMPEG_EXE, _NO_WINDOW, ffmpeg_available

_nvenc_cache: bool | None = None


def _run_quiet(args: list[str]) -> int:
    try:
        p = subprocess.run(
            [str(FFMPEG_EXE), "-hide_banner", *args],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", creationflags=_NO_WINDOW, timeout=30,
        )
        return p.returncode
    except Exception:
        return 1


def nvenc_available(force: bool = False) -> bool:
    """True only if a real NVENC encode succeeds on this machine."""
    global _nvenc_cache
    if _nvenc_cache is not None and not force:
        return _nvenc_cache
    if not ffmpeg_available():
        _nvenc_cache = False
        return False
    rc = _run_quiet([
        "-f", "lavfi", "-i", "color=c=black:s=256x256:r=5",
        "-t", "0.2", "-c:v", "h264_nvenc", "-f", "null", "-",
    ])
    _nvenc_cache = (rc == 0)
    return _nvenc_cache


def resolve_encoder(hardware: str) -> tuple[bool, str]:
    """Return (use_gpu, human_label) for the requested hardware option.

    Falls back to CPU when the GPU can't actually encode.
    """
    wants_gpu = hardware in ("gpu_max", "cpu_gpu_max")
    if wants_gpu and nvenc_available():
        return True, "NVENC (GPU)"
    if wants_gpu:
        return False, "libx264 (CPU · GPU encoder unavailable)"
    return False, "libx264 (CPU)"
