"""FFmpeg / FFprobe location, process runner, and probing.

The proven runner from the original build is preserved: it streams
`-progress pipe:1`, surfaces real speed, and terminates cleanly on cancel.
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
from pathlib import Path
from typing import Callable

# ---- Bundled binary discovery --------------------------------------------
# PyInstaller unpacks bundled data under sys._MEIPASS. During development the
# app also accepts src/ffmpeg/bin. build_windows.ps1 places ffmpeg there.
_BASE = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
_CANDIDATE_BINS = [
    _BASE / "ffmpeg" / "bin",
    Path(__file__).resolve().parents[1] / "ffmpeg" / "bin",
]

_EXE_SUFFIX = ".exe" if os.name == "nt" else ""


def _first_existing_bin() -> Path:
    for b in _CANDIDATE_BINS:
        if (b / f"ffmpeg{_EXE_SUFFIX}").exists():
            return b
    # Fall back to first candidate; ensure() will raise a clear error.
    return _CANDIDATE_BINS[0]


FFMPEG_BIN = _first_existing_bin()
FFMPEG_EXE = FFMPEG_BIN / f"ffmpeg{_EXE_SUFFIX}"
FFPROBE_EXE = FFMPEG_BIN / f"ffprobe{_EXE_SUFFIX}"
FFPLAY_EXE = FFMPEG_BIN / f"ffplay{_EXE_SUFFIX}"

# On Windows, suppress the console window that Popen would otherwise flash.
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def ffmpeg_available() -> bool:
    return FFMPEG_EXE.exists() and FFPROBE_EXE.exists()


class FF:
    """A single FFmpeg execution context bound to a cancellable job."""

    def __init__(
        self,
        log: Callable[[str], None],
        progress: Callable[[float, float | None], None] | None = None,
        cancel_event: threading.Event | None = None,
    ):
        self.log = log
        # progress(percent, speed_x)
        self.progress = progress or (lambda pct, spd: None)
        self.cancel_event = cancel_event or threading.Event()
        self.current_process: subprocess.Popen | None = None

    # -- guards ------------------------------------------------------------
    def ensure(self):
        if not ffmpeg_available():
            raise RuntimeError(
                "Bundled FFmpeg/FFprobe are missing from this build.\n"
                "Rebuild the EXE with build_windows.ps1, or place ffmpeg into "
                "src/ffmpeg/bin for a development run."
            )

    def cancel(self):
        self.cancel_event.set()
        p = self.current_process
        if p is not None and p.poll() is None:
            try:
                p.terminate()
            except Exception:
                pass

    # -- probing -----------------------------------------------------------
    def probe(self, path: Path) -> dict:
        self.ensure()
        p = subprocess.run(
            [
                str(FFPROBE_EXE), "-v", "error",
                "-print_format", "json",
                "-show_format", "-show_streams",
                str(path),
            ],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", creationflags=_NO_WINDOW,
        )
        if p.returncode != 0:
            raise RuntimeError(f"ffprobe failed on {path.name}: {p.stderr.strip()[:400]}")
        return json.loads(p.stdout or "{}")

    def duration(self, path: Path) -> float:
        try:
            info = self.probe(path)
            return float(info.get("format", {}).get("duration", 0) or 0)
        except Exception:
            return 0.0

    # -- execution ---------------------------------------------------------
    def run(self, args: list[str], duration: float | None = None, label: str = "") -> str:
        """Run ffmpeg with the given args (without the ffmpeg binary itself)."""
        self.ensure()
        if self.cancel_event.is_set():
            raise RuntimeError("Operation cancelled by user.")

        cmd = [str(FFMPEG_EXE), "-hide_banner", "-progress", "pipe:1", "-nostats", *map(str, args)]
        self.log("$ " + " ".join(f'"{x}"' if " " in str(x) else str(x) for x in cmd))

        p = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            creationflags=_NO_WINDOW,
        )
        self.current_process = p
        q: queue.Queue = queue.Queue()
        lines: list[str] = []
        speed = None

        def reader():
            try:
                for line in p.stdout:  # type: ignore[union-attr]
                    q.put(line.rstrip())
            finally:
                q.put(None)

        rt = threading.Thread(target=reader, daemon=True)
        rt.start()

        try:
            finished = False
            while not finished:
                if self.cancel_event.is_set() and p.poll() is None:
                    self.log("Cancellation requested. Stopping current FFmpeg operation…")
                    self._terminate(p)
                    raise RuntimeError("Operation cancelled by user.")
                try:
                    line = q.get(timeout=0.15)
                except queue.Empty:
                    if p.poll() is not None and not rt.is_alive():
                        finished = True
                    continue
                if line is None:
                    finished = True
                    continue
                if not line:
                    continue
                lines.append(line)

                if line.startswith("speed="):
                    try:
                        speed = float(line.split("=", 1)[1].replace("x", "").strip())
                    except Exception:
                        pass
                elif line.startswith("out_time_ms=") and duration:
                    try:
                        t = float(line.split("=", 1)[1]) / 1_000_000.0
                        self.progress(max(0.0, min(100.0, t / duration * 100.0)), speed)
                    except Exception:
                        pass
                elif line == "progress=end":
                    self.progress(100.0, speed)
                elif not line.startswith((
                    "frame=", "fps=", "bitrate=", "total_size=",
                    "out_time_", "dup_frames=", "drop_frames=",
                    "speed=", "progress=",
                )):
                    self.log(line)

            rc = p.wait()
            if self.cancel_event.is_set():
                raise RuntimeError("Operation cancelled by user.")
            if rc != 0:
                raise RuntimeError(f"{label or 'FFmpeg'} failed (exit {rc}).\n\n" + "\n".join(lines[-120:]))
            return "\n".join(lines)
        finally:
            self.current_process = None

    def _terminate(self, p: subprocess.Popen):
        try:
            p.terminate()
        except Exception:
            pass
        try:
            p.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            try:
                p.kill()
            except Exception:
                pass
            try:
                p.wait(timeout=2.0)
            except Exception:
                pass
