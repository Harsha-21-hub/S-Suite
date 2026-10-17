#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SDM - multi-connection download manager.

Engine + local API + UI server, all in one process.
  * Splits every file into many parallel ranged connections (default 16, up to 64)
  * Dynamic segmentation: when a connection finishes it steals half of the
    biggest remaining piece, so all connections stay busy until the last byte
  * Pause / resume (survives app restarts), auto-retry, throttle detection
  * Cookies / referrer / user-agent forwarded from the browser extension so
    logged-in downloads work
  * Optional media mode through yt-dlp (video pages, HLS/DASH streams)

Run:  python sdm.py            -> starts engine and opens the app window
      python sdm.py --no-window
      python sdm.py --add URL  -> hand a link to the running instance
      python sdm.py --quit
"""
import argparse
import asyncio
import base64
import ftplib
import importlib.util
import json
import logging
import math
import os
import re
import shutil
import socket
import string
import subprocess
import sys
import threading
import time
import uuid
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.cookies import Morsel
from pathlib import Path
from urllib.parse import unquote, urlparse

# SDM.exe hosts Python inside its own process, so Task Manager lists "SDM - S Download Manager" instead of
# "Python". Child processes (pip, the browser helper, shortcuts) still need the real interpreter: pythonw.exe in
# that Python's own folder (SDM.exe also passes its path along). PYTHONHOME, needed only to start the hosted
# runtime, is not handed down to them.
def _real_interpreter():
    if os.name != "nt":
        return None
    name = os.path.basename(sys.executable or "").lower()
    if name in ("python.exe", "pythonw.exe") or (name != "sdm.exe" and not os.environ.get("SDM_PYTHONW")):
        return None                                   # started the classic way (pythonw sdm.py)
    given = os.environ.get("SDM_PYTHONW") or ""
    for cand in (given, os.path.join(sys.base_prefix, "pythonw.exe"), os.path.join(sys.base_prefix, "python.exe")):
        if cand and os.path.isfile(cand):
            return cand
    return None


_REAL_PY = _real_interpreter()
HOSTED = _REAL_PY is not None
os.environ.pop("SDM_PYTHONW", None)
if HOSTED:
    os.environ.pop("PYTHONHOME", None)
    sys.executable = _REAL_PY
    try:
        sys._base_executable = _REAL_PY
    except Exception:
        pass


def _message(text, error=False, wait=False):
    """Tell the user something even when running windowless (pythonw)."""
    if sys.platform.startswith("win"):
        try:
            import ctypes
            import threading as _t
            flags = 0x10 if error else 0x40
            show = lambda: ctypes.windll.user32.MessageBoxW(None, text, "SDM", flags | 0x40000)
            if wait:
                show()
            else:
                _t.Thread(target=show, daemon=True).start()
            return
        except Exception:
            pass
    try:
        print(text)
    except Exception:
        pass


def _pip_install(*pkgs):
    """pip install into *this* Python (user site first). Returns True on success."""
    flags = 0x08000000 if sys.platform.startswith("win") else 0  # CREATE_NO_WINDOW
    py = sys.executable
    if sys.platform.startswith("win") and py.lower().endswith("pythonw.exe"):
        cand = py[:-len("pythonw.exe")] + "python.exe"
        py = cand if os.path.exists(cand) else py
    base = [py, "-m", "pip", "install", "--disable-pip-version-check", "-q", "--no-warn-script-location"]
    for extra in (["--user"], [], ["--break-system-packages", "--user"]):
        try:
            r = subprocess.run(base + extra + list(pkgs), capture_output=True, creationflags=flags, timeout=900)
        except Exception:
            continue
        if r.returncode == 0:
            try:
                import site
                site.addsitedir(site.getusersitepackages())   # user site may not have existed at startup
            except Exception:
                pass
            importlib.invalidate_caches()
            return True
    return False


def _bootstrap():
    """First run on a new Python: install the one required package silently."""
    if importlib.util.find_spec("aiohttp") is not None:
        return
    _message("SDM is installing a required component (aiohttp).\n\nThis happens only once and takes less than a minute.")
    if not _pip_install("aiohttp>=3.9") or importlib.util.find_spec("aiohttp") is None:
        _message("SDM could not install aiohttp.\n\nCheck your internet connection, then run install.bat.", error=True, wait=True)
        sys.exit(1)


_bootstrap()
import aiohttp  # noqa: E402
import sdm_torrent  # noqa: E402
from aiohttp import web  # noqa: E402
from aiohttp.abc import AbstractResolver  # noqa: E402
from yarl import URL  # noqa: E402

VERSION = "2.1.1"
DEFAULT_PORT = 6880
ROOT = Path(__file__).resolve().parent
UI_DIR = ROOT / "ui"

LEGACY_DIR = None     # data folder of the old "FastDL" name (migrated on first start)
if os.environ.get("SDM_HOME"):
    APP_DIR = Path(os.environ["SDM_HOME"])
    if os.environ.get("FASTDL_HOME"):
        LEGACY_DIR = Path(os.environ["FASTDL_HOME"])
elif os.name == "nt":
    APP_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "SDM"
    LEGACY_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "FastDL"
elif sys.platform == "darwin":
    APP_DIR = Path.home() / "Library" / "Application Support" / "SDM"
    LEGACY_DIR = Path.home() / "Library" / "Application Support" / "FastDL"
else:
    APP_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "SDM"
    LEGACY_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "FastDL"
APP_DIR.mkdir(parents=True, exist_ok=True)

DB_FILE = APP_DIR / "downloads.json"
CFG_FILE = APP_DIR / "config.json"
ADDED_TORRENTS = APP_DIR / "torrents" / "added"   # .torrent files added from the window (button / drag & drop)
LOG_FILE = APP_DIR / "sdm.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.FileHandler(LOG_FILE, encoding="utf-8")]
    + ([logging.StreamHandler()] if sys.stderr else []),
)
log = logging.getLogger("sdm")

HAVE_YTDLP = importlib.util.find_spec("yt_dlp") is not None

def _bigger_socket_reads(size=256 * 1024):
    """asyncio reads at most 64 KB per socket read on Windows: at 1 GB/s that is ~16,000 trips through the
    event loop every second. 256 KB reads (also used for TLS) cut that 4x; in tests larger reads were no
    faster but used far more memory with many connections."""
    try:
        import inspect
        import asyncio.proactor_events as pe
        orig = pe._ProactorReadPipeTransport.__init__
        params = list(inspect.signature(orig).parameters)
        if not getattr(orig, "_sdm", False) and "buffer_size" in params:
            pos = params.index("buffer_size") - 1          # index in *args (without self)

            def __init__(self, *a, **kw):
                if len(a) > pos:
                    a = a[:pos] + (max(int(a[pos]), size),) + a[pos + 1:]
                else:
                    kw["buffer_size"] = max(int(kw.get("buffer_size", 65536)), size)
                orig(self, *a, **kw)
            __init__._sdm = True
            pe._ProactorReadPipeTransport.__init__ = __init__
    except Exception:
        pass
    try:
        import asyncio.selector_events as se
        if se._SelectorTransport.max_size < size:
            se._SelectorTransport.max_size = size
    except Exception:
        pass
    try:
        import asyncio.sslproto as sp                  # TLS: decrypt in the same bigger steps
        if getattr(sp.SSLProtocol, "max_size", size) < size:
            sp.SSLProtocol.max_size = size
    except Exception:
        pass


_bigger_socket_reads(max(64, min(4096, int(os.environ.get("SDM_READ_KB") or 256))) * 1024)


def default_download_dir():
    return str(Path.home() / "Downloads" / "SDM")


DEFAULT_CONFIG = {
    "download_dir": default_download_dir(),
    "ask_location": True,          # show "where to save" dialog for each new download
    "categorize": True,            # sort into Video / Music / ... sub folders
    "connections": 32,             # parallel connections per file (1-256)
    "max_active": 4,               # simultaneous downloads
    "speed_limit_kb": 0,           # 0 = unlimited
    "intercept": True,             # browser extension catches downloads
    "min_size_kb": 0,              # browser downloads smaller than this stay in the browser
    "skip_sites": "",
    "theme": "dark",
    "sound": True,
    "recent_dirs": [],
    "open_window_on_catch": True,
    "speed_unit": "MBps",          # MBps | Mbps
    "dns_mode": "auto",            # system | auto (system, then DNS-over-HTTPS) | doh (always secure DNS)
    "allow_insecure": False,       # accept servers with broken TLS certificates
    "proxy": "",                   # e.g. http://127.0.0.1:8080  (empty = system proxy)
    "seed_after": False,           # keep sharing finished torrents (until ratio 1.0)
    "handle_magnets": False,       # open magnet: links and .torrent files with SDM (Windows)
    "start_with_windows": False,   # start silently at sign-in
    "tray": True,                  # tray icon; closing the window keeps SDM running
    "wake_on_catch": False,        # browser extension may start SDM when it is closed
    "keep_awake": True,            # Windows: no sleep while downloads are running
}

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
)

CATEGORIES = {
    "Video": "mp4 mkv webm avi mov flv wmv m4v ts 3gp mpeg mpg m2ts vob ogv",
    "Music": "mp3 flac wav aac m4a ogg opus wma alac aiff mka",
    "Archives": "zip rar 7z tar gz bz2 xz zst iso img tgz cab lz lzma tbz2 txz",
    "Programs": "exe msi msix msixbundle appx appxbundle apk xapk deb rpm appimage pkg dmg jar run",
    "Documents": "pdf doc docx xls xlsx ppt pptx txt csv epub mobi odt ods odp rtf md json xml azw3 djvu",
    "Images": "jpg jpeg png gif webp bmp tif tiff svg heic heif avif psd raw cr2 nef ico",
    "Torrents": "torrent",
}
EXT_CAT = {e: c for c, exts in CATEGORIES.items() for e in exts.split()}

HTML_TYPES = {"text/html", "application/xhtml+xml"}
MANUAL_SOURCES = {"ui", "link", "cli"}   # user handed us a link (may be a video page), not a real browser download
STREAM_TYPES = {
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
    "audio/x-mpegurl", "application/dash+xml",
}

MB = 1024 * 1024
FLUSH_AT = 4 * MB            # bytes collected per connection before a disk write (upper bound)
FLUSH_MIN = 512 * 1024       # ... lower bound
FLUSH_BUDGET = 64 * MB       # total collected across one download's connections
MIN_FIRST_SPLIT = 1 * MB     # smallest piece when initially splitting
STEAL_MIN = 256 * 1024       # never split a remaining piece smaller than 2x this
RETRIES = 12
MAX_CONNS = 256
TORRENT_MAX = 64 * MB        # largest .torrent file accepted from the computer
TIMEOUT = aiohttp.ClientTimeout(total=None, sock_connect=20, sock_read=45)


# --------------------------------------------------------------------------- helpers
class HTTPStatusError(Exception):
    def __init__(self, status, reason=""):
        super().__init__(f"HTTP {status} {reason}".strip())
        self.status = status


class NoRangeSupport(Exception):
    pass


class FileChanged(Exception):
    pass


class DiskError(Exception):
    pass


class SwitchToMedia(Exception):
    pass


class SwitchToTorrent(Exception):
    pass


class UserFacingError(Exception):
    pass


def human_error(e):
    if isinstance(e, HTTPStatusError):
        return {
            401: "HTTP 401 - login required (download it from the browser so cookies are sent)",
            403: "HTTP 403 - forbidden or the link expired. Use REFRESH LINK with a new URL",
            404: "HTTP 404 - file not found on server",
            410: "HTTP 410 - link expired. Use REFRESH LINK with a new URL",
            416: "HTTP 416 - server rejected the byte range",
        }.get(e.status, str(e))
    if isinstance(e, FileChanged):
        return "File changed on the server - restart the download"
    if isinstance(e, DiskError):
        return f"Disk error: {e}"
    if isinstance(e, asyncio.TimeoutError):
        return "Connection timed out"
    if isinstance(e, socket.gaierror) or e.__class__.__name__ == "ClientConnectorDNSError":
        return "DNS could not find this host - check the link, or set DNS to Secure in Settings"
    if isinstance(e, aiohttp.ClientPayloadError):
        return "Connection dropped mid-transfer by the server/proxy - retrying"
    if isinstance(e, sdm_torrent.TorrentError):
        return "Torrent: " + str(e)
    if isinstance(e, ConnectionRefusedError):
        return "Server refused the connection (offline or wrong port)"
    if isinstance(e, (socket.timeout, TimeoutError)):
        return "Connection timed out"
    if isinstance(e, ftplib.Error):
        return "FTP: " + str(e)[:200]
    if isinstance(e, aiohttp.ClientConnectorCertificateError):
        return "Secure connection failed (certificate problem)"
    if isinstance(e, aiohttp.ClientConnectorError):
        host = getattr(e, "host", None) or "server"
        return f"Cannot reach {host} - offline, blocked or wrong address"
    if isinstance(e, aiohttp.InvalidURL):
        return "Invalid URL"
    msg = str(e) or e.__class__.__name__
    return msg[:300]


def parse_content_range(v):
    # "bytes 0-99/1234"  or "bytes */1234"
    if not v:
        return None
    m = re.match(r"\s*bytes\s+(\d+)-(\d+)/(\d+|\*)", v, re.I)
    if not m:
        return None
    total = None if m.group(3) == "*" else int(m.group(3))
    return int(m.group(1)), int(m.group(2)), total


def _fix_mojibake(s):
    try:
        return s.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


def cd_filename(cd):
    if not cd:
        return None
    m = re.search(r"filename\*\s*=\s*([\w!#$&+.^`|~-]*)'[^']*'([^;]+)", cd, re.I)
    if m:
        enc = m.group(1) or "utf-8"
        try:
            return unquote(m.group(2).strip().strip('"'), encoding=enc, errors="replace")
        except LookupError:
            return unquote(m.group(2).strip().strip('"'))
    m = re.search(r'filename\s*=\s*"((?:\\.|[^"\\])*)"', cd, re.I)
    if m:
        return _fix_mojibake(re.sub(r"\\(.)", r"\1", m.group(1)))
    m = re.search(r"filename\s*=\s*([^;]+)", cd, re.I)
    if m:
        return _fix_mojibake(unquote(m.group(1).strip()))
    return None


def name_from_url(url):
    try:
        p = urlparse(url)
    except ValueError:
        return None
    seg = unquote(p.path.rstrip("/").rsplit("/", 1)[-1]) if p.path else ""
    return seg or None


_BAD = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}


def sanitize(name):
    name = _BAD.sub("_", name or "").strip().strip(".")
    name = re.sub(r"\s+", " ", name)
    if not name:
        name = "download"
    stem, dot, ext = name.rpartition(".")
    if (stem if dot else name).upper() in _RESERVED:
        name = "_" + name
    if len(name.encode("utf-8")) > 200:
        ext = ("." + ext) if dot and len(ext) <= 10 else ""
        base = name[: -len(ext)] if ext else name
        while len((base + ext).encode("utf-8")) > 200:
            base = base[:-1]
        name = base + ext
    return name


def ext_of(name):
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def category_of(name):
    return EXT_CAT.get(ext_of(name), "Other")


def guess_ext(mime):
    import mimetypes
    if not mime:
        return ""
    special = {"video/mp4": ".mp4", "audio/mpeg": ".mp3", "application/zip": ".zip",
               "application/pdf": ".pdf", "video/webm": ".webm", "audio/mp4": ".m4a",
               "application/x-msdownload": ".exe", "application/vnd.android.package-archive": ".apk",
               "image/jpeg": ".jpg", "text/plain": ".txt"}
    return special.get(mime) or mimetypes.guess_extension(mime) or ""


def write_atomic(path, data):
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(data, encoding="utf-8")
    os.replace(tmp, path)


def build_jar(cookies):
    jar = aiohttp.CookieJar(unsafe=True, quote_cookie=False)
    for c in cookies or []:
        try:
            name, value = str(c["name"]), str(c.get("value", ""))
            domain = (c.get("domain") or "").strip()
            if not domain:
                continue
            host = domain.lstrip(".")
            mo = Morsel()
            mo.set(name, value, value)
            mo["path"] = c.get("path") or "/"
            if c.get("secure"):
                mo["secure"] = True
            if not c.get("hostOnly"):
                mo["domain"] = host
            scheme = "https" if c.get("secure") else "http"
            jar.update_cookies({name: mo}, response_url=URL(f"{scheme}://{host}/"))
        except Exception:  # odd cookie names etc. - skip
            continue
    return jar


def cookies_to_netscape(cookies, path):
    lines = ["# Netscape HTTP Cookie File"]
    for c in cookies or []:
        dom = c.get("domain") or ""
        if not dom:
            continue
        inc = "FALSE" if c.get("hostOnly") else "TRUE"
        if inc == "TRUE" and not dom.startswith("."):
            dom = "." + dom
        exp = int(c.get("expirationDate") or 0)
        lines.append("\t".join([dom, inc, c.get("path") or "/", "TRUE" if c.get("secure") else "FALSE",
                                str(exp), str(c.get("name", "")), str(c.get("value", ""))]))
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def adopt_legacy_part(it):
    """Resume a download that FastDL (SDM's old name) started: rename its .fdlpart file."""
    try:
        if it.filename and not os.path.exists(it.part_path) and os.path.exists(it.legacy_part_path):
            os.replace(it.legacy_part_path, it.part_path)
            log.info("adopted %s", it.legacy_part_path)
    except OSError as e:
        log.warning("could not adopt old partial file: %s", e)


def migrate_legacy_data():
    """First start after the FastDL -> SDM rename: carry settings, history and torrent state over."""
    if not LEGACY_DIR or not LEGACY_DIR.is_dir() or LEGACY_DIR.resolve() == APP_DIR.resolve():
        return
    if (APP_DIR / "downloads.json").exists() or (APP_DIR / "config.json").exists():
        return
    for name in ("config.json", "downloads.json", "trackers.txt", "dht.dat", "dht6.dat"):
        src = LEGACY_DIR / name
        if src.is_file():
            try:
                shutil.copy2(src, APP_DIR / name)
            except OSError as e:
                log.warning("migrate %s: %s", name, e)
    # FastDL 1.x marked running downloads as failed ("Session is closed") when it was quit.
    # Put those back in the queue so they simply continue.
    db = APP_DIR / "downloads.json"
    try:
        data = json.loads(db.read_text(encoding="utf-8"))
        fixed = 0
        for d in data.get("items", []):
            if d.get("state") == "error" and "session is closed" in str(d.get("error") or "").lower():
                d["state"], d["error"] = "queued", None
                fixed += 1
            # FastDL 1.x also permanently lowered connections after errors: restore the user's setting
            try:
                want = int(json.loads((APP_DIR / "config.json").read_text(encoding="utf-8")).get("connections", 32))
            except (OSError, ValueError):
                want = 32
            if d.get("state") != "done" and int(d.get("max_conns") or 0) < want:
                d["max_conns"] = want
                fixed += 1
        if fixed:
            write_atomic(db, json.dumps(data, ensure_ascii=False))
            log.info("repaired %d download entr(ies) left by the old version (requeued / connections restored)", fixed)
    except (OSError, ValueError):
        pass
    if (LEGACY_DIR / "torrents").is_dir():
        try:
            shutil.copytree(LEGACY_DIR / "torrents", APP_DIR / "torrents", dirs_exist_ok=True)
        except OSError:
            pass
    log.info("settings and history migrated from %s", LEGACY_DIR)


def _win_set_size(f, size):
    """Windows: make the file sparse and set its length WITHOUT writing anything.
    (Python's f.truncate() on Windows calls _chsize_s, which writes zeros over the
    whole new length - minutes for a 100+ GB file. Never use it to grow a file.)"""
    import ctypes
    import msvcrt
    from ctypes import wintypes
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.DeviceIoControl.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPVOID, wintypes.DWORD,
                                    wintypes.LPVOID, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]
    k32.DeviceIoControl.restype = wintypes.BOOL
    k32.SetFileInformationByHandle.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
    k32.SetFileInformationByHandle.restype = wintypes.BOOL

    class EndOfFileInfo(ctypes.Structure):
        _fields_ = [("EndOfFile", ctypes.c_longlong)]

    h = wintypes.HANDLE(msvcrt.get_osfhandle(f.fileno()))
    ret = wintypes.DWORD(0)
    # FSCTL_SET_SPARSE: far-away connections can write without NTFS zero-filling the gap
    k32.DeviceIoControl(h, 0x000900C4, None, 0, None, 0, ctypes.byref(ret), None)
    info = EndOfFileInfo(size)
    if not k32.SetFileInformationByHandle(h, 6, ctypes.byref(info), ctypes.sizeof(info)):  # FileEndOfFileInfo
        err = ctypes.get_last_error()
        if err in (39, 112):      # ERROR_HANDLE_DISK_FULL / ERROR_DISK_FULL
            raise OSError(28, "not enough disk space")
        raise OSError(err, ctypes.FormatError(err).strip())


def preallocate(path, size):
    """Create the .sdmpart file at its final size instantly. Runs in a worker thread."""
    with open(path, "wb") as f:
        if not size:
            return
        if sys.platform.startswith("win"):
            _win_set_size(f, size)
        else:
            os.ftruncate(f.fileno(), size)   # sparse on Linux / macOS, no data written


def which_ffmpeg():
    p = shutil.which("ffmpeg")
    if p or not sys.platform.startswith("win"):
        return p
    la = os.environ.get("LOCALAPPDATA", "")
    cands = [os.path.join(la, "Microsoft", "WinGet", "Links", "ffmpeg.exe")]
    cands += [str(x) for x in Path(la, "Microsoft", "WinGet", "Packages").glob("Gyan.FFmpeg*/*/bin/ffmpeg.exe")]
    return next((c for c in cands if os.path.exists(c)), None)


# --------------------------------------------------------------------------- DNS
class FallbackResolver(AbstractResolver):
    """System DNS first; if it fails (or mode is 'doh') resolve through DNS-over-HTTPS
    straight to Cloudflare / Google by IP, so broken or filtering DNS can't stop a download."""

    DOH = ("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve", "https://1.0.0.1/dns-query")

    def __init__(self, engine):
        self.engine = engine
        self.sys = aiohttp.ThreadedResolver()
        self.cache = {}
        self._s = None

    def _result(self, host, port, ips):
        return [{"hostname": host, "host": ip, "port": port,
                 "family": socket.AF_INET6 if ":" in ip else socket.AF_INET,
                 "proto": 0, "flags": socket.AI_NUMERICHOST} for ip in ips]

    async def resolve(self, host, port=0, family=socket.AF_INET):
        mode = self.engine.cfg.get("dns_mode", "auto")
        if mode == "doh" and not _is_ip(host):
            ips = await self.doh(host, family)
            if ips:
                return self._result(host, port, ips)
        try:
            return await self.sys.resolve(host, port, family)
        except OSError:
            if mode == "system" or _is_ip(host):
                raise
            ips = await self.doh(host, family)
            if not ips:
                raise
            log.info("system DNS failed for %s - using DNS-over-HTTPS %s", host, ips)
            return self._result(host, port, ips)

    async def doh(self, host, family=socket.AF_INET):
        hit = self.cache.get(host)
        if hit and hit[1] > time.time():
            return hit[0]
        if self._s is None:
            self._s = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=6))
        types = ("AAAA", "A") if family == socket.AF_INET6 else ("A", "AAAA")
        for base in self.DOH:
            for t in types:
                try:
                    async with self._s.get(base, params={"name": host, "type": t},
                                           headers={"accept": "application/dns-json"}) as r:
                        j = await r.json(content_type=None)
                    ips = [a["data"] for a in j.get("Answer", []) if a.get("type") in (1, 28)]
                    if ips:
                        ttl = min([a.get("TTL", 300) for a in j.get("Answer", [])] + [600])
                        self.cache[host] = (ips, time.time() + max(30, ttl))
                        return ips
                except Exception:
                    continue
        return []

    async def close(self):
        await self.sys.close()
        if self._s is not None:
            await self._s.close()


def _is_ip(host):
    try:
        socket.inet_pton(socket.AF_INET6 if ":" in host else socket.AF_INET, host.strip("[]"))
        return True
    except OSError:
        return False


# --------------------------------------------------------------------------- FTP
def ftp_connect(url, timeout=30):
    p = urlparse(url)
    ftp = ftplib.FTP_TLS() if p.scheme == "ftps" else ftplib.FTP()
    ftp.connect(p.hostname, p.port or 21, timeout=timeout)
    ftp.login(unquote(p.username or "anonymous"), unquote(p.password or "anonymous@"))
    if isinstance(ftp, ftplib.FTP_TLS):
        ftp.prot_p()
    ftp.voidcmd("TYPE I")
    return ftp, unquote(p.path or "/")


def ftp_close(ftp):
    try:
        ftp.close()
    except Exception:
        pass


def ftp_info(url):
    ftp, path = ftp_connect(url, 15)
    try:
        size = None
        try:
            size = ftp.size(path)
        except ftplib.all_errors:
            pass
        rest = False
        try:
            ftp.sendcmd("REST 0")
            rest = True
        except ftplib.all_errors:
            pass
        return size, rest
    finally:
        ftp_close(ftp)


# --------------------------------------------------------------------------- model
class Segment:
    __slots__ = ("start", "end", "pos", "flushed", "active")

    def __init__(self, start, end, flushed=None):
        self.start = start
        self.end = end              # inclusive, None = unknown (stream until EOF)
        self.flushed = start if flushed is None else flushed
        self.pos = self.flushed
        self.active = False

    @property
    def remaining(self):
        return math.inf if self.end is None else self.end - self.pos + 1

    @property
    def complete(self):
        return self.end is not None and self.flushed > self.end

    def dump(self):
        return [self.start, self.end, self.flushed]


class Item:
    PERSIST = ["id", "url", "final_url", "filename", "dir", "size", "done", "state", "error",
               "ranges", "kind", "mime", "category", "created", "finished", "referrer",
               "user_agent", "headers", "cookies", "max_conns", "source", "page_url", "elapsed",
               "user_named", "user_dir", "priority", "gid"]

    def __init__(self, **kw):
        self.id = kw.get("id") or uuid.uuid4().hex[:12]
        self.url = kw.get("url", "")
        self.final_url = kw.get("final_url")
        self.filename = kw.get("filename") or ""
        self.dir = kw.get("dir") or ""
        self.size = kw.get("size")
        self.done = kw.get("done", 0)
        self.state = kw.get("state", "queued")   # pending|queued|downloading|paused|done|error
        self.error = kw.get("error")
        self.ranges = kw.get("ranges", False)
        self.kind = kw.get("kind", "http")       # http | media
        self.mime = kw.get("mime", "")
        self.category = kw.get("category", "Other")
        self.created = kw.get("created") or time.time()
        self.finished = kw.get("finished")
        self.referrer = kw.get("referrer", "")
        self.user_agent = kw.get("user_agent", "")
        self.headers = kw.get("headers") or {}
        self.cookies = kw.get("cookies") or []
        self.max_conns = int(kw.get("max_conns") or 16)
        self.source = kw.get("source", "ui")
        self.page_url = kw.get("page_url", "")
        self.elapsed = kw.get("elapsed", 0.0)
        self.user_named = kw.get("user_named", False)
        self.user_dir = kw.get("user_dir", False)
        self.priority = int(kw.get("priority") or 0)      # >0 = "download first"
        self.gid = kw.get("gid")                          # torrent: aria2 id
        self.segments = [Segment(*s) for s in kw.get("segments", [])]
        # runtime
        self.speed = 0.0
        self.conns = 0
        self.task = None
        self.session = None
        self.lane_sessions = {}
        self.stop = False
        self.started_at = None
        self.last_done = self.done
        self.probed = False
        self.note = ""

    @property
    def path(self):
        return os.path.join(self.dir, self.filename)

    @property
    def part_path(self):
        return self.path + ".sdmpart"

    @property
    def legacy_part_path(self):
        return self.path + ".fdlpart"          # written by FastDL (the old name of SDM)

    def dump(self):
        d = {k: getattr(self, k) for k in self.PERSIST}
        d["segments"] = [s.dump() for s in self.segments]
        return d

    def view(self, with_segments=True):
        eta = None
        if self.state == "downloading" and self.speed > 1 and self.size:
            eta = max(0, (self.size - self.done) / self.speed)
        v = {
            "id": self.id, "url": self.url, "filename": self.filename, "dir": self.dir,
            "size": self.size, "done": self.done, "state": self.state, "error": self.error,
            "ranges": self.ranges, "kind": self.kind, "mime": self.mime, "category": self.category,
            "created": self.created, "finished": self.finished, "speed": round(self.speed),
            "conns": self.conns, "max_conns": self.max_conns, "eta": eta, "source": self.source,
            "elapsed": round(self.elapsed + ((time.time() - self.started_at) if self.started_at else 0), 1),
            "note": self.note, "referrer": self.referrer, "probed": self.probed,
            "priority": self.priority, "path": self.path if self.filename else "",
            "seeding": bool(self.kind == "torrent" and self.state == "done" and self.gid),
        }
        if with_segments and self.size and self.segments and self.state in ("downloading", "paused", "error", "queued"):
            S = float(self.size)
            v["segs"] = [[round(s.start / S, 5), round(min(s.pos, (s.end if s.end is not None else s.pos - 1) + 1) / S, 5),
                          round(((s.end if s.end is not None else self.size - 1) + 1) / S, 5), 1 if s.active else 0]
                         for s in sorted(self.segments, key=lambda s: s.start)]
        return v


# --------------------------------------------------------------------------- limiter
class Limiter:
    """Speed limit shared by every connection (and network lane). Each chunk reserves its slot on
    a virtual clock, so 1 or 256 connections together stay at the limit; 0.25 s of burst credit."""

    BURST = 0.25

    def __init__(self):
        self.rate = 0
        self.t = time.monotonic()
        self.lock = threading.Lock()

    def set(self, kb):
        with self.lock:
            self.rate = max(0, int(kb)) * 1024
            self.t = time.monotonic()

    async def take(self, n):
        rate = self.rate
        if rate <= 0:
            return
        with self.lock:
            now = time.monotonic()
            if self.t < now - self.BURST:
                self.t = now - self.BURST
            self.t += n / rate
            wait = self.t - now
        if wait > 0:
            await asyncio.sleep(wait)


# --------------------------------------------------------------------------- writer
class SegWriter:
    """Collects one connection's chunks (no copying) and writes them at the right offset in a worker
    thread while the connection keeps receiving: one write in flight, the next batch filling up."""

    def __init__(self, fh, seg, pool):
        self.fh = fh
        self.seg = seg
        self.pool = pool
        self.chunks = []
        self.nbuf = 0
        self.fut = None
        self.pending = None

    def add(self, chunk):
        self.chunks.append(chunk)
        self.nbuf += len(chunk)

    def _write(self, off, chunks):
        self.fh.seek(off)
        for c in chunks:
            mv = memoryview(c)
            while mv:
                n = self.fh.write(mv)
                if n is None or n <= 0:
                    raise OSError("short write")
                mv = mv[n:]

    @classmethod
    async def create(cls, path, seg, pool):
        fh = await asyncio.get_running_loop().run_in_executor(pool, lambda: open(path, "r+b", buffering=0))
        return cls(fh, seg, pool)

    async def _drain(self):
        """Wait for the write in flight; only then count its bytes as safely on disk."""
        if self.fut is None:
            return
        try:
            await asyncio.shield(self.fut)
        except OSError as e:
            self.fut = None
            self.pending = None
            raise DiskError(e)
        self.seg.flushed = self.pending[0] + self.pending[1]
        self.fut = None
        self.pending = None

    async def flush(self, wait=True):
        await self._drain()
        if self.chunks:
            chunks, n = self.chunks, self.nbuf
            self.chunks, self.nbuf = [], 0
            off = self.seg.flushed
            self.pending = (off, n)
            self.fut = asyncio.get_running_loop().run_in_executor(self.pool, self._write, off, chunks)
        if wait:
            await self._drain()

    async def close(self):
        try:
            if self.fut is not None:
                await asyncio.wait({self.fut})
                if not self.fut.cancelled() and self.fut.exception() is None and self.pending:
                    self.seg.flushed = self.pending[0] + self.pending[1]
                self.fut = None
                self.pending = None
            if self.chunks:
                chunks, off = self.chunks, self.seg.flushed
                n = self.nbuf
                self.chunks, self.nbuf = [], 0
                try:
                    await asyncio.get_running_loop().run_in_executor(self.pool, self._write, off, chunks)
                    self.seg.flushed = off + n
                except (OSError, ValueError):
                    pass
        finally:
            try:
                await asyncio.get_running_loop().run_in_executor(self.pool, self.fh.close)
            except Exception:
                pass


# --------------------------------------------------------------------------- network lanes
def new_connector(resolver):
    kw = dict(limit=0, limit_per_host=0, ttl_dns_cache=300, enable_cleanup_closed=True, resolver=resolver)
    try:
        return aiohttp.TCPConnector(happy_eyeballs_delay=0.25, **kw)
    except TypeError:
        return aiohttp.TCPConnector(**kw)


def lane_count():
    """Extra network threads. TLS decryption and socket reads release Python's GIL, so connections
    spread over several lanes use several CPU cores (needed for ~1 GB/s over HTTPS)."""
    try:
        forced = int(os.environ.get("SDM_LANES", "0"))
    except ValueError:
        forced = 0
    if forced > 0:
        return min(forced, 16)
    cpus = os.cpu_count() or 2
    return 1 if cpus <= 2 else 2 if cpus <= 5 else 3


class Lane:
    """One network thread with its own event loop, connection pool and DNS resolver."""

    def __init__(self, engine, idx):
        self.engine = engine
        self.idx = idx
        self.busy = 0                      # connections running here (touched on the main loop only)
        self.loop = None
        self.connector = None
        self.resolver = None
        self.error = None
        self._ready = threading.Event()
        threading.Thread(target=self._main, daemon=True, name=f"sdm-net{idx}").start()
        if not self._ready.wait(15) or self.error:
            raise RuntimeError(f"network lane {idx} did not start: {self.error}")

    def _main(self):
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            self.loop = loop

            async def setup():
                self.resolver = FallbackResolver(self.engine)
                self.connector = new_connector(self.resolver)
            loop.run_until_complete(setup())
        except Exception as e:
            self.error = e
            self._ready.set()
            return
        self._ready.set()
        try:
            loop.run_forever()
        finally:
            try:
                loop.close()
            except Exception:
                pass

    def submit(self, coro_fn):
        """Run coro_fn() on this lane; returns a future for the calling (main) loop."""
        async def run():
            return await coro_fn()
        return asyncio.wrap_future(asyncio.run_coroutine_threadsafe(run(), self.loop))

    async def shutdown(self):
        async def close():
            try:
                await self.connector.close()
            finally:
                await self.resolver.close()
        try:
            await asyncio.wait_for(self.submit(close), 10)
        except Exception:
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)


class LaneJob:
    """A download connection running on a lane. `.fut` (on the main loop) resolves only after the
    connection has completely finished - including flushing and closing its file - so pause,
    remove and resume never race a connection that is still shutting down."""

    def __init__(self, lane, coro_fn, main_loop):
        self.lane = lane
        self.main = main_loop
        self.fut = main_loop.create_future()
        self.task = None
        self.cancelled = False
        lane.busy += 1
        lane.loop.call_soon_threadsafe(self._start, coro_fn)

    def _start(self, coro_fn):                       # lane thread
        if self.cancelled:
            self._deliver("cancelled", None)
            return
        try:
            self.task = self.lane.loop.create_task(coro_fn())
        except Exception as e:
            self._deliver(None, e)
            return
        self.task.add_done_callback(self._done)

    def _done(self, t):                              # lane thread
        if t.cancelled():
            self._deliver("cancelled", None)
        else:
            exc = t.exception()
            self._deliver(None if exc is not None else t.result(), exc)

    def _deliver(self, res, exc):
        def apply():                                 # main loop
            self.lane.busy -= 1
            if self.fut.done():
                return
            if exc is not None:
                self.fut.set_exception(exc)
            else:
                self.fut.set_result(res)
        try:
            self.main.call_soon_threadsafe(apply)
        except RuntimeError:                         # main loop already closed (exiting)
            pass

    def cancel(self):                                # main loop
        self.cancelled = True

        def c():
            if self.task is not None:
                self.task.cancel()
        try:
            self.lane.loop.call_soon_threadsafe(c)
        except RuntimeError:
            pass


# --------------------------------------------------------------------------- engine
class Engine:
    def __init__(self, port):
        self.port = port
        self.items = {}
        self.order = []
        self.cfg = dict(DEFAULT_CONFIG)
        self.limiter = Limiter()
        self.pool = ThreadPoolExecutor(max_workers=16, thread_name_prefix="sdm-io")
        self.fpool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="sdm-fs")
        self.heartbeat = time.monotonic()
        self.aria = sdm_torrent.Aria2(APP_DIR, ROOT, lambda: self.cfg)
        self.loop = None
        self.connector = None
        self.ws_clients = set()
        self.changed = set()
        self.removed = set()
        self.dirty = False
        self.rx = 0
        self.nlock = threading.Lock()   # byte counters + segment boundaries, shared with network lanes
        self.lanes = []
        self.speed = 0.0
        self.hist = []
        self.stats = {"day": time.strftime("%Y-%m-%d"), "today": 0, "total": 0}
        self.last_ext_ping = 0
        self.reserved = set()
        self.window_opened_at = 0
        self.app = None

    # ----- persistence
    def load(self):
        if CFG_FILE.exists():
            try:
                self.cfg.update(json.loads(CFG_FILE.read_text(encoding="utf-8")))
            except Exception as e:
                log.warning("config unreadable: %s", e)
        if DB_FILE.exists():
            try:
                data = json.loads(DB_FILE.read_text(encoding="utf-8"))
                self.stats.update(data.get("stats", {}))
                for d in data.get("items", []):
                    it = Item(**d)
                    if it.state == "downloading":
                        it.state = "queued"
                    self.items[it.id] = it
                    self.order.append(it.id)
            except Exception as e:
                log.error("database unreadable, starting fresh: %s", e)
                shutil.copy(DB_FILE, str(DB_FILE) + ".broken")
        self.limiter.set(self.cfg.get("speed_limit_kb", 0))

    def _watchdog(self):
        """If the engine ever stops responding, write every thread's stack to the log once."""
        import faulthandler
        dumped = False
        while True:
            time.sleep(5)
            lag = time.monotonic() - self.heartbeat
            if lag > 15 and not dumped:
                log.error("engine loop stalled for %.0fs - thread stacks follow", lag)
                try:
                    with open(LOG_FILE, "a", encoding="utf-8") as f:
                        faulthandler.dump_traceback(file=f, all_threads=True)
                except Exception:
                    pass
                dumped = True
            elif lag < 2:
                dumped = False

    def _background_checks(self):
        """Runs once per start, off the event loop: optional deps + browser-helper registration."""
        global HAVE_YTDLP
        if not HAVE_YTDLP:
            log.info("yt-dlp missing - installing in background")
            if _pip_install("yt-dlp") and importlib.util.find_spec("yt_dlp") is not None:
                HAVE_YTDLP = True
                log.info("yt-dlp installed")
        try:
            remember_python()
            import install_helper
            if install_helper.needs_registration():
                install_helper.register_native()
                log.info("browser helper (re)registered for %s", ROOT)
            install_helper.migrate_from_fastdl()
            fixed = install_helper.repair_shortcuts()
            if fixed:
                log.info("shortcuts now point to %s: %s", ROOT, ", ".join(fixed))
            self.apply_system_settings(("start_with_windows", "handle_magnets"))
        except Exception as e:
            log.warning("self-check: %s", e)
        self._background_tray()

    def _background_tray(self):
        if sys.platform.startswith("win") and self.cfg.get("tray", True):
            if importlib.util.find_spec("pystray") is None or importlib.util.find_spec("PIL") is None:
                log.info("installing tray icon support (pystray, pillow)")
                _pip_install("pystray", "pillow")
            self.loop.call_soon_threadsafe(self.start_tray)

    def start_tray(self):
        if getattr(self, "tray", None) is not None or not self.cfg.get("tray", True):
            return
        try:
            import sdm_tray
            t = sdm_tray.Tray(self, str(UI_DIR / "icon.png"), lambda: open_window(self.port), self.quit)
            if t.start():
                self.tray = t
                log.info("tray icon running")
        except Exception as e:
            log.warning("tray: %s", e)

    async def quit(self):
        """Quit from the tray / window: save everything, stop aria2, exit."""
        log.info("quit requested")
        try:
            await self.stop_all()
        finally:
            asyncio.get_running_loop().call_later(0.3, os._exit, 0)

    def apply_system_settings(self, keys):
        """Windows integration that follows Settings (runs in a worker thread)."""
        import install_helper
        try:
            if "start_with_windows" in keys:          # creates / removes the Startup shortcut
                install_helper.set_startup(bool(self.cfg.get("start_with_windows")))
            if "handle_magnets" in keys:              # only ever removes associations that point to SDM
                install_helper.set_magnet_handler(bool(self.cfg.get("handle_magnets")))
        except Exception as e:
            log.warning("system settings: %s", e)

    def save(self):
        try:
            write_atomic(DB_FILE, json.dumps({"items": [self.items[i].dump() for i in self.order if i in self.items],
                                              "stats": self.stats}, ensure_ascii=False))
            write_atomic(CFG_FILE, json.dumps(self.cfg, indent=1, ensure_ascii=False))
            self.dirty = False
        except Exception as e:
            log.error("save failed: %s", e)

    def touch(self, it):
        self.changed.add(it.id)
        self.dirty = True

    # ----- lifecycle
    async def start(self):
        self.loop = asyncio.get_running_loop()
        self.awake = False
        if sdm_torrent.win_no_throttle():
            log.info("background throttling disabled (full speed in the tray)")
        self.resolver = FallbackResolver(self)
        self.connector = new_connector(self.resolver)
        try:
            self.lanes = [Lane(self, i) for i in range(lane_count())]
            log.info("network lanes: %d (+ main)", len(self.lanes))
        except Exception as e:
            log.warning("network lanes unavailable, using the main loop only: %s", e)
            self.lanes = []
        self.load()
        asyncio.create_task(self._ticker())
        asyncio.get_running_loop().run_in_executor(None, self._background_checks)
        threading.Thread(target=self._watchdog, daemon=True, name="sdm-watchdog").start()
        self.kick()

    async def stop_all(self):
        self.closing = True          # nothing may (re)start while we shut down
        sdm_torrent.win_keep_awake(False)
        tasks = []
        for it in self.items.values():
            if it.task and not it.task.done():
                it.state = "queued"
                it.task.cancel()
                tasks.append(it.task)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self.save()
        try:
            await self.aria.stop()
        except Exception:
            pass
        for lane in self.lanes:
            await lane.shutdown()
        self.lanes = []
        if self.connector:
            await self.connector.close()
            try:
                await self.resolver.close()
            except Exception:
                pass

    # ----- sessions
    def make_session(self, it, connector=None):
        headers = {"User-Agent": it.user_agent or DEFAULT_UA, "Accept": "*/*",
                   "Accept-Encoding": "identity", "Accept-Language": "en-US,en;q=0.9"}
        if it.referrer:
            headers["Referer"] = it.referrer
        for k, v in (it.headers or {}).items():
            if k.lower() not in ("range", "host", "content-length", "cookie", "accept-encoding", "connection"):
                headers[k] = v
        kw = dict(connector=connector or self.connector, connector_owner=False, cookie_jar=build_jar(it.cookies),
                  headers=headers, timeout=TIMEOUT, auto_decompress=False, trust_env=True,
                  # per-connection receive buffer: 1 MB, smaller with very many connections (RAM stays bounded)
                  read_bufsize=max(256 * 1024, min(1 * MB, (64 * MB) // max(1, it.max_conns or 1))))
        try:
            return aiohttp.ClientSession(max_line_size=65536, max_field_size=65536, **kw)
        except TypeError:
            return aiohttp.ClientSession(**kw)

    def rkw(self):
        """Per-request options: certificate checking and manual proxy."""
        kw = {"ssl": False if self.cfg.get("allow_insecure") else True}
        px = (self.cfg.get("proxy") or "").strip()
        if px:
            kw["proxy"] = px if "://" in px else "http://" + px
        return kw

    # ----- names / folders
    def target_dir(self, it):
        if it.user_dir and it.dir:
            return it.dir
        base = self.cfg.get("download_dir") or default_download_dir()
        if self.cfg.get("categorize") and it.category and it.category != "Other":
            return os.path.join(base, it.category)
        return base

    def unique_name(self, folder, name, it):
        stem, dot, ext = name.rpartition(".")
        if not dot:
            stem, ext = name, ""
        cand = name
        i = 1
        taken = {(o.dir, o.filename) for o in self.items.values()
                 if o is not it and o.state not in ("done", "error")}
        taken |= self.reserved          # removed downloads whose files are still being cleaned up
        while (os.path.exists(os.path.join(folder, cand)) or os.path.exists(os.path.join(folder, cand + ".sdmpart"))
               or os.path.exists(os.path.join(folder, cand + ".fdlpart"))
               or (folder, cand) in taken):
            cand = f"{stem} ({i}).{ext}" if ext else f"{stem} ({i})"
            i += 1
        return cand

    # ----- public actions
    def add(self, data):
        url = (data.get("url") or "").strip()
        local_torrent = (url.lower().endswith(".torrent") and not re.match(r"^[a-z]+://", url, re.I)
                         and os.path.isfile(url))
        if not (re.match(r"^(https?|ftps?)://", url, re.I) or sdm_torrent.is_magnet(url) or local_torrent):
            raise UserFacingError("Unsupported link - use http(s)://, ftp://, magnet: or a .torrent file")
        for o in self.items.values():  # ignore duplicate catches of a running download
            if o.url == url and o.state in ("pending", "queued", "downloading"):
                o.duplicate = True
                return o
        kind = data.get("kind") or "http"
        u = url.lower().split("?")[0]
        if u.endswith((".m3u8", ".mpd")):
            kind = "media"
        if u.startswith(("ftp://", "ftps://")):
            kind = "ftp"
        if sdm_torrent.is_magnet(url) or local_torrent or sdm_torrent.looks_like_torrent_url(url, data.get("mime", "")) \
                or (data.get("filename") or "").lower().endswith(".torrent"):
            kind = "torrent"
        it = Item(url=url, referrer=data.get("referrer") or "", user_agent=data.get("user_agent") or "",
                  headers=data.get("headers") or {}, cookies=data.get("cookies") or [], kind=kind,
                  max_conns=min(MAX_CONNS, max(1, int(data.get("connections") or self.cfg["connections"]))),
                  source=data.get("source") or "ui", page_url=data.get("page_url") or "")
        if data.get("final_url") and re.match(r"^https?://", data["final_url"], re.I):
            it.final_url = data["final_url"]
        if data.get("filename") and kind != "torrent":
            it.filename = sanitize(data["filename"])
            it.user_named = bool(data.get("user_named"))
            it.category = category_of(it.filename)
        if kind == "torrent":
            it.category = "Torrents"
            it.ranges = True
            if sdm_torrent.is_magnet(url):
                try:
                    it.filename = sanitize(sdm_torrent.magnet_meta(url)[0])
                except sdm_torrent.TorrentError as e:
                    raise UserFacingError(str(e))
            elif local_torrent:
                try:                                   # name + size straight from the file
                    with open(url, "rb") as fh:
                        tname, tsize, _n, _ih = sdm_torrent.torrent_meta(fh.read(TORRENT_MAX + 1))
                    it.filename = sanitize(tname)
                    it.size = tsize or None
                except Exception:
                    it.filename = sanitize(os.path.basename(url)[:-len(".torrent")])
        if data.get("size") and kind != "torrent":
            try:
                it.size = int(data["size"]) or None
            except (TypeError, ValueError):
                pass
        if data.get("mime"):
            it.mime = data["mime"]
        if data.get("dir"):
            it.dir = data["dir"]
            it.user_dir = True
        ask = data.get("ask")
        if ask is None:
            ask = self.cfg.get("ask_location", True)
        it.state = "pending" if ask else "queued"
        self.items[it.id] = it
        self.order.insert(0, it.id)
        self.touch(it)
        if it.state == "pending":
            asyncio.create_task(self._preprobe(it))
            if it.source != "ui" and not self.ws_clients and self.cfg.get("open_window_on_catch", True):
                if time.time() - self.window_opened_at > 8:
                    self.window_opened_at = time.time()
                    open_window(self.port)
        else:
            self.kick()
        return it

    def _assign_name_fallback(self, it):
        if not it.filename:
            it.filename = sanitize(name_from_url(it.url) or "download")
            it.category = category_of(it.filename)
        if not it.dir:
            it.dir = self.target_dir(it)

    def confirm(self, it, data):
        """User answered the 'where to save' dialog."""
        if it.state != "pending":
            return
        if data.get("filename") and it.kind != "torrent":
            it.filename = sanitize(data["filename"])
            it.user_named = True
            it.category = category_of(it.filename)
        if data.get("dir"):
            it.dir = os.path.expanduser(data["dir"].strip())
            it.user_dir = True
            rec = [d for d in self.cfg.get("recent_dirs", []) if d != it.dir]
            self.cfg["recent_dirs"] = ([it.dir] + rec)[:6]
        if data.get("connections"):
            it.max_conns = min(MAX_CONNS, max(1, int(data["connections"])))
        if data.get("kind") in ("http", "media") and it.kind != "torrent":
            it.kind = data["kind"]
        self._assign_name_fallback(it)
        try:
            os.makedirs(it.dir, exist_ok=True)
        except OSError as e:
            raise UserFacingError(f"Cannot use that folder: {e}")
        if it.kind != "torrent" and (not it.user_named or os.path.exists(it.path)):
            it.filename = self.unique_name(it.dir, it.filename, it)
        it.state = "paused" if data.get("later") else "queued"
        if data.get("remember_dir") and it.dir:
            self.cfg["download_dir"] = it.dir
            self.cfg["categorize"] = False
        if data.get("dont_ask"):
            self.cfg["ask_location"] = False
        self.touch(it)
        self.kick()

    def pause(self, it):
        if it.state in ("downloading", "queued"):
            it.state = "paused"
            if it.task and not it.task.done():
                it.task.cancel()
            self.touch(it)
            self.kick()

    def requeue(self, it):
        """Stop a running download but put it straight back in the queue."""
        if it.state == "downloading":
            it.state = "queued"
            if it.task and not it.task.done():
                it.task.cancel()
            self.touch(it)

    def download_first(self, it):
        others = [o.priority for o in self.items.values() if o is not it]
        it.priority = max(others + [0]) + 1
        if it.state in ("paused", "error"):
            it.state = "queued"
            it.error = None
        self.touch(it)
        if it.state == "queued":
            active = [o for o in self.items.values() if o.state == "downloading"]
            if len(active) >= int(self.cfg.get("max_active", 4)):
                victim = sorted(active, key=lambda o: (o.priority, -(o.started_at or 0)))[0]
                if victim.priority < it.priority:
                    self.requeue(victim)
        self.kick()

    async def stop_seeding(self, it):
        """Stop sharing a finished torrent; it stays in the list and its files stay on disk."""
        if it.kind == "torrent" and it.state == "done" and it.gid:
            await self._torrent_forget(it, delete_data=False, keep_torrent_file=True)
            it.note = ""
            self.touch(it)
            self.save()

    def pause_all(self):
        for it in list(self.items.values()):
            self.pause(it)

    def resume_all(self):
        for it in list(self.items.values()):
            if it.state in ("paused", "error"):
                self.resume(it)

    def resume(self, it):
        if it.state in ("paused", "error"):
            it.state = "queued"
            it.error = None
            self.touch(it)
            self.kick()

    def restart(self, it):
        self.pause(it)

        async def _r():
            if it.task:
                await asyncio.gather(it.task, return_exceptions=True)
            if it.kind == "torrent":
                await self._torrent_forget(it, delete_data=True)
            else:
                await self.io(self._delete_part, it)
            it.segments = []
            it.done = 0
            it.size = None if it.kind == "http" else it.size
            it.elapsed = 0
            it.state = "queued"
            it.error = None
            self.touch(it)
            self.kick()
        asyncio.create_task(_r())

    def refresh(self, it, new_url):
        if it.kind == "torrent":
            raise UserFacingError("Torrents don't use links that expire - use Restart instead")
        if not re.match(r"^(https?|ftps?)://", new_url or "", re.I):
            raise UserFacingError("Paste a valid http(s) or ftp link")
        self.pause(it)
        it.url = new_url.strip()
        it.final_url = None
        it.error = None
        it.state = "queued"
        self.touch(it)
        self.kick()

    def _delete_part(self, it):
        for p in (it.part_path, it.legacy_part_path):
            try:
                if it.filename and os.path.exists(p):
                    os.remove(p)
            except OSError:
                pass
        if it.kind == "media" and it.filename:
            for p in Path(it.dir or ".").glob(glob_escape(Path(it.filename).stem) + "*.part"):
                try:
                    p.unlink()
                except OSError:
                    pass

    def remove(self, it, delete_file=False, keep_data=False):
        """Take a download off the list. Torrents stop seeding. Finished files are only deleted with
        delete_file; keep_data (the Clear buttons) also keeps a failed torrent's partial files."""
        it.state = "removed"
        if it.task and not it.task.done():
            it.task.cancel()
        key = (it.dir, it.filename)
        if it.filename and it.kind != "torrent":
            self.reserved.add(key)     # a new download must not reuse this name until clean-up is done

        async def _r():
            try:
                await _clean()
            finally:
                self.reserved.discard(key)

        async def _clean():
            if it.task:
                await asyncio.gather(it.task, return_exceptions=True)
            if it.kind == "torrent":
                incomplete = not (it.size and it.done >= it.size)
                await self._torrent_forget(it, delete_data=delete_file or (incomplete and not keep_data),
                                           keep_torrent_file=False)
                return

            def _rm():
                self._delete_part(it)
                if delete_file and it.filename and os.path.isfile(it.path):
                    try:
                        os.remove(it.path)
                    except OSError:
                        pass
            await self.io(_rm)
        asyncio.create_task(_r())
        self.items.pop(it.id, None)
        if it.id in self.order:
            self.order.remove(it.id)
        self.removed.add(it.id)
        self.dirty = True
        self.kick()

    def kick(self):
        if getattr(self, "closing", False):
            return
        active = sum(1 for i in self.items.values() if i.state == "downloading")
        slots = int(self.cfg.get("max_active", 4)) - active
        if slots <= 0:
            return
        queued = [self.items[i] for i in self.order if i in self.items and self.items[i].state == "queued"]
        queued.sort(key=lambda o: (-o.priority, o.created))     # "download first", then oldest first
        for it in queued:
            if it.task and not it.task.done():
                continue
            it.state = "downloading"
            it.error = None
            it.task = asyncio.create_task(self._run(it))
            self.touch(it)
            slots -= 1
            if slots <= 0:
                break

    # ----- probing
    async def _preprobe(self, it):
        """Fill name/size/type for the 'where to save' dialog without downloading."""
        if it.kind == "torrent":
            try:
                await self._torrent_info(it)
            except Exception as e:
                it.note = human_error(e)
            if not it.user_dir:
                it.dir = self.target_dir(it)
            it.probed = True
            self.touch(it)
            return
        if it.kind == "media":
            it.probed = True
            if not it.filename:
                it.filename = sanitize((name_from_url(it.url) or "media").rsplit(".", 1)[0] + ".mp4")
                it.category = "Video"
            it.dir = it.dir or self.target_dir(it)
            self.touch(it)
            return
        if it.kind == "ftp":
            try:
                it.size, it.ranges = await asyncio.get_running_loop().run_in_executor(None, ftp_info, it.url)
            except Exception as e:
                it.note = "Could not reach FTP server yet: " + human_error(e)
            if not it.filename:
                it.filename = sanitize(name_from_url(it.url) or "download")
            it.category = category_of(it.filename)
            if not it.user_dir:
                it.dir = self.target_dir(it)
            it.probed = True
            self.touch(it)
            return
        sess = self.make_session(it)
        try:
            async with sess.get(it.final_url or it.url, headers={"Range": "bytes=0-0"}, allow_redirects=True,
                                timeout=aiohttp.ClientTimeout(total=15), **self.rkw()) as r:
                ctype = r.headers.get("Content-Type", "").split(";")[0].strip().lower()
                it.mime = ctype or it.mime
                if r.status == 206:
                    cr = parse_content_range(r.headers.get("Content-Range"))
                    if cr and cr[2]:
                        it.size = cr[2]
                    it.ranges = True
                elif r.status == 200:
                    cl = r.headers.get("Content-Length")
                    if cl and cl.isdigit():
                        it.size = int(cl)
                elif r.status >= 400:
                    it.note = f"Server answered HTTP {r.status}"
                if not it.user_named:
                    name = cd_filename(r.headers.get("Content-Disposition")) or it.filename \
                        or name_from_url(str(r.url)) or name_from_url(it.url)
                    name = sanitize(name or "download")
                    if "." not in name and ctype and ctype not in HTML_TYPES:
                        name += guess_ext(ctype)
                    it.filename = name
                if ctype in HTML_TYPES and it.source in MANUAL_SOURCES and "attachment" not in r.headers.get("Content-Disposition", "").lower():
                    it.kind = "media"
                    it.note = "Web page - will grab its video/audio" if HAVE_YTDLP else "This link is a web page, not a file"
                    if not ext_of(it.filename) or ext_of(it.filename) in ("html", "htm", "php", "aspx"):
                        it.filename = sanitize((it.filename.rsplit(".", 1)[0] if "." in it.filename else it.filename) + ".mp4")
                if ctype in STREAM_TYPES:
                    it.kind = "media"
                it.category = category_of(it.filename)
        except Exception as e:
            it.note = "Could not reach server yet: " + human_error(e)
        finally:
            await sess.close()
        if not it.filename:
            it.filename = sanitize(name_from_url(it.url) or "download")
            it.category = category_of(it.filename)
        if not it.user_dir:
            it.dir = self.target_dir(it)
        it.probed = True
        self.touch(it)

    async def _open_probe(self, it):
        url = it.final_url or it.url
        resp = await it.session.get(url, headers={"Range": "bytes=0-"}, allow_redirects=True, max_redirects=30,
                                    **self.rkw())
        if resp.status >= 400 and url != it.url:
            resp.close()   # resolved link expired - go through the original link again
            resp = await it.session.get(it.url, headers={"Range": "bytes=0-"}, allow_redirects=True,
                                        max_redirects=30, **self.rkw())
        if resp.status >= 400:
            resp.close()
            raise HTTPStatusError(resp.status, resp.reason or "")
        it.final_url = str(resp.url)
        ctype = resp.headers.get("Content-Type", "").split(";")[0].strip().lower()
        cdisp = resp.headers.get("Content-Disposition", "")
        it.mime = ctype
        total, ranges = None, False
        if resp.status == 206:
            cr = parse_content_range(resp.headers.get("Content-Range"))
            if cr and cr[0] == 0 and cr[2]:
                total, ranges = cr[2], True
        else:
            cl = resp.headers.get("Content-Length")
            enc = resp.headers.get("Content-Encoding", "identity").lower()
            if cl and cl.isdigit() and enc in ("", "identity"):
                total = int(cl)
            ranges = bool(total) and "bytes" in resp.headers.get("Accept-Ranges", "").lower()
        if ctype in STREAM_TYPES:
            resp.close()
            raise SwitchToMedia()
        if "bittorrent" in ctype or (ext_of(it.filename) == "torrent" and not it.user_named):
            resp.close()
            raise SwitchToTorrent()
        if ctype in HTML_TYPES and "attachment" not in cdisp.lower():
            if it.source in MANUAL_SOURCES and HAVE_YTDLP:
                resp.close()
                raise SwitchToMedia()
            if it.source not in MANUAL_SOURCES and ext_of(it.filename) not in ("html", "htm"):
                resp.close()
                raise UserFacingError("Server sent a web page instead of the file (login or expired link?). "
                                      "Use OPEN IN BROWSER")
        if not it.user_named and (not it.filename or not it.dir):
            # server's Content-Disposition beats the browser's guess (browsers fall back to "download")
            name = cd_filename(cdisp) or it.filename or name_from_url(it.final_url) or name_from_url(it.url) or "download"
            name = sanitize(name)
            if "." not in name:
                name += guess_ext(ctype)
            it.filename = name
            it.category = category_of(name)
        return resp, total, ranges

    # ----- the download
    async def _run(self, it):
        it.started_at = time.time()
        it.stop = False
        try:
            if it.kind == "media":
                await self._run_media(it)
            elif it.kind == "ftp":
                await self._run_ftp(it)
            elif it.kind == "torrent":
                await self._run_torrent(it)
            else:
                try:
                    await self._run_http(it)
                except SwitchToTorrent:
                    await self._close_session(it)
                    it.kind = "torrent"
                    it.category = "Torrents"
                    it.filename = ""
                    await self._run_torrent(it)
                except SwitchToMedia:
                    if not HAVE_YTDLP:
                        raise UserFacingError("This link is a web page / stream. Install yt-dlp "
                                              "(pip install yt-dlp) to grab its media")
                    it.kind = "media"
                    if it.filename and not it.user_named:
                        it.filename = sanitize(it.filename.rsplit(".", 1)[0] + ".mp4")
                    await self._close_session(it)
                    await self._run_media(it)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            if getattr(self, "closing", False):
                it.state = "queued"              # interrupted by quitting: continue next start
            elif it.state == "downloading":
                it.state = "error"
                it.error = human_error(e)
                log.warning("download %s failed: %r", it.filename or it.url, e)
        finally:
            await self._close_session(it)
            if it.started_at:
                it.elapsed += time.time() - it.started_at
                it.started_at = None
            it.conns = 0
            it.speed = 0
            for s in it.segments:
                s.active = False
                s.pos = s.flushed
            if it.segments and it.state != "done":
                it.done = sum(s.flushed - s.start for s in it.segments)
            self.touch(it)
            self.save()
            if it.state in ("done", "error"):
                self.broadcast_event({"t": "finished", "id": it.id, "state": it.state,
                                      "filename": it.filename, "error": it.error})
            asyncio.get_running_loop().call_soon(self.kick)

    async def _close_session(self, it):
        if it.session is not None:
            s, it.session = it.session, None
            try:
                await s.close()
            except Exception:
                pass
        sessions, it.lane_sessions = it.lane_sessions, {}
        for idx, s in sessions.items():
            lane = next((ln for ln in self.lanes if ln.idx == idx), None)
            if lane is None:
                continue
            try:
                await asyncio.wait_for(lane.submit(s.close), 10)
            except Exception:
                pass

    async def _lane_session(self, it, lane):
        s = it.lane_sessions.get(lane.idx)
        if s is None:
            async def mk():
                return self.make_session(it, lane.connector)
            s = await lane.submit(mk)
            it.lane_sessions[lane.idx] = s
        return s

    async def io(self, fn, *a):
        """Run blocking file-system work off the event loop (antivirus can stall any file call)."""
        return await asyncio.get_running_loop().run_in_executor(self.fpool, fn, *a)

    async def _run_http(self, it):
        it.session = self.make_session(it)

        def _part_ok():
            adopt_legacy_part(it)
            return os.path.exists(it.part_path) and os.path.getsize(it.part_path) == it.size

        resumable = bool(it.filename and it.dir and it.ranges and it.size and it.segments
                         and await self.io(_part_ok))
        first = None
        if resumable:
            for s in it.segments:
                s.pos = s.flushed
                s.active = False
            it.done = sum(s.flushed - s.start for s in it.segments)
        else:
            it.note = "Connecting to server..."
            self.touch(it)
            first, total, ranges = await self._open_probe(it)
            fresh_name = not it.dir
            self._assign_name_fallback(it)
            try:
                await self.io(lambda: os.makedirs(it.dir, exist_ok=True))
            except OSError as e:
                first.close()
                raise DiskError(e)
            if (fresh_name and not it.user_named) or (it.dir, it.filename) in self.reserved:
                it.filename = self.unique_name(it.dir, it.filename, it)
            if it.size and total and total != it.size and it.done:
                log.info("size changed on server, restarting %s", it.filename)
            it.size = total
            it.ranges = ranges
            it.done = 0
            free = await self.io(disk_free, it.dir)
            if total and free is not None and free < total:
                first.close()
                raise DiskError(f"not enough space on {volume_of(it.dir)[1] or 'this drive'}: "
                                f"need {fmt_size(total)}, {fmt_size(free)} free")
            it.note = "Preparing file..."
            self.touch(it)
            try:
                await self.io(preallocate, it.part_path, total)
            except OSError as e:
                first.close()
                raise DiskError(e)
            it.note = ""
            if total and ranges:
                n = max(1, min(it.max_conns, total // MIN_FIRST_SPLIT or 1))
                step = total // n
                it.segments = []
                for k in range(n):
                    a = k * step
                    b = total - 1 if k == n - 1 else (k + 1) * step - 1
                    it.segments.append(Segment(a, b))
            else:
                it.segments = [Segment(0, total - 1 if total else None)]
        self.touch(it)
        try:
            await self._coordinate(it, first)
        except NoRangeSupport:
            log.info("%s: server ignores ranges, switching to one connection", it.filename)
            it.ranges = False
            it.segments = [Segment(0, (it.size - 1) if it.size else None)]
            it.done = 0
            await self.io(preallocate, it.part_path, it.size)
            await self._coordinate(it, None)
        await self._finalize(it)

    def _next_segment(self, it):
        for s in it.segments:
            if not s.active and not s.complete:
                return s
        if not it.ranges or it.size is None:
            return None
        with self.nlock:               # connections on other threads read s.pos / s.end per chunk
            best, best_rem = None, 0
            for s in it.segments:
                if s.active and s.end is not None:
                    rem = s.end - s.pos + 1
                    if rem >= 2 * STEAL_MIN and rem > best_rem:
                        best, best_rem = s, rem
            if not best:
                return None
            mid = best.pos + best_rem // 2
            new = Segment(mid, best.end)
            best.end = mid - 1
            it.segments.insert(it.segments.index(best) + 1, new)
            return new

    async def _coordinate(self, it, first_resp):
        workers = {}          # future -> (segment, LaneJob or None)
        last_err = None
        loop = asyncio.get_running_loop()

        async def spawn(seg, resp=None):
            lane = None
            if resp is None and self.lanes:
                lane = min(self.lanes, key=lambda ln: ln.busy)
                try:
                    sess = await self._lane_session(it, lane)
                except Exception as e:
                    log.warning("lane %d unavailable: %s", lane.idx, e)
                    lane = None
            seg.active = True
            seg.pos = seg.flushed
            if lane is None:
                workers[asyncio.create_task(self._worker(it, seg, resp))] = (seg, None)
            else:
                job = LaneJob(lane, lambda: self._worker(it, seg, None, sess), loop)
                workers[job.fut] = (seg, job)

        # it.max_conns is the user's setting and is never lowered by errors. `cap` is this run's
        # working limit: cut when a server pushes back, raised again after 30 s without trouble.
        cap = max(1, it.max_conns)
        user_max = it.max_conns
        last_cut = 0.0
        try:
            if first_resp is not None:
                await spawn(it.segments[0], first_resp)
            while True:
                if it.max_conns != user_max:            # user changed connections while running
                    user_max = it.max_conns
                    cap = max(1, user_max)
                if cap < user_max and time.monotonic() - last_cut > 30:
                    cap = min(user_max, cap + max(2, cap // 2))
                    if cap >= user_max and it.note.startswith("Server limits"):
                        it.note = ""
                limit = cap if it.ranges else 1
                while len(workers) < limit:
                    seg = self._next_segment(it)
                    if seg is None:
                        break
                    await spawn(seg)
                it.conns = len(workers)
                if not workers:
                    break
                done, _ = await asyncio.wait(workers.keys(), timeout=5, return_when=asyncio.FIRST_COMPLETED)
                for t in done:
                    seg = workers.pop(t)[0]
                    seg.active = False
                    seg.pos = seg.flushed
                    exc = t.exception()
                    if exc is None:
                        if t.result() == "throttled":
                            cap = max(1, len(workers))
                            last_cut = time.monotonic()
                            it.note = f"Server limits connections - using {cap}, trying more again shortly"
                            if not workers:
                                await asyncio.sleep(3)
                        continue
                    if isinstance(exc, (NoRangeSupport, FileChanged, DiskError, UserFacingError)):
                        raise exc
                    last_err = exc
                    if workers:
                        cap = max(1, min(cap, len(workers)))
                        last_cut = time.monotonic()
                    else:
                        raise exc
                self.touch(it)
        finally:
            for t, (_seg, job) in workers.items():
                if job is not None:
                    job.cancel()
                else:
                    t.cancel()
            # wait until every connection has really stopped and closed its file, even if we are
            # cancelled again meanwhile (pause, then remove right away)
            pending, again = set(workers), False
            while pending:
                try:
                    _d, pending = await asyncio.wait(pending)
                except asyncio.CancelledError:
                    again = True
            for t in workers:
                if t.done() and not t.cancelled():
                    t.exception()                      # mark retrieved
            for s in it.segments:
                s.active = False
            if again:
                raise asyncio.CancelledError()
        if not all(s.complete for s in it.segments):
            raise last_err or ConnectionError("download incomplete")

    async def _worker(self, it, seg, resp=None, session=None):
        session = session or it.session
        nlock = self.nlock
        attempts = 0
        use_url = it.final_url or it.url
        writer = await SegWriter.create(it.part_path, seg, self.pool)
        # memory stays bounded: about 2 x flush_at per connection (one batch writing, one filling)
        flush_at = max(FLUSH_MIN, min(FLUSH_AT, FLUSH_BUDGET // max(1, it.max_conns or 1)))
        try:
            while not seg.complete and not (seg.end is not None and seg.pos > seg.end):
                try:
                    if resp is None:
                        hdrs = {}
                        if it.ranges:
                            hdrs["Range"] = f"bytes={seg.pos}-{seg.end}" if seg.end is not None else f"bytes={seg.pos}-"
                        elif seg.pos:
                            # no range support: must start over
                            seg.pos = seg.flushed = 0
                            it.done = 0
                        resp = await session.get(use_url, headers=hdrs, allow_redirects=True, max_redirects=30,
                                                    **self.rkw())
                        st = resp.status
                        if st in (401, 403, 410) and use_url != it.url:
                            resp.close()
                            resp = None
                            use_url = it.url          # signed redirect expired - follow original again
                            continue
                        if st >= 400:
                            raise HTTPStatusError(st, resp.reason or "")
                        if it.ranges:
                            if st == 200 and seg.pos != 0:
                                raise NoRangeSupport()
                            if st == 206:
                                cr = parse_content_range(resp.headers.get("Content-Range"))
                                if cr:
                                    if it.size and cr[2] and cr[2] != it.size:
                                        raise FileChanged()
                                    if cr[0] != seg.pos:
                                        raise NoRangeSupport()
                    got_any = False
                    ended_naturally = True
                    content = resp.content
                    while True:
                        rate = self.limiter.rate
                        if rate:      # speed limit: small slices so the limit holds from the first second
                            chunk = await content.read(max(16384, min(MB, rate // 32)))
                        else:
                            chunk = await content.readany()
                        if not chunk:
                            break
                        if type(chunk) is not bytes:
                            chunk = bytes(chunk)      # never keep a view of a reused buffer
                        with nlock:                   # the coordinator may shorten seg.end any time
                            if seg.end is not None:
                                lim = seg.end - seg.pos + 1
                                if lim <= 0:
                                    n = 0
                                elif len(chunk) > lim:
                                    chunk = chunk[:lim]
                                    n = lim
                                else:
                                    n = len(chunk)
                            else:
                                n = len(chunk)
                            seg.pos += n
                            it.done += n
                            self.rx += n
                        if n == 0:
                            ended_naturally = False
                            break
                        writer.chunks.append(chunk)
                        writer.nbuf += n
                        if not got_any:
                            got_any = True
                            attempts = 0
                            if it.note.startswith("Retrying"):
                                it.note = ""
                        if rate:
                            await self.limiter.take(n)
                        if writer.nbuf >= flush_at:
                            await writer.flush(wait=False)
                        if seg.end is not None and seg.pos > seg.end:
                            ended_naturally = False
                            break
                    resp.close()
                    resp = None
                    if ended_naturally:
                        if seg.end is None:
                            seg.end = seg.pos - 1
                            if it.size is None:
                                it.size = seg.pos
                        elif seg.pos <= seg.end:
                            raise aiohttp.ClientPayloadError("connection closed early")
                    await writer.flush()
                except (aiohttp.ClientError, asyncio.TimeoutError, ConnectionError, HTTPStatusError) as e:
                    if resp is not None:
                        resp.close()
                        resp = None
                    await writer.flush()
                    if isinstance(e, HTTPStatusError):
                        if e.status in (429, 503) or (e.status in (403, 509) and it.conns > 1):
                            return "throttled"
                        if e.status not in (408, 500, 502, 504, 520, 521, 522, 524):
                            raise
                    if not it.ranges and seg.end is None:
                        raise
                    attempts += 1
                    if attempts > RETRIES:
                        raise
                    it.note = f"Retrying ({attempts}/{RETRIES}): {human_error(e)}"
                    await asyncio.sleep(min(20, 0.6 * (2 ** attempts)))
            return "ok"
        finally:
            if resp is not None:
                resp.close()
            await writer.close()
            seg.pos = seg.flushed

    async def _finalize(self, it):
        done = sum(s.flushed - s.start for s in it.segments)
        if it.size is None:
            it.size = done
        if done != it.size:
            raise ConnectionError(f"size mismatch {done} != {it.size}")
        src = it.part_path if await self.io(os.path.exists, it.part_path) else await self.io(self._find_part, it)
        if await self.io(os.path.exists, it.path):
            it.filename = self.unique_name(it.dir, it.filename, it)
        for i in range(20):
            try:
                await self.io(os.replace, src, it.path)
                break
            except PermissionError:  # antivirus / indexer holding the file - retry
                if i == 19:
                    raise DiskError("could not rename finished file (in use by another program)")
                await asyncio.sleep(0.5)
        it.done = it.size
        it.segments = []
        it.state = "done"
        it.finished = time.time()
        it.note = ""
        it.error = None

    def _find_part(self, it):
        for o in list(Path(it.dir).glob("*.sdmpart")) + list(Path(it.dir).glob("*.fdlpart")):
            if o.stat().st_size == it.size:
                return str(o)
        raise FileNotFoundError("partial file missing")

    # ----- FTP (segmented with REST, one control connection per segment)
    async def _run_ftp(self, it):
        loop = asyncio.get_running_loop()
        size, rest = await loop.run_in_executor(None, ftp_info, it.url)
        if not it.filename:
            it.filename = sanitize(name_from_url(it.url) or "download")
            it.category = category_of(it.filename)
        fresh = not it.dir
        self._assign_name_fallback(it)
        await self.io(lambda: os.makedirs(it.dir, exist_ok=True))
        if fresh and not it.user_named:
            it.filename = self.unique_name(it.dir, it.filename, it)
        await self.io(adopt_legacy_part, it)
        resumable = (rest and size and it.size == size and it.segments and os.path.exists(it.part_path)
                     and os.path.getsize(it.part_path) == size)
        if not resumable:
            it.size, it.ranges = size, bool(rest and size)
            await self.io(preallocate, it.part_path, size)
            if it.ranges:
                n = max(1, min(it.max_conns, 8, size // MIN_FIRST_SPLIT or 1))
                step = size // n
                it.segments = [Segment(k * step, size - 1 if k == n - 1 else (k + 1) * step - 1) for k in range(n)]
            else:
                it.segments = [Segment(0, size - 1 if size else None)]
        for sg in it.segments:
            sg.pos, sg.active = sg.flushed, False
        it.done = sum(sg.flushed - sg.start for sg in it.segments)
        stop = threading.Event()
        lock = threading.Lock()
        errors = []
        workers_alive = [0]

        def pick():
            with lock:
                for sg in it.segments:
                    if not sg.active and not sg.complete:
                        sg.active = True
                        return sg
            return None

        def worker():
            with lock:
                workers_alive[0] += 1
            try:
                while not stop.is_set():
                    sg = pick()
                    if sg is None:
                        return
                    for attempt in range(6):
                        try:
                            self._ftp_segment(it, sg, stop)
                            break
                        except Exception as e:
                            msg = str(e)
                            if msg.startswith(("421", "530")) and workers_alive[0] > 1:
                                sg.active = False      # server allows fewer connections - let others finish
                                return
                            if attempt == 5 or stop.is_set():
                                errors.append(e)
                                sg.active = False
                                return
                            time.sleep(min(15, 1.5 * (2 ** attempt)))
                    sg.active = False
            finally:
                with lock:
                    workers_alive[0] -= 1

        def run_all():
            n = len([sg for sg in it.segments if not sg.complete]) or 1
            ts = [threading.Thread(target=worker, daemon=True) for _ in range(min(n, 8))]
            for t in ts:
                t.start()
            for t in ts:
                t.join()

        fut = loop.run_in_executor(None, run_all)
        try:
            while not fut.done():
                await asyncio.sleep(0.25)
                d = sum(sg.flushed - sg.start for sg in it.segments)
                with self.nlock:
                    self.rx += max(0, d - it.done)
                it.done = d
                it.conns = sum(1 for sg in it.segments if sg.active)
            await fut
        except asyncio.CancelledError:
            stop.set()
            try:
                await asyncio.wait_for(asyncio.shield(fut), 50)
            except BaseException:
                pass
            raise
        it.done = sum(sg.flushed - sg.start for sg in it.segments)
        if not all(sg.complete for sg in it.segments):
            raise errors[-1] if errors else ConnectionError("FTP transfer incomplete")
        await self._finalize(it)

    def _ftp_segment(self, it, sg, stop):
        ftp, path = ftp_connect(it.url)
        try:
            if not it.ranges:
                sg.flushed = sg.pos = 0
            conn = ftp.transfercmd("RETR " + path, rest=sg.flushed or None)
            conn.settimeout(45)
            with conn, open(it.part_path, "r+b", buffering=0) as f:
                f.seek(sg.flushed)
                while not stop.is_set():
                    if sg.end is not None and sg.flushed > sg.end:
                        break
                    data = conn.recv(1 << 20)
                    if not data:
                        if sg.end is None:
                            sg.end = sg.flushed - 1
                        elif sg.flushed <= sg.end:
                            raise ConnectionError("FTP data connection closed early")
                        break
                    if sg.end is not None:
                        data = data[: sg.end - sg.flushed + 1]
                    mv = memoryview(data)
                    while mv:
                        n = f.write(mv)
                        mv = mv[n:]
                    sg.flushed += len(data)
                    sg.pos = sg.flushed
                    if self.limiter.rate:
                        time.sleep(len(data) / self.limiter.rate * max(1, it.conns))
        finally:
            ftp_close(ftp)

    # ----- torrents (aria2)
    def _torrent_file(self, it):
        return APP_DIR / "torrents" / (it.id + ".torrent")

    async def _torrent_bytes(self, it):
        """The .torrent file contents (cached in the app folder), or None for magnet links."""
        if sdm_torrent.is_magnet(it.url):
            return None
        f = self._torrent_file(it)
        if await self.io(f.exists):
            return await self.io(f.read_bytes)
        if re.match(r"^https?://", it.url, re.I):
            sess = self.make_session(it)
            try:
                async with sess.get(it.final_url or it.url, allow_redirects=True,
                                    timeout=aiohttp.ClientTimeout(total=60), **self.rkw()) as r:
                    if r.status >= 400:
                        raise HTTPStatusError(r.status, r.reason or "")
                    cl = r.headers.get("Content-Length")
                    if cl and cl.isdigit() and int(cl) > 64 * MB:
                        raise sdm_torrent.TorrentError("this link is not a .torrent file (too large)")
                    data = await r.read()
            finally:
                await sess.close()
        else:
            data = await self.io(Path(it.url).read_bytes)
        sdm_torrent.torrent_meta(data)          # raises TorrentError if it is not a torrent

        def _save():
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(data)
        await self.io(_save)
        return data

    async def _torrent_info(self, it):
        """Name / size for the save dialog."""
        if sdm_torrent.is_magnet(it.url):
            name, size, _ih = sdm_torrent.magnet_meta(it.url)
            if not it.filename:
                it.filename = sanitize(name)
            it.size = size
            it.note = "Size and file list arrive from peers once the download starts"
        else:
            data = await self._torrent_bytes(it)
            name, size, count, _ih = sdm_torrent.torrent_meta(data)
            it.filename = sanitize(name)
            it.size = size or None
            it.note = "%d file%s" % (count, "" if count == 1 else "s")
        it.category = "Torrents"
        it.ranges = True

    def _torrent_opts(self, it, recheck=False):
        opts = {"dir": it.dir}
        if recheck:                       # file already on disk without aria2's progress file: verify, then continue
            opts["check-integrity"] = "true"
        if self.cfg.get("seed_after"):
            opts.update({"seed-ratio": "1.0", "seed-time": "1440"})
        else:
            opts.update({"seed-time": "0"})
        return opts

    async def _torrent_gid(self, it, recheck=False):
        if it.gid:
            try:
                st = await self.aria.call("tellStatus", it.gid, ["status"])
                if st.get("status") in ("active", "waiting", "paused"):
                    if st.get("status") == "paused":
                        await self.aria.call("unpause", it.gid)
                    return it.gid
                try:
                    await self.aria.call("removeDownloadResult", it.gid)
                except Exception:
                    pass
            except Exception:
                pass                     # engine restarted: re-add, aria2 resumes from its .aria2 file
            it.gid = None
        if sdm_torrent.is_magnet(it.url):
            gid = await self.aria.call("addUri", [it.url], self._torrent_opts(it, recheck))
        else:
            data = await self._torrent_bytes(it)
            gid = await self.aria.call("addTorrent", base64.b64encode(data).decode("ascii"), [],
                                       self._torrent_opts(it, recheck))
        it.gid = gid
        self.touch(it)
        return gid

    @staticmethod
    def _torrent_top(st, name):
        """Real top-level file/folder name aria2 writes (may differ from the display name)."""
        try:
            d = st.get("dir") or ""
            p = st["files"][0]["path"]
            if p and not p.startswith("[METADATA]"):
                top = re.split(r"[\\/]", os.path.relpath(p, d))[0]
                if top and top != "..":
                    return top
        except Exception:
            pass
        return sanitize(name)

    async def _run_torrent(self, it):
        it.note = "Starting torrent engine..."
        self.touch(it)
        await self.aria.ensure()
        if not it.dir:
            it.dir = self.target_dir(it)
        await self.io(lambda: os.makedirs(it.dir, exist_ok=True))
        gid = await self._torrent_gid(it)
        named = False
        seeding = False
        rechecked = False
        try:
            while True:
                keys = sdm_torrent.STATUS_KEYS + ([] if named else ["files"])
                st = await self.aria.call("tellStatus", gid, keys)
                fb = st.get("followedBy")
                if fb:                                   # magnet: metadata done -> real download
                    gid = fb[0]
                    it.gid = gid
                    self.touch(it)
                    continue
                total = int(st.get("totalLength") or 0)
                done = int(st.get("completedLength") or 0)
                if done > it.done:
                    with self.nlock:
                        self.rx += done - it.done
                it.done = done
                if total:
                    it.size = total
                it.conns = int(st.get("connections") or 0)
                info = (st.get("bittorrent") or {}).get("info") or {}
                name = info.get("name")
                if not named and name and st.get("files"):
                    it.filename = self._torrent_top(st, name)
                    named = True
                status = st.get("status")
                if status == "complete":
                    break
                if status == "error":
                    msg = st.get("errorMessage") or "download failed"
                    if not rechecked and "control file" in msg:
                        rechecked = True              # files already there: check them and carry on
                        it.note = "Files already exist - verifying them..."
                        self.touch(it)
                        try:
                            await self.aria.call("removeDownloadResult", gid)
                        except Exception:
                            pass
                        it.gid = None
                        gid = await self._torrent_gid(it, recheck=True)
                        continue
                    raise sdm_torrent.TorrentError(msg)
                if status == "removed":
                    raise sdm_torrent.TorrentError("stopped by the torrent engine")
                if st.get("seeder") == "true" and total and done >= total:
                    seeding = True
                    break
                if status == "paused":
                    await self.aria.call("unpause", gid)
                seeds = int(st.get("numSeeders") or 0)
                if not name:
                    it.note = "Getting torrent info from peers... (%d connected)" % it.conns
                elif st.get("verifyIntegrityPending") == "true":
                    it.note = "Checking data already on disk..."
                elif it.conns == 0:
                    it.note = "Looking for peers (DHT + %s trackers)..." % ("public" if sdm_torrent.is_magnet(it.url) else "torrent + public")
                else:
                    it.note = "%d peers connected - %d seeds" % (it.conns, seeds)
                self.touch(it)
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            if it.state in ("paused", "queued"):          # pause / quit: keep it resumable in aria2
                try:
                    await self.aria.call("forcePause", gid)
                except Exception:
                    pass
            raise
        it.size = it.size or it.done
        it.done = it.size
        it.state = "done"
        it.finished = time.time()
        it.error = None
        it.conns = 0
        if seeding and self.cfg.get("seed_after"):
            it.note = "Seeding (sharing back until ratio 1.0)"
        else:
            it.note = ""
            try:
                await self.aria.call("removeDownloadResult", gid)
            except Exception:
                pass
            it.gid = None

    async def _torrent_forget(self, it, delete_data=False, keep_torrent_file=True):
        if it.gid and self.aria.running():
            try:
                await self.aria.call("forceRemove", it.gid)
                await asyncio.sleep(0.6)                  # let aria2 close its files
            except Exception:
                pass
            try:
                await self.aria.call("removeDownloadResult", it.gid)
            except Exception:
                pass
        it.gid = None

        def _rm():
            if it.dir and it.filename:
                ctrl = os.path.join(it.dir, it.filename + ".aria2")
                try:
                    if os.path.isfile(ctrl):
                        os.remove(ctrl)
                except OSError:
                    pass
                if delete_data:
                    p = it.path
                    try:
                        if os.path.isdir(p):
                            shutil.rmtree(p, ignore_errors=True)
                        elif os.path.isfile(p):
                            os.remove(p)
                    except OSError:
                        pass
            if not keep_torrent_file:
                try:
                    self._torrent_file(it).unlink()
                except OSError:
                    pass
                # a copy kept for a .torrent added from the window: delete it unless another item uses it
                try:
                    up = Path(it.url)
                    if (up.parent.resolve() == ADDED_TORRENTS.resolve() and up.is_file()
                            and not any(o is not it and o.url == it.url for o in self.items.values())):
                        up.unlink()
                except (OSError, ValueError):
                    pass
        await self.io(_rm)

    # ----- media (yt-dlp)
    async def _run_media(self, it):
        if not HAVE_YTDLP:
            raise UserFacingError("Media grabbing needs yt-dlp:  pip install yt-dlp")
        self._assign_name_fallback(it)
        os.makedirs(it.dir, exist_ok=True)
        loop = asyncio.get_running_loop()
        state = {"total": None, "done": 0}
        it.ranges = True
        it.segments = []
        cookiefile = None
        if it.cookies:
            cookiefile = str(APP_DIR / f"cookies-{it.id}.txt")
            cookies_to_netscape(it.cookies, cookiefile)

        def hook(d):
            if it.stop:
                raise KeyboardInterrupt("stopped")
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate")
                got = d.get("downloaded_bytes") or 0
                if total:
                    it.size = int(total)
                delta = got - state["done"] if got >= state["done"] else got
                state["done"] = got
                it.done = got
                with self.nlock:
                    self.rx += max(0, delta)
                if d.get("filename"):
                    it.filename = os.path.basename(d["filename"])
                frag = d.get("fragment_count")
                it.note = f"Fragments {d.get('fragment_index', 0)}/{frag}" if frag else "Grabbing media"
                it.conns = it.max_conns if frag else 1
            elif d.get("status") == "finished":
                state["done"] = 0

        ff = which_ffmpeg()
        stem = it.filename.rsplit(".", 1)[0] if (it.user_named and it.filename) else "%(title).150B"
        opts = {
            "outtmpl": {"default": os.path.join(it.dir, stem + ".%(ext)s")},
            "format": "bv*+ba/b" if ff else "b[acodec!=none][vcodec!=none]/b",
            "merge_output_format": "mp4" if ff else None,
            "concurrent_fragment_downloads": max(4, min(32, it.max_conns)),
            "http_chunk_size": 10 * MB,
            "retries": 15, "fragment_retries": 15, "noplaylist": True,
            "quiet": True, "no_warnings": True, "noprogress": True, "progress_hooks": [hook],
            "http_headers": {"User-Agent": it.user_agent or DEFAULT_UA,
                             **({"Referer": it.referrer} if it.referrer else {})},
            "windowsfilenames": True, "continuedl": True, "nopart": False,
            "ratelimit": (self.limiter.rate or None),
        }
        if cookiefile:
            opts["cookiefile"] = cookiefile
        if ff:
            opts["ffmpeg_location"] = ff

        def job():
            import yt_dlp
            with yt_dlp.YoutubeDL({k: v for k, v in opts.items() if v is not None}) as ydl:
                info = ydl.extract_info(it.url, download=True)
                if info and info.get("_type") == "playlist" and info.get("entries"):
                    info = info["entries"][0]
                rd = (info or {}).get("requested_downloads") or []
                return rd[0].get("filepath") if rd else ydl.prepare_filename(info)

        it.note = "Finding media..."
        self.touch(it)
        fut = loop.run_in_executor(self.pool, job)
        try:
            path = await asyncio.shield(fut)
        except asyncio.CancelledError:
            it.stop = True
            try:
                await asyncio.wait_for(asyncio.shield(fut), 10)
            except BaseException:
                pass
            raise
        except Exception as e:
            msg = str(e).replace("ERROR: ", "")
            if "Unsupported URL" in msg:
                msg = "No downloadable video/audio found on this page"
            raise UserFacingError(re.sub(r"\x1b\[[0-9;]*m", "", msg)[:300])
        finally:
            if cookiefile:
                try:
                    os.remove(cookiefile)
                except OSError:
                    pass
        if path and os.path.exists(path):
            it.filename = os.path.basename(path)
            it.dir = os.path.dirname(path)
            it.size = os.path.getsize(path)
        it.done = it.size or it.done
        it.category = category_of(it.filename)
        it.state = "done"
        it.finished = time.time()
        it.note = ""

    # ----- ticker / broadcast
    async def _ticker(self):
        last = time.monotonic()
        last_rx = 0
        last_save = time.monotonic()
        while True:
            await asyncio.sleep(0.5)
            now = time.monotonic()
            self.heartbeat = now
            dt = max(1e-3, now - last)
            last = now
            delta = self.rx - last_rx
            last_rx = self.rx
            day = time.strftime("%Y-%m-%d")
            if self.stats.get("day") != day:
                self.stats["day"], self.stats["today"] = day, 0
            self.stats["today"] += delta
            self.stats["total"] = self.stats.get("total", 0) + delta
            inst = delta / dt
            self.speed = inst if self.speed == 0 else self.speed * 0.5 + inst * 0.5
            if inst == 0 and not any(i.state == "downloading" for i in self.items.values()):
                self.speed = 0
            self.hist.append(round(self.speed))
            self.hist = self.hist[-120:]
            busy = any(i.state == "downloading" for i in self.items.values())
            want_awake = busy and bool(self.cfg.get("keep_awake", True))
            if want_awake != self.awake:
                sdm_torrent.win_keep_awake(want_awake)
                self.awake = want_awake
            for it in self.items.values():
                if it.state == "downloading":
                    i_inst = (it.done - it.last_done) / dt
                    it.speed = i_inst if it.speed == 0 else it.speed * 0.6 + i_inst * 0.4
                    self.changed.add(it.id)
                it.last_done = it.done
            if self.dirty and now - last_save > 1:
                self.save()
                last_save = now
            self.broadcast_tick()

    def snapshot(self):
        return {
            "t": "full", "items": [self.items[i].view() for i in self.order if i in self.items],
            "config": self.cfg, "speed": round(self.speed), "hist": self.hist, "stats": self.stats,
            "ytdlp": HAVE_YTDLP, "ffmpeg": bool(which_ffmpeg()), "version": VERSION,
            "ext": time.time() - self.last_ext_ping < 150, "platform": sys.platform,
            "torrents": self.aria.available, "tray": getattr(self, "tray", None) is not None,
        }

    def broadcast_tick(self):
        if not self.ws_clients:
            self.changed.clear()
            self.removed.clear()
            return
        msg = {"t": "tick", "speed": round(self.speed), "stats": self.stats,
               "ext": time.time() - self.last_ext_ping < 150,
               "items": [self.items[i].view() for i in list(self.changed) if i in self.items],
               "removed": list(self.removed)}
        self.changed.clear()
        self.removed.clear()
        self._send(msg)

    def broadcast_event(self, msg):
        self._send(msg)

    def _send(self, msg):
        data = json.dumps(msg)
        for ws in list(self.ws_clients):
            if ws.closed:
                self.ws_clients.discard(ws)
                continue
            asyncio.ensure_future(_safe_send(ws, data))


async def _safe_send(ws, data):
    try:
        await ws.send_str(data)
    except Exception:
        pass


def glob_escape(s):
    return re.sub(r"([\[\]*?])", r"[\1]", s)


def fmt_size(n):
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.1f} {u}"
        n /= 1024
    return f"{n:.1f} PB"


# --------------------------------------------------------------------------- OS helpers
def open_path(path):
    if sys.platform.startswith("win"):
        os.startfile(path)  # noqa
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def reveal(path):
    if sys.platform.startswith("win"):
        if os.path.exists(path):
            subprocess.Popen(f'explorer /select,"{os.path.normpath(path)}"')
        else:
            os.startfile(os.path.dirname(path))  # noqa
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path] if os.path.exists(path) else ["open", os.path.dirname(path)])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)])


def browser_candidates():
    if sys.platform.startswith("win"):
        pf = [os.environ.get(k, "") for k in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA")]
        rel = [r"Google\Chrome\Application\chrome.exe", r"Microsoft\Edge\Application\msedge.exe",
               r"BraveSoftware\Brave-Browser\Application\brave.exe"]
        return [os.path.join(b, r) for r in rel for b in pf if b]
    if sys.platform == "darwin":
        return ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"]
    return [p for p in (shutil.which(n) for n in ("google-chrome", "chromium", "chromium-browser",
                                                   "microsoft-edge", "brave-browser")) if p]


def open_window(port):
    url = f"http://127.0.0.1:{port}/"
    if os.environ.get("SDM_NO_WINDOW"):
        return
    for exe in browser_candidates():
        if os.path.exists(exe):
            try:
                flags = 0x00000008 if sys.platform.startswith("win") else 0  # DETACHED_PROCESS
                subprocess.Popen([exe, f"--app={url}", "--window-size=1240,840"], creationflags=flags,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except OSError:
                continue
    webbrowser.open(url)


def pick_folder_native(initial):
    """System folder dialog. tkinter first, PowerShell fallback on Windows."""
    try:
        import tkinter as tk
        from tkinter import filedialog
        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        p = filedialog.askdirectory(initialdir=initial or str(Path.home()), title="SDM - choose download folder")
        root.destroy()
        return p or None
    except Exception:
        pass
    if sys.platform.startswith("win"):
        ps = ("Add-Type -AssemblyName System.Windows.Forms;"
              "$d=New-Object System.Windows.Forms.FolderBrowserDialog;$d.ShowNewFolderButton=$true;"
              f"$d.SelectedPath='{(initial or '').replace(chr(39), '')}';"
              "if($d.ShowDialog((New-Object System.Windows.Forms.Form -Property @{TopMost=$true}))-eq'OK'){$d.SelectedPath}")
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-STA", "-Command", ps], capture_output=True,
                                 text=True, timeout=600, creationflags=0x08000000)
            return out.stdout.strip() or None
        except Exception:
            return None
    raise RuntimeError("no native dialog available")


def list_dir(path):
    if not path:
        if sys.platform.startswith("win"):
            drives = [f"{d}:\\" for d in string.ascii_uppercase if os.path.exists(f"{d}:\\")]
            return {"path": "", "parent": None, "dirs": drives}
        path = "/"
    path = os.path.abspath(os.path.expanduser(path))
    dirs = []
    try:
        with os.scandir(path) as it:
            for e in it:
                try:
                    if e.is_dir() and not e.name.startswith((".", "$")):
                        dirs.append(e.name)
                except OSError:
                    pass
    except OSError as e:
        raise UserFacingError(str(e))
    dirs.sort(key=str.lower)
    parent = os.path.dirname(path.rstrip("\\/")) if path.rstrip("\\/") else None
    if sys.platform.startswith("win") and re.match(r"^[A-Za-z]:\\?$", path):
        parent = ""
    if parent == path:
        parent = None
    return {"path": path, "parent": parent, "dirs": dirs[:500]}


def quick_places():
    h = Path.home()
    places = [("Downloads", h / "Downloads"), ("Desktop", h / "Desktop"), ("Documents", h / "Documents"),
              ("Videos", h / ("Movies" if sys.platform == "darwin" else "Videos")), ("Music", h / "Music"), ("Home", h)]
    out = [{"name": n, "path": str(p)} for n, p in places if p.exists()]
    if sys.platform.startswith("win"):
        out += [{"name": f"{d}:", "path": f"{d}:\\"} for d in string.ascii_uppercase if os.path.exists(f"{d}:\\")]
    return out


def volume_of(path):
    """Closest existing folder of `path` (the volume it will be written to) and a short drive label."""
    try:
        p = Path(os.path.abspath(os.path.expanduser(str(path or "")) or "."))
    except (OSError, ValueError):
        return None, ""
    while not p.exists() and p.parent != p:
        p = p.parent
    drive = os.path.splitdrive(str(p))[0]
    label = drive if drive else str(p.anchor or "/")
    return str(p), label


def disk_free(path):
    root, _ = volume_of(path)
    if not root:
        return None
    try:
        return shutil.disk_usage(root).free
    except OSError:
        return None


def drives_info():
    """Windows drives (or the home volume elsewhere) with free space, for quick folder choices."""
    out = []
    if sys.platform.startswith("win"):
        for d in string.ascii_uppercase:
            root = f"{d}:\\"
            if os.path.exists(root):
                try:
                    u = shutil.disk_usage(root)
                    out.append({"drive": f"{d}:", "root": root, "free": u.free, "total": u.total})
                except OSError:
                    pass
    else:
        try:
            u = shutil.disk_usage(str(Path.home()))
            out.append({"drive": "~", "root": str(Path.home()), "free": u.free, "total": u.total})
        except OSError:
            pass
    return out


# --------------------------------------------------------------------------- web server
ALLOWED_HOSTS = {"127.0.0.1", "localhost", "[::1]", "::1"}


def make_app(engine):
    @web.middleware
    async def guard(request, handler):
        host = request.host.rsplit(":", 1)[0] if not request.host.startswith("[") else request.host.split("]")[0] + "]"
        if host not in ALLOWED_HOSTS:
            return web.Response(status=403, text="forbidden host")
        origin = request.headers.get("Origin")
        ok_origin = (not origin or origin in (f"http://127.0.0.1:{engine.port}", f"http://localhost:{engine.port}")
                     or origin.startswith(("chrome-extension://", "moz-extension://", "extension://")))
        if not ok_origin:
            return web.Response(status=403, text="forbidden origin")
        if request.method == "OPTIONS":
            resp = web.Response(status=204)
        else:
            try:
                resp = await handler(request)
            except UserFacingError as e:
                resp = web.json_response({"ok": False, "error": str(e)}, status=400)
            except web.HTTPException:
                raise
            except Exception as e:
                log.exception("api error")
                resp = web.json_response({"ok": False, "error": human_error(e)}, status=500)
        if origin:
            resp.headers["Access-Control-Allow-Origin"] = origin
            resp.headers["Access-Control-Allow-Headers"] = "Content-Type"
            resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            resp.headers["Access-Control-Allow-Private-Network"] = "true"
        return resp

    app = web.Application(middlewares=[guard], client_max_size=8 * MB)
    routes = web.RouteTableDef()

    def get_item(data):
        it = engine.items.get(data.get("id", ""))
        if not it:
            raise UserFacingError("download not found")
        return it

    @routes.get("/")
    async def index(_):
        resp = web.FileResponse(UI_DIR / "index.html")
        resp.headers["Cache-Control"] = "no-store"
        return resp

    @routes.get("/api/ping")
    async def ping(request):
        if request.query.get("src") == "ext":
            engine.last_ext_ping = time.time()
        return web.json_response({"app": "SDM", "version": VERSION, "ok": True,
                                  "intercept": engine.cfg.get("intercept", True),
                                  "min_size_kb": engine.cfg.get("min_size_kb", 0),
                                  "skip_sites": engine.cfg.get("skip_sites", "")})

    @routes.get("/api/state")
    async def state(_):
        return web.json_response(engine.snapshot())

    @routes.post("/api/add")
    async def add(request):
        data = await request.json()
        if data.get("source") == "browser":
            engine.last_ext_ping = time.time()
        urls = data.get("urls")
        if urls:
            ids, dups = [], 0
            for u in urls:
                d = dict(data, url=u)
                d.pop("urls", None)
                d.pop("filename", None)
                try:
                    o = engine.add(d)
                    ids.append(o.id)
                    if getattr(o, "duplicate", False):
                        dups += 1
                        o.duplicate = False
                except UserFacingError:
                    pass
            if not ids:
                raise UserFacingError("No valid http(s) links found")
            return web.json_response({"ok": True, "ids": ids, "duplicate": dups == len(ids)})
        it = engine.add(data)
        dup = getattr(it, "duplicate", False)
        it.duplicate = False
        return web.json_response({"ok": True, "id": it.id, "ids": [it.id], "duplicate": dup})

    @routes.post("/api/add_torrent")
    async def add_torrent(request):
        """A .torrent file chosen or dropped in the window: the browser sends the file itself."""
        name = os.path.basename((request.query.get("name") or "file.torrent").replace("\\", "/")) or "file.torrent"
        data = bytearray()
        async for chunk in request.content.iter_chunked(1 << 16):
            data += chunk
            if len(data) > TORRENT_MAX:
                raise UserFacingError(f"{name} is too large to be a .torrent file")
        data = bytes(data)
        try:
            tname, _size, _count, ih = sdm_torrent.torrent_meta(data)
        except Exception as e:
            raise UserFacingError(f"{name} is not a valid .torrent file") from e
        dest = ADDED_TORRENTS / f"{ih}.torrent"

        def _save():
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_suffix(".tmp")
            tmp.write_bytes(data)
            os.replace(tmp, dest)
        await engine.io(_save)
        it = engine.add({"url": str(dest), "source": "ui"})
        dup = getattr(it, "duplicate", False)
        it.duplicate = False
        return web.json_response({"ok": True, "id": it.id, "ids": [it.id], "duplicate": dup, "name": it.filename or tname})

    @routes.post("/api/confirm")
    async def confirm(request):
        data = await request.json()
        it = get_item(data)
        engine.confirm(it, data)
        return web.json_response({"ok": True})

    @routes.post("/api/action")
    async def action(request):
        data = await request.json()
        act = data.get("action")
        if act in ("pause_all", "resume_all", "retry_failed", "clear_done", "clear_failed", "clear_finished"):
            if act == "pause_all":
                engine.pause_all()
            elif act == "resume_all":
                engine.resume_all()
            n = 0
            for it in list(engine.items.values()):
                if act == "retry_failed" and it.state == "error":
                    engine.resume(it)
                    n += 1
                elif ((act in ("clear_done", "clear_finished") and it.state == "done")
                      or (act in ("clear_failed", "clear_finished") and it.state == "error")):
                    engine.remove(it, keep_data=True)      # list only: files stay, torrents stop seeding
                    n += 1
            return web.json_response({"ok": True, "count": n})
        it = get_item(data)
        if act == "pause":
            engine.pause(it)
        elif act == "resume":
            engine.resume(it)
        elif act == "first":
            engine.download_first(it)
        elif act == "normal":
            it.priority = 0
            engine.touch(it)
        elif act == "restart":
            engine.restart(it)
        elif act == "remove":
            engine.remove(it, delete_file=bool(data.get("delete_file")))
        elif act == "stop_seeding":
            await engine.stop_seeding(it)
        elif act == "cancel_pending":
            if it.state != "pending":       # never cancel a download that already started
                return web.json_response({"ok": True, "ignored": True})
            engine.remove(it)
        elif act == "refresh":
            engine.refresh(it, data.get("url", ""))
        elif act == "connections":
            it.max_conns = min(MAX_CONNS, max(1, int(data.get("value") or 1)))
            engine.touch(it)
        elif act == "open":
            if it.state != "done" or not os.path.exists(it.path):
                raise UserFacingError("File is not on disk")
            open_path(it.path)
        elif act == "folder":
            reveal(it.path if it.state == "done" else it.part_path if os.path.exists(it.part_path) else it.dir)
        elif act == "browser":
            webbrowser.open(it.url)
        else:
            raise UserFacingError("unknown action")
        return web.json_response({"ok": True})

    @routes.post("/api/config")
    async def config(request):
        data = await request.json()
        for k, v in data.items():
            if k in DEFAULT_CONFIG:
                if isinstance(DEFAULT_CONFIG[k], bool):
                    v = bool(v)
                elif isinstance(DEFAULT_CONFIG[k], int):
                    v = int(v or 0)
                engine.cfg[k] = v
        engine.cfg["connections"] = min(MAX_CONNS, max(1, engine.cfg["connections"]))
        engine.cfg["max_active"] = min(20, max(1, engine.cfg["max_active"]))
        if not isinstance(engine.cfg.get("recent_dirs"), list):
            engine.cfg["recent_dirs"] = []
        engine.cfg["recent_dirs"] = [str(d) for d in engine.cfg["recent_dirs"] if d][:6]
        engine.limiter.set(engine.cfg.get("speed_limit_kb", 0))
        if "download_dir" in data:
            os.makedirs(engine.cfg["download_dir"], exist_ok=True)
        engine.save()
        engine.kick()
        loop = asyncio.get_running_loop()
        sys_keys = [k for k in ("start_with_windows", "handle_magnets") if k in data]
        if sys_keys:
            loop.run_in_executor(None, engine.apply_system_settings, tuple(sys_keys))
        if "speed_limit_kb" in data or "proxy" in data:
            asyncio.ensure_future(engine.aria.set_limits())
        if "tray" in data:
            if engine.cfg.get("tray"):
                loop.run_in_executor(None, engine._background_tray)
            elif getattr(engine, "tray", None) is not None:
                engine.tray.stop()
                engine.tray = None
        return web.json_response({"ok": True, "config": engine.cfg})

    @routes.post("/api/pickfolder")
    async def pickfolder(request):
        data = await request.json() if request.can_read_body else {}
        loop = asyncio.get_running_loop()
        try:
            p = await loop.run_in_executor(None, pick_folder_native, data.get("initial"))
        except RuntimeError:
            return web.json_response({"ok": False, "native": False})
        return web.json_response({"ok": True, "path": p})

    @routes.post("/api/listdir")
    async def listdir(request):
        data = await request.json()
        loop = asyncio.get_running_loop()
        out = await loop.run_in_executor(engine.fpool, list_dir, data.get("path"))
        out["places"] = await loop.run_in_executor(engine.fpool, quick_places)
        return web.json_response(out)

    @routes.post("/api/mkdir")
    async def mkdir(request):
        data = await request.json()
        name = sanitize(data.get("name", ""))
        p = os.path.join(data.get("path", ""), name)
        os.makedirs(p, exist_ok=True)
        return web.json_response({"ok": True, "path": p})

    @routes.post("/api/diskfree")
    async def diskfree(request):
        data = await request.json()
        path = data.get("path") or engine.cfg["download_dir"]
        loop = asyncio.get_running_loop()
        root, label = await loop.run_in_executor(engine.fpool, volume_of, path)
        free = await loop.run_in_executor(engine.fpool, disk_free, path)
        return web.json_response({"free": free, "drive": label, "path": path})

    @routes.get("/api/drives")
    async def drives(_):
        out = await asyncio.get_running_loop().run_in_executor(engine.fpool, drives_info)
        return web.json_response({"drives": out})

    @routes.post("/api/open_window")
    async def openwin(_):
        open_window(engine.port)
        return web.json_response({"ok": True})

    @routes.post("/api/quit")
    async def quit_(_):
        asyncio.ensure_future(engine.quit())
        return web.json_response({"ok": True})

    @routes.post("/api/shutdown")
    async def shutdown(_):
        await engine.stop_all()
        asyncio.get_running_loop().call_later(0.3, os._exit, 0)
        return web.json_response({"ok": True})

    @routes.get("/ws")
    async def ws_handler(request):
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        engine.ws_clients.add(ws)
        await ws.send_str(json.dumps(engine.snapshot()))
        try:
            async for _ in ws:
                pass
        finally:
            engine.ws_clients.discard(ws)
        return ws

    app.add_routes(routes)
    app.router.add_static("/ui/", UI_DIR, show_index=False)

    async def on_start(_):
        await engine.start()

    async def on_cleanup(_):
        await engine.stop_all()
        engine.pool.shutdown(wait=False, cancel_futures=True)
        engine.fpool.shutdown(wait=False, cancel_futures=True)

    app.on_startup.append(on_start)
    app.on_cleanup.append(on_cleanup)
    engine.app = app
    return app


def remember_python():
    """Record this interpreter for SDM.exe (prefers the windowless pythonw)."""
    exe = sys.executable
    if sys.platform.startswith("win") and exe.lower().endswith("python.exe"):
        w = exe[:-len("python.exe")] + "pythonw.exe"
        exe = w if os.path.exists(w) else exe
    f = ROOT / "python_path.txt"
    try:
        if not f.exists() or f.read_text(encoding="utf-8").strip() != exe:
            f.write_text(exe, encoding="utf-8")
    except OSError:
        pass


# --------------------------------------------------------------------------- CLI
def ping_info(port, timeout=2.0):
    """The running engine's /api/ping answer, or None. Never goes through a proxy."""
    import urllib.request
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"http://127.0.0.1:{port}/api/ping", timeout=timeout) as r:
            j = json.loads(r.read())
            return j if j.get("app") in ("SDM", "FastDL") else None     # FastDL = SDM's old name
    except Exception:
        return None


def is_running(port):
    return ping_info(port) is not None


def port_busy(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(1)
    try:
        return s.connect_ex(("127.0.0.1", port)) == 0
    except OSError:
        return False
    finally:
        s.close()


def kill_port_owner(port):
    """Stop a frozen SDM that still holds the port (only if it is a Python process)."""
    if not sys.platform.startswith("win"):
        return False
    try:
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True,
                             creationflags=0x08000000, timeout=15).stdout
    except Exception:
        return False
    pids = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1] in (f"127.0.0.1:{port}", f"0.0.0.0:{port}") and parts[2] in ("0.0.0.0:0", "*:*"):
            if parts[-1].isdigit() and int(parts[-1]) != os.getpid():
                pids.add(int(parts[-1]))
    killed = False
    for pid in pids:
        try:
            img = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"], capture_output=True,
                                 text=True, creationflags=0x08000000, timeout=15).stdout.lower()
            if "python" in img:
                subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True,
                               creationflags=0x08000000, timeout=15)
                log.warning("stopped unresponsive/old SDM engine (pid %d)", pid)
                killed = True
        except Exception:
            pass
    return killed


def stop_existing(port, reason):
    """Ask the running engine to quit; force it if it doesn't. Returns True when the port is free."""
    log.info("replacing running engine (%s)", reason)
    try:
        api_call(port, "/api/shutdown", {})
    except Exception:
        pass
    for _ in range(30):
        if not port_busy(port):
            return True
        time.sleep(0.2)
    kill_port_owner(port)
    for _ in range(30):
        if not port_busy(port):
            return True
        time.sleep(0.2)
    return False


def api_call(port, path, data):
    import urllib.request
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=5) as r:
        return json.loads(r.read())


def main():
    ap = argparse.ArgumentParser(description="SDM download manager")
    ap.add_argument("--port", type=int, default=int(os.environ.get("SDM_PORT", DEFAULT_PORT)))
    ap.add_argument("--no-window", action="store_true", help="run engine only (tray/background)")
    ap.add_argument("--add", metavar="URL", nargs="*", help="send link(s) to the running SDM")
    ap.add_argument("--quit", action="store_true", help="stop the running SDM")
    ap.add_argument("links", nargs="*", help="magnet links, .torrent files or URLs to download")
    args = ap.parse_args()
    links = [l for l in (args.links or []) if l.strip()]
    for i, l in enumerate(links):          # a double-clicked .torrent file arrives as a plain path
        if not re.match(r"^[a-z]+:", l, re.I) or re.match(r"^[a-z]:[\\/]", l, re.I):
            links[i] = os.path.abspath(l)
    if args.add:
        links = list(args.add) + links

    os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost")
    info = ping_info(args.port)
    if args.quit:
        if info or port_busy(args.port):
            stop_existing(args.port, "quit requested")
        return
    # an older version, or a frozen engine, must never block the new one
    if info and info.get("version") != VERSION:
        stop_existing(args.port, f"old version {info.get('version')} -> {VERSION}")
        info = None
    elif not info and port_busy(args.port):
        info = ping_info(args.port, timeout=6)       # maybe just busy - give it a moment
        if not info:
            stop_existing(args.port, "engine not responding")
    running = info is not None
    if not running:
        migrate_legacy_data()
    if links and running:
        try:
            api_call(args.port, "/api/add", {"urls": links, "source": "cli"})
        except Exception as e:
            log.warning("could not hand links to SDM: %s", e)
        if not args.no_window:
            api_call(args.port, "/api/open_window", {})
        return
    if running:
        if not args.no_window:
            open_window(args.port)
        return

    engine = Engine(args.port)
    app = make_app(engine)
    if links:
        async def _add_links(_):
            if not args.no_window:
                engine.window_opened_at = time.time()     # the window opens once, just below
            for l in links:
                try:
                    engine.add({"url": l, "source": "cli"})
                except Exception as e:
                    log.warning("could not add %s: %s", l, e)
        app.on_startup.append(_add_links)
    if not args.no_window:
        async def _open(_):
            asyncio.get_running_loop().call_later(0.4, open_window, args.port)
        app.on_startup.append(_open)
    log.info("SDM %s on http://127.0.0.1:%d  (data: %s)%s", VERSION, args.port, APP_DIR,
             "  [running inside SDM.exe]" if HOSTED else "")
    try:
        web.run_app(app, host="127.0.0.1", port=args.port, print=None, handle_signals=True,
                    shutdown_timeout=5)
    except OSError as e:
        log.error("cannot start server: %s", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
