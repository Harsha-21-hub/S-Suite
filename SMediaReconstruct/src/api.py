"""JS-facing bridge for the pywebview window.

The frontend calls `window.pywebview.api.<method>()`; the backend pushes
events to the frontend with `window.evaluate_js("window.__smr.onEvent(...)")`.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from pathlib import Path

from engine.ffmpeg import FFPLAY_EXE
from engine.pipeline import Config, Pipeline
from engine.util import safe_name


RECENTS_MAX = 6

# Startup engine protection.
# The WebView calls get_defaults() during startup. That call must never wait
# indefinitely for FFmpeg/FFprobe, otherwise WebView2 can show "Not Responding".
STARTUP_ENGINE_TIMEOUT = 5.0
STARTUP_RELAUNCH_ENV = "SMR_STARTUP_ENGINE_RELAUNCHED"


def _bundled_engine_paths() -> tuple[Path, Path]:
    """Resolve the FFmpeg/FFprobe paths from the same bundled bin directory."""
    bin_dir = FFPLAY_EXE.parent
    return bin_dir / "ffmpeg.exe", bin_dir / "ffprobe.exe"


def _startup_engine_check() -> tuple[bool, str]:
    """
    Run a bounded FFmpeg/FFprobe health check.

    The check is deliberately performed here with subprocess timeouts rather
    than calling an unbounded helper. This prevents the pywebview API call
    itself from hanging forever during application startup.
    """
    ffmpeg_exe, ffprobe_exe = _bundled_engine_paths()
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    for label, exe in (("FFmpeg", ffmpeg_exe), ("FFprobe", ffprobe_exe)):
        if not exe.is_file():
            return False, f"{label} was not found: {exe}"

        try:
            proc = subprocess.run(
                [str(exe), "-version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=STARTUP_ENGINE_TIMEOUT,
                creationflags=creationflags,
            )
        except subprocess.TimeoutExpired:
            return False, (
                f"{label} startup check timed out after "
                f"{STARTUP_ENGINE_TIMEOUT:.0f} seconds."
            )
        except Exception as exc:
            return False, f"{label} startup check could not be started: {exc}"

        if proc.returncode != 0:
            output = (proc.stdout or "").strip()
            detail = output[-1200:] if output else "No diagnostic output."
            return False, (
                f"{label} startup check failed (exit {proc.returncode}).\n"
                f"{detail}"
            )

    return True, "FFmpeg and FFprobe are ready."


def _relaunch_once_after_engine_failure(error: str) -> bool:
    """
    Relaunch the packaged/source application once after a startup engine
    failure. Returns True when a relaunch was started.

    The environment marker prevents an infinite restart loop.
    """
    if os.environ.get(STARTUP_RELAUNCH_ENV) == "1":
        return False

    try:
        env = os.environ.copy()
        env[STARTUP_RELAUNCH_ENV] = "1"

        if getattr(sys, "frozen", False):
            command = [sys.executable, *sys.argv[1:]]
            cwd = str(Path(sys.executable).resolve().parent)
        else:
            script = Path(sys.argv[0]).resolve()
            command = [sys.executable, str(script), *sys.argv[1:]]
            cwd = str(script.parent)

        subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            close_fds=True,
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )
        return True
    except Exception:
        return False




def _config_dir() -> Path:
    base = os.environ.get("APPDATA") or os.environ.get("XDG_CONFIG_HOME") or str(Path.home())
    d = Path(base) / "S-MediaReconstruct"
    try:
        d.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass
    return d


def _recents_path() -> Path:
    return _config_dir() / "recents.json"


def _load_recents() -> dict:
    try:
        data = json.loads(_recents_path().read_text(encoding="utf-8"))
        return {"inputs": list(data.get("inputs", []))[:RECENTS_MAX],
                "outputs": list(data.get("outputs", []))[:RECENTS_MAX]}
    except Exception:
        return {"inputs": [], "outputs": []}


def _save_recents(r: dict):
    try:
        _recents_path().write_text(json.dumps(r), encoding="utf-8")
    except Exception:
        pass


def _push_recent(kind: str, value: str):
    if not value:
        return
    r = _load_recents()
    lst = [x for x in r.get(kind, []) if x != value]
    lst.insert(0, value)
    r[kind] = lst[:RECENTS_MAX]
    _save_recents(r)


class Api:
    def __init__(self):
        self.window = None
        self.cancel_event = threading.Event()
        self.pipeline: Pipeline | None = None
        self.worker: threading.Thread | None = None
        self.inputs: list[Path] = []
        self.input_mode = "folder"
        self.last_output: Path | None = None

    # -- wiring ------------------------------------------------------------
    def set_window(self, window):
        self.window = window

    def _emit(self, evt: dict):
        if not self.window:
            return
        try:
            self.window.evaluate_js(f"window.__smr && window.__smr.onEvent({json.dumps(evt)})")
        except Exception:
            pass

    def _busy(self) -> bool:
        return bool(self.worker and self.worker.is_alive())

    # -- environment -------------------------------------------------------
    def get_defaults(self) -> dict:
        home = Path.home()
        out = home / "Videos"

        # This method is called by the UI while it is showing ENGINE CHECKING.
        # Never call an unbounded FFmpeg helper here: a hung process would keep
        # the pywebview bridge request pending and make the whole window appear
        # "Not Responding".
        ok, engine_error = _startup_engine_check()

        if not ok:
            relaunched = _relaunch_once_after_engine_failure(engine_error)

            # When the first check fails, start exactly one clean replacement
            # process and terminate this broken instance. The replacement gets
            # the marker above, so it cannot create a restart loop.
            if relaunched:
                # Give the new process a moment to initialize before exiting.
                threading.Timer(0.20, lambda: os._exit(0)).start()

                return {
                    "outFolder": str(out if out.exists() else home),
                    "outName": "S-Media-Reconstructed",
                    "ffmpeg": False,
                    "platform": os.name,
                    "engineError": (
                        "Engine startup check failed. "
                        "S-MediaReconstruct is relaunching once…"
                    ),
                    "engineRelaunching": True,
                }

            # This is the second failure. Do NOT relaunch again. Return the
            # actual diagnostic so the UI can surface the real problem.
            return {
                "outFolder": str(out if out.exists() else home),
                "outName": "S-Media-Reconstructed",
                "ffmpeg": False,
                "platform": os.name,
                "engineError": (
                    "ENGINE CHECK FAILED AFTER AUTOMATIC RELAUNCH\n\n"
                    + engine_error
                    + "\n\nNo further automatic relaunch will be attempted."
                ),
                "engineRelaunching": False,
            }

        return {
            "outFolder": str(out if out.exists() else home),
            "outName": "S-Media-Reconstructed",
            "ffmpeg": True,
            "platform": os.name,
            "engineError": "",
            "engineRelaunching": False,
        }

    # -- input selection ---------------------------------------------------
    def pick_files(self) -> dict:
        if not self.window:
            return {"ok": False}
        import webview
        types = ("Media (*.exo;*.mp4;*.mkv;*.mov;*.m4v;*.m4a;*.aac;*.wav;*.flac;*.ts;*.m2ts)",
                 "All files (*.*)")
        res = self.window.create_file_dialog(webview.OPEN_DIALOG, allow_multiple=True, file_types=types)
        if not res:
            return {"ok": False}
        self.inputs = [Path(p) for p in res]
        self.input_mode = "files"
        _push_recent("inputs", str(self.inputs[0].parent))
        return {"ok": True, "mode": "files", "count": len(self.inputs),
                "label": self._input_label()}

    def pick_folder(self) -> dict:
        if not self.window:
            return {"ok": False}
        import webview
        res = self.window.create_file_dialog(webview.FOLDER_DIALOG)
        if not res:
            return {"ok": False}
        self.inputs = [Path(res[0])]
        self.input_mode = "folder"
        _push_recent("inputs", str(self.inputs[0]))
        return {"ok": True, "mode": "folder", "count": 1, "label": self._input_label()}

    def set_dropped(self, paths: list[str]) -> dict:
        cleaned = [Path(p) for p in paths if p]
        if not cleaned:
            return {"ok": False}
        self.inputs = cleaned
        self.input_mode = "folder" if (len(cleaned) == 1 and cleaned[0].is_dir()) else "files"
        recent = str(cleaned[0]) if self.input_mode == "folder" else str(cleaned[0].parent)
        _push_recent("inputs", recent)
        return {"ok": True, "mode": self.input_mode, "count": len(cleaned),
                "label": self._input_label()}

    def pick_output_folder(self) -> dict:
        if not self.window:
            return {"ok": False}
        import webview
        res = self.window.create_file_dialog(webview.FOLDER_DIALOG)
        if not res:
            return {"ok": False}
        _push_recent("outputs", str(Path(res[0])))
        return {"ok": True, "path": str(Path(res[0]))}

    def get_recents(self) -> dict:
        return _load_recents()

    def use_input(self, path: str) -> dict:
        """Select a recent input folder without a dialog."""
        p = Path(path)
        if not p.exists():
            return {"ok": False, "error": "Folder no longer exists."}
        self.inputs = [p]
        self.input_mode = "folder" if p.is_dir() else "files"
        _push_recent("inputs", str(p if p.is_dir() else p.parent))
        return {"ok": True, "mode": self.input_mode, "count": 1, "label": self._input_label()}

    def _input_label(self) -> str:
        if not self.inputs:
            return "No input selected"
        if self.input_mode == "folder":
            return f"Folder: {self.inputs[0]}"
        return f"{len(self.inputs)} file(s) selected"

    # -- config ------------------------------------------------------------
    def _build_config(self, c: dict) -> Config:
        if not self.inputs:
            raise RuntimeError("Select input files or a folder first.")
        out_folder = Path(c.get("outFolder", "")).expanduser()
        if not str(out_folder):
            raise RuntimeError("Choose an output folder.")
        name = safe_name(c.get("outName", "output"))
        fmt = c.get("format", "mkv")
        if fmt not in ("mkv", "mp4"):
            fmt = "mkv"
        quality = c.get("quality", "copy_first")
        if quality not in ("copy_first", "normalize_all"):
            quality = "copy_first"
        output = out_folder / f"{name}.{fmt}"
        return Config(
            quality=quality,
            hardware=c.get("hardware", "gpu_max"),
            size=c.get("size", "large"),
            output_format=fmt,
            output=output,
            inputs=list(self.inputs),
            recursive=bool(c.get("recursive", True)),
        )

    def resolve_output(self, c: dict) -> dict:
        try:
            cfg = self._build_config(c)
            return {"ok": True, "path": str(cfg.output), "exists": cfg.output.exists()}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    # -- run controls ------------------------------------------------------
    def analyze(self, c: dict) -> dict:
        if self._busy():
            return {"ok": False, "error": "A job is already running."}
        try:
            cfg = self._build_config(c)
        except Exception as e:
            return {"ok": False, "error": str(e)}
        self.cancel_event = threading.Event()
        self.pipeline = Pipeline(self._emit, self.cancel_event)

        def work():
            try:
                self.pipeline.analyze(cfg)
            except Exception as e:
                self._emit({"type": "error", "stage": "ANALYZE", "problem": str(e),
                            "logText": str(e)})
        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()
        return {"ok": True}

    def start(self, c: dict) -> dict:
        if self._busy():
            return {"ok": False, "error": "A job is already running."}
        try:
            cfg = self._build_config(c)
        except Exception as e:
            return {"ok": False, "error": str(e)}
        self.last_output = cfg.output
        _push_recent("outputs", str(cfg.output.parent))
        self.cancel_event = threading.Event()
        self.pipeline = Pipeline(self._emit, self.cancel_event)

        def work():
            self.pipeline.run(cfg)
        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()
        return {"ok": True, "output": str(cfg.output)}

    def cancel(self) -> dict:
        self.cancel_event.set()
        if self.pipeline and self.pipeline.ff:
            self.pipeline.ff.cancel()
        return {"ok": True}

    # -- utilities ---------------------------------------------------------
    def open_path(self, path: str) -> dict:
        try:
            p = Path(path)
            if os.name == "nt":
                os.startfile(str(p))  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(p)])
            else:
                subprocess.Popen(["xdg-open", str(p)])
            return {"ok": True}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def reveal_output(self) -> dict:
        if self.last_output and self.last_output.exists():
            return self.open_path(str(self.last_output.parent))
        return {"ok": False, "error": "No output yet."}

    def play_preview(self) -> dict:
        p = self.pipeline.last_preview if self.pipeline else None
        if p and Path(p).exists():
            return self.open_path(str(p))
        return {"ok": False, "error": "No preview available yet."}

    def save_log(self, text: str) -> dict:
        if not self.window:
            return {"ok": False}
        import webview
        res = self.window.create_file_dialog(webview.SAVE_DIALOG, save_filename="job.log")
        if not res:
            return {"ok": False}
        try:
            path = res if isinstance(res, str) else res[0]
            Path(path).write_text(text or "", encoding="utf-8")
            return {"ok": True, "path": str(path)}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def copy_to_clipboard(self, text: str) -> dict:
        """Server-side clipboard copy.

        The WebView2 host often blocks navigator.clipboard (non-secure origin),
        so the UI falls back to this. Uses the OS clipboard utility.
        """
        text = text or ""
        try:
            if os.name == "nt":
                p = subprocess.run(["clip"], input=text.encode("utf-16-le"),
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                return {"ok": p.returncode == 0}
            if sys.platform == "darwin":
                p = subprocess.run(["pbcopy"], input=text.encode("utf-8"))
                return {"ok": p.returncode == 0}
            for tool in (["xclip", "-selection", "clipboard"], ["xsel", "--clipboard", "--input"]):
                try:
                    p = subprocess.run(tool, input=text.encode("utf-8"))
                    if p.returncode == 0:
                        return {"ok": True}
                except FileNotFoundError:
                    continue
            return {"ok": False, "error": "No clipboard utility available."}
        except Exception as e:
            return {"ok": False, "error": str(e)}
