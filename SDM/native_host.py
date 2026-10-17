#!/usr/bin/env python3
"""
SDM native messaging helper.

The browser extension calls this (through Chrome/Edge native messaging) when the
SDM engine is not running, so a caught download can still go to SDM.
Protocol: 4-byte little-endian length + JSON, on stdin/stdout.
"""
import json
import os
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PORT = 6880
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read_msg():
    raw = sys.stdin.buffer.read(4)
    if len(raw) < 4:
        return None
    n = struct.unpack("<I", raw)[0]
    return json.loads(sys.stdin.buffer.read(n).decode("utf-8"))


def send_msg(obj):
    data = json.dumps(obj).encode("utf-8")
    sys.stdout.buffer.write(struct.pack("<I", len(data)) + data)
    sys.stdout.buffer.flush()


def alive():
    try:
        with OPENER.open(f"http://127.0.0.1:{PORT}/api/ping", timeout=1) as r:
            return json.loads(r.read()).get("app") in ("SDM", "FastDL")
    except Exception:
        return False


def post(path):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=b"{}",
                                 headers={"Content-Type": "application/json"}, method="POST")
    with OPENER.open(req, timeout=3):
        pass


def pythonw():
    exe = Path(sys.executable)
    if os.name == "nt":
        w = exe.with_name("pythonw.exe")
        if w.exists():
            return str(w)
    return str(exe)


def launch():
    exe = ROOT / "SDM.exe"
    if os.name == "nt" and exe.exists():
        args = [str(exe), "--no-window"]          # shows as "SDM - S Download Manager" in Task Manager
    else:
        args = [pythonw(), str(ROOT / "sdm.py"), "--no-window"]
    if os.name == "nt":
        DETACHED, NEW_GROUP, BREAKAWAY, NO_WINDOW = 0x8, 0x200, 0x01000000, 0x08000000
        try:
            # break out of the browser's job object so SDM keeps running
            subprocess.Popen(args, cwd=str(ROOT), creationflags=DETACHED | NEW_GROUP | BREAKAWAY,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             close_fds=True)
            return
        except OSError:
            pass
        lnk = Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs/SDM (engine).lnk"
        if lnk.exists():
            # explorer starts the shortcut outside the browser's process tree
            subprocess.Popen(["explorer.exe", str(lnk)], creationflags=NO_WINDOW)
            return
        subprocess.Popen(args, cwd=str(ROOT), creationflags=DETACHED | NEW_GROUP,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        subprocess.Popen(args, cwd=str(ROOT), start_new_session=True,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def wake_allowed():
    """Settings > 'Browser can start SDM when it is closed' (off by default)."""
    if os.environ.get("SDM_HOME"):
        base = Path(os.environ["SDM_HOME"])
    elif os.name == "nt":
        base = Path(os.environ.get("APPDATA", str(Path.home()))) / "SDM"
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / "SDM"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "SDM"
    try:
        return bool(json.loads((base / "config.json").read_text(encoding="utf-8")).get("wake_on_catch", False))
    except Exception:
        return False


def main():
    msg = read_msg() or {}
    cmd = msg.get("cmd", "start")
    ok = alive()
    if not ok and cmd == "start" and not wake_allowed():
        # automatic catch while SDM is closed: the browser keeps the download
        send_msg({"ok": False, "reason": "SDM is closed (auto-start is off in Settings)"})
        return
    if not ok:
        launch()
        for _ in range(60):
            time.sleep(0.25)
            if alive():
                ok = True
                break
    if ok and cmd == "open":
        try:
            post("/api/open_window")
        except Exception:
            pass
    send_msg({"ok": ok})


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # always answer, never hang the browser
        try:
            send_msg({"ok": False, "error": str(e)})
        except Exception:
            pass
