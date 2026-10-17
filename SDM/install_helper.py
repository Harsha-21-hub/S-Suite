#!/usr/bin/env python3
"""
SDM installer helper (called by install.bat / install.sh).

  install    register the browser helper, create shortcuts (+ auto-start)
  uninstall  remove all of that again (your downloads are not touched)
"""
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
HOST = "com.fastdl.host"
EXT_ID = "bffcpmpannajnnlifkbphndblmljgcmi"   # fixed by the "key" in extension/manifest.json
WIN_BROWSERS = [
    r"Software\Google\Chrome\NativeMessagingHosts",
    r"Software\Microsoft\Edge\NativeMessagingHosts",
    r"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts",
    r"Software\Chromium\NativeMessagingHosts",
    r"Software\Vivaldi\NativeMessagingHosts",
]


def say(msg):
    print("   " + msg)


def manifest(path_to_host):
    return {
        "name": HOST,
        "description": "Starts the SDM download engine",
        "path": str(path_to_host),
        "type": "stdio",
        "allowed_origins": [f"chrome-extension://{EXT_ID}/"],
    }


def py_exes():
    """(console python for the browser bridge, windowless pythonw for the app)."""
    exe = Path(sys.executable)
    if exe.name.lower() == "pythonw.exe" and exe.with_name("python.exe").exists():
        exe = exe.with_name("python.exe")
    w = exe.with_name("pythonw.exe")
    return str(exe), str(w if w.exists() else exe)


# ------------------------------------------------------------------ windows
def ps_quote(s):
    return "'" + str(s).replace("'", "''") + "'"


def make_shortcut(lnk, target, args, workdir, icon, desc):
    ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut({ps_quote(lnk)});"
          f"$s.TargetPath={ps_quote(target)};$s.Arguments={ps_quote(args)};"
          f"$s.WorkingDirectory={ps_quote(workdir)};$s.IconLocation={ps_quote(icon)};"
          f"$s.Description={ps_quote(desc)};$s.Save()")
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
                   check=True, capture_output=True, creationflags=0x08000000)


def win_paths():
    appdata = Path(os.environ["APPDATA"])
    start_menu = appdata / "Microsoft" / "Windows" / "Start Menu" / "Programs"
    desktop = Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Desktop"
    try:  # real (possibly OneDrive-redirected) desktop
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "[Environment]::GetFolderPath('Desktop')"],
                             capture_output=True, text=True, creationflags=0x08000000)
        if out.stdout.strip():
            desktop = Path(out.stdout.strip())
    except Exception:
        pass
    return {
        "desktop": desktop / "SDM.lnk",
        "menu": start_menu / "SDM.lnk",
        "engine": start_menu / "SDM (engine).lnk",
        "startup": start_menu / "Startup" / "SDM (background).lnk",
    }


def _manifest_path():
    return ROOT / "native" / f"{HOST}.json"


def needs_registration():
    """True when the browser bridge is missing or points at another folder / Python."""
    man = _manifest_path()
    try:
        data = json.loads(man.read_text(encoding="utf-8"))
        host = Path(data["path"])
        if not host.exists() or host.name not in ("sdm_host.bat", "sdm_host.sh"):
            return True
        py, _ = py_exes()
        if sys.platform.startswith("win"):
            if py.lower() not in host.read_text(encoding="utf-8").lower():
                return True
            import winreg
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, WIN_BROWSERS[0] + "\\" + HOST)
            val, _t = winreg.QueryValueEx(k, "")
            winreg.CloseKey(k)
            return Path(val).resolve() != man.resolve()
        return False
    except Exception:
        return True


def register_native():
    """Write the bridge files for this folder + Python and register them with the browsers."""
    if sys.platform.startswith("win"):
        import winreg
        py, _ = py_exes()
        native = ROOT / "native"
        native.mkdir(exist_ok=True)
        bat = native / "sdm_host.bat"
        bat.write_text(f'@echo off\r\n"{py}" "{ROOT / "native_host.py"}" %*\r\n', encoding="utf-8")
        man = _manifest_path()
        man.write_text(json.dumps(manifest(bat), indent=2), encoding="utf-8")
        for key in WIN_BROWSERS:
            k = winreg.CreateKey(winreg.HKEY_CURRENT_USER, key + "\\" + HOST)
            winreg.SetValueEx(k, "", 0, winreg.REG_SZ, str(man))
            winreg.CloseKey(k)
    else:
        native = ROOT / "native"
        native.mkdir(exist_ok=True)
        sh = native / "sdm_host.sh"
        sh.write_text(f'#!/bin/sh\nexec "{py_exes()[0]}" "{ROOT / "native_host.py"}" "$@"\n')
        sh.chmod(0o755)
        _manifest_path().write_text(json.dumps(manifest(sh), indent=2))
        for d in unix_dirs():
            if d.parent.exists():
                d.mkdir(parents=True, exist_ok=True)
                (d / f"{HOST}.json").write_text(json.dumps(manifest(sh), indent=2))


def remember_python():
    _, pyw = py_exes()
    try:
        (ROOT / "python_path.txt").write_text(pyw, encoding="utf-8")
    except OSError:
        pass


def _launch_target():
    """(program, args) that opens SDM: the native SDM.exe when present, else pythonw sdm.py."""
    _, pyw = py_exes()
    exe = ROOT / "SDM.exe"
    if exe.exists():
        return str(exe), ""
    return pyw, f'"{ROOT / "sdm.py"}"'


def set_startup(enable):
    """Start SDM silently when you sign in (Windows Startup folder / Linux autostart)."""
    if sys.platform.startswith("win"):
        lnk = win_paths()["startup"]
        if enable:
            target, args = _launch_target()
            make_shortcut(lnk, target, (args + " --no-window").strip(), ROOT, str(ROOT / "sdm.ico"), "SDM engine")
        else:
            try:
                lnk.unlink()
            except OSError:
                pass
        return
    auto = Path.home() / ".config/autostart/sdm.desktop"
    if enable:
        auto.parent.mkdir(parents=True, exist_ok=True)
        auto.write_text(f"[Desktop Entry]\nType=Application\nName=SDM engine\n"
                        f'Exec="{sys.executable}" "{ROOT / "sdm.py"}" --no-window\nTerminal=false\n')
    else:
        try:
            auto.unlink()
        except OSError:
            pass


def startup_enabled():
    if sys.platform.startswith("win"):
        return win_paths()["startup"].exists()
    return (Path.home() / ".config/autostart/sdm.desktop").exists()


MAGNET_KEYS = (r"Software\Classes\magnet", r"Software\Classes\SDM.torrent")


def set_magnet_handler(enable):
    """Open magnet: links and .torrent files with SDM (current user only). Windows."""
    if not sys.platform.startswith("win"):
        return
    import winreg
    target, args = _launch_target()
    cmd = f'"{target}" {args} "%1"'.replace("  ", " ")
    HK = winreg.HKEY_CURRENT_USER

    def put(path, name, value):
        k = winreg.CreateKey(HK, path)
        winreg.SetValueEx(k, name, 0, winreg.REG_SZ, value)
        winreg.CloseKey(k)

    def ours(path):
        try:
            k = winreg.OpenKey(HK, path + r"\shell\open\command")
            v, _t = winreg.QueryValueEx(k, "")
            winreg.CloseKey(k)
            return str(ROOT).lower() in v.lower() or "fastdl" in v.lower()
        except OSError:
            return False

    def delete_tree(path):
        try:
            k = winreg.OpenKey(HK, path, 0, winreg.KEY_ALL_ACCESS)
            while True:
                try:
                    sub = winreg.EnumKey(k, 0)
                except OSError:
                    break
                delete_tree(path + "\\" + sub)
            winreg.CloseKey(k)
            winreg.DeleteKey(HK, path)
        except OSError:
            pass

    if enable:
        put(MAGNET_KEYS[0], "", "URL:Magnet link")
        put(MAGNET_KEYS[0], "URL Protocol", "")
        put(MAGNET_KEYS[0] + r"\DefaultIcon", "", str(ROOT / "sdm.ico"))
        put(MAGNET_KEYS[0] + r"\shell\open\command", "", cmd)
        put(MAGNET_KEYS[1], "", "Torrent file (SDM)")
        put(MAGNET_KEYS[1] + r"\DefaultIcon", "", str(ROOT / "sdm.ico"))
        put(MAGNET_KEYS[1] + r"\shell\open\command", "", cmd)
        put(r"Software\Classes\.torrent", "", "SDM.torrent")
    else:
        for path in MAGNET_KEYS:
            if ours(path):
                delete_tree(path)
        try:
            k = winreg.OpenKey(HK, r"Software\Classes\.torrent")
            v, _t = winreg.QueryValueEx(k, "")
            winreg.CloseKey(k)
            if v == "SDM.torrent":
                delete_tree(r"Software\Classes\.torrent")
        except OSError:
            pass
    try:   # tell Explorer that associations changed
        import ctypes
        ctypes.windll.shell32.SHChangeNotify(0x08000000, 0, None, None)
    except Exception:
        pass


LEGACY_FILES = ("FastDL.exe", "FastDL.bat", "fastdl.py", "fastdl.ico", "fdl_torrent.py", "fdl_tray.py",
                "native/fastdl_host.bat", "native/fastdl_host.sh", "launcher/fastdl_launcher.c", "launcher/fastdl.rc")


def migrate_from_fastdl():
    """After the FastDL -> SDM rename: swap old shortcuts for SDM ones, remove the old program files."""
    if sys.platform.startswith("win"):
        appdata = Path(os.environ["APPDATA"])
        start_menu = appdata / "Microsoft" / "Windows" / "Start Menu" / "Programs"
        new = win_paths()
        old = {"desktop": new["desktop"].parent / "FastDL.lnk", "menu": start_menu / "FastDL.lnk",
               "engine": start_menu / "FastDL (engine).lnk", "startup": start_menu / "Startup" / "FastDL (background).lnk"}
        target, args = _launch_target()
        icon = str(ROOT / "sdm.ico")
        for key, lnk in old.items():
            if not lnk.exists():
                continue
            try:
                lnk.unlink()
            except OSError:
                continue
            if key == "startup":
                continue                     # follows the "Start with Windows" setting instead
            if not new[key].exists():
                try:
                    a = (args + " --no-window").strip() if key == "engine" else args
                    make_shortcut(new[key], target, a, ROOT, icon, "SDM - S Download Manager")
                except Exception:
                    pass
    else:
        old = Path.home() / ".local/share/applications/fastdl.desktop"
        if old.exists():
            try:
                old.unlink()
            except OSError:
                pass
    for rel in LEGACY_FILES:
        f = ROOT / rel
        if f.is_file():
            try:
                f.unlink()
            except OSError:
                pass


def repair_shortcuts():
    """After the SDM folder was moved or renamed: point the existing SDM shortcuts at this folder.
    Only shortcuts that already exist are touched (a deleted shortcut stays deleted)."""
    if not sys.platform.startswith("win"):
        return []
    p = win_paths()
    keys = [k for k in ("desktop", "menu", "engine") if p[k].exists()]
    if not keys:
        return []
    read = ";".join(f"$w.CreateShortcut({ps_quote(p[k])}).TargetPath + '|' + $w.CreateShortcut({ps_quote(p[k])}).Arguments"
                    for k in keys)
    out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                          "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                          "$w=New-Object -ComObject WScript.Shell;" + read],
                         capture_output=True, text=True, encoding="utf-8", errors="replace",
                         creationflags=0x08000000)
    lines = [ln.strip().lstrip("\ufeff") for ln in (out.stdout or "").splitlines() if ln.strip()]
    if out.returncode != 0 or len(lines) != len(keys):
        return []
    root = str(ROOT).lower().rstrip("\\")
    target, args = _launch_target()
    icon = str(ROOT / "sdm.ico")
    fixed = []
    for key, line in zip(keys, lines):
        tgt, _sep, a = line.partition("|")
        here = (tgt + " " + a).lower()
        exists = Path(tgt).exists() if tgt else False
        if exists and (root + "\\") in here:
            continue                                   # already points at this folder
        try:
            make_shortcut(p[key], target, (args + " --no-window").strip() if key == "engine" else args,
                          ROOT, icon, "SDM - S Download Manager")
            fixed.append(key)
        except Exception:
            pass
    return fixed


def install_windows(startup=False):
    _, pyw = py_exes()
    register_native()
    remember_python()
    say("Browser helper registered (Chrome, Edge, Brave, Chromium, Vivaldi)")

    icon = str(ROOT / "sdm.ico")
    p = win_paths()
    exe = ROOT / "SDM.exe"
    if exe.exists():            # native launcher: finds Python + checks everything itself
        target, args = str(exe), ""
    else:
        target, args = pyw, f'"{ROOT / "sdm.py"}"'
    bg = (args + " --no-window").strip()
    try:
        make_shortcut(p["desktop"], target, args, ROOT, icon, "SDM download manager")
        make_shortcut(p["menu"], target, args, ROOT, icon, "SDM download manager")
        make_shortcut(p["engine"], target, bg, ROOT, icon, "SDM engine (no window)")
        say("Shortcuts created: Desktop + Start menu")
        set_startup(startup)
        if startup:
            say("Starts silently with Windows")
    except Exception as e:
        say(f"Could not create shortcuts ({e}). Start SDM with:  pythonw sdm.py")


def uninstall_windows():
    import winreg
    for key in WIN_BROWSERS:
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, key + "\\" + HOST)
        except OSError:
            pass
    for lnk in win_paths().values():
        try:
            lnk.unlink()
        except OSError:
            pass
    set_magnet_handler(False)
    say("Helper and shortcuts removed")


# ------------------------------------------------------------------ mac / linux
def unix_dirs():
    h = Path.home()
    if sys.platform == "darwin":
        base = h / "Library" / "Application Support"
        return [base / "Google/Chrome/NativeMessagingHosts", base / "Microsoft Edge/NativeMessagingHosts",
                base / "BraveSoftware/Brave-Browser/NativeMessagingHosts", base / "Chromium/NativeMessagingHosts",
                base / "Vivaldi/NativeMessagingHosts"]
    c = h / ".config"
    return [c / "google-chrome/NativeMessagingHosts", c / "microsoft-edge/NativeMessagingHosts",
            c / "BraveSoftware/Brave-Browser/NativeMessagingHosts", c / "chromium/NativeMessagingHosts",
            c / "vivaldi/NativeMessagingHosts"]


def install_unix(startup=False):
    register_native()
    say("Browser helper registered")
    if sys.platform.startswith("linux"):
        apps = Path.home() / ".local/share/applications"
        apps.mkdir(parents=True, exist_ok=True)
        (apps / "sdm.desktop").write_text(
            f"[Desktop Entry]\nType=Application\nName=SDM\nComment=Fast download manager\n"
            f'Exec="{sys.executable}" "{ROOT / "sdm.py"}"\nIcon={ROOT / "ui" / "icon.png"}\n'
            f"Terminal=false\nCategories=Network;FileTransfer;\n")
        set_startup(startup)
        say("App menu entry created")


def uninstall_unix():
    for d in unix_dirs():
        try:
            (d / f"{HOST}.json").unlink()
        except OSError:
            pass
    for f in (Path.home() / ".local/share/applications/sdm.desktop", Path.home() / ".config/autostart/sdm.desktop"):
        try:
            f.unlink()
        except OSError:
            pass
    say("Helper removed")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "install"
    startup = "--startup" in sys.argv          # off unless asked: open SDM yourself
    win = sys.platform.startswith("win")
    if cmd == "install":
        install_windows(startup) if win else install_unix(startup)
    elif cmd == "uninstall":
        uninstall_windows() if win else uninstall_unix()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
