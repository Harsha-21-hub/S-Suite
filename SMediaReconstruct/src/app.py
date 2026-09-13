"""S-MediaReconstruct — entry point.

Launches a frameless-friendly pywebview window that hosts the Nothing-styled
HTML UI (ui/index.html) and bridges to the Python media engine via api.Api.
"""
from __future__ import annotations

import ctypes
import os
import sys
from pathlib import Path

import webview

from api import Api

APP_NAME = "S-MediaReconstruct"
APP_VERSION = "1.0.0"


def resource(rel: str) -> Path:
    """
    Resolve packaged resources robustly for both:
      - normal Python execution
      - PyInstaller --onefile
      - PyInstaller --onedir
    """
    candidates = []

    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        base = Path(meipass)
        candidates.extend([
            base / rel,
            base / "src" / rel,
        ])

    exe_dir = Path(sys.executable).resolve().parent
    candidates.extend([
        exe_dir / rel,
        exe_dir / "src" / rel,
    ])

    source_dir = Path(__file__).resolve().parent
    candidates.extend([
        source_dir / rel,
        source_dir.parent / rel,
    ])

    for candidate in candidates:
        if candidate.exists():
            return candidate

    # Return the primary packaged location for an informative error.
    if meipass:
        return Path(meipass) / rel
    return source_dir / rel


def load_local_nothing_fonts():
    """Register any user-supplied Nothing/Ndot fonts (Windows, private scope)."""
    if os.name != "nt":
        return
    font_dir = resource("fonts")
    if not font_dir.exists():
        return
    try:
        add = ctypes.windll.gdi32.AddFontResourceExW
        FR_PRIVATE = 0x10
        for fp in font_dir.iterdir():
            if fp.suffix.lower() in (".ttf", ".otf"):
                add(str(fp), FR_PRIVATE, 0)
    except Exception:
        pass


def main():
    if os.name == "nt":
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass

    load_local_nothing_fonts()

    # Keep application UI in a dedicated package directory ("smr_ui") so
    # PyInstaller/pywebview cannot collide with webview's own bundled data.
    index = resource("smr_ui/index.html")

    if not index.is_file():
        raise FileNotFoundError(
            "Bundled UI file was not found.\n\n"
            f"Expected: {index}\n\n"
            "The executable was built without the S-MediaReconstruct UI assets. "
            "Rebuild using the corrected S-MediaReconstruct.spec."
        )

    html = index.read_text(encoding="utf-8")

    api = Api()
    window = webview.create_window(
        title=f"{APP_NAME} {APP_VERSION}",
        html=html,
        js_api=api,
        width=1180,
        height=900,
        min_size=(980, 680),
        background_color="#000000",
        text_select=False,
    )
    api.set_window(window)

    gui = "edgechromium" if os.name == "nt" else None
    webview.start(gui=gui, debug=False, private_mode=False)


if __name__ == "__main__":
    main()
