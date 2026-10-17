"""
SDM torrent engine.

BitTorrent / magnet downloads run in a private aria2c process that SDM starts,
controls over JSON-RPC (localhost + random secret) and stops with itself.
Speed: DHT (+ bootstrap nodes), peer exchange, local peer discovery, a public tracker
list refreshed daily (with a built-in fallback), up to 200 peers per torrent, and
aggressive peer search while a download is below full speed.
"""
import asyncio
import hashlib
import io
import logging
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

import aiohttp

log = logging.getLogger("sdm")

TRACKER_SOURCES = (
    "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt",
    "https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt",
)
FALLBACK_TRACKERS = [
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.theoks.net:6969/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://tracker.tiny-vps.com:6969/announce",
    "udp://tracker1.bt.moack.co.kr:80/announce",
    "udp://opentracker.io:6969/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://tracker-udp.gbitt.info:80/announce",
    "https://tracker.tamersunion.org:443/announce",
    "https://tracker.gbitt.info:443/announce",
    "http://tracker.opentrackr.org:1337/announce",
]
# Official aria2 Windows build, fetched on first use when bin\aria2c.exe is not present (e.g. a git clone).
ARIA2_WIN_URL = "https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0-win-64bit-build1.zip"
ARIA2_WIN_SHA256 = "be2099c214f63a3cb4954b09a0becd6e2e34660b886d4c898d260febfe9d70c2"   # aria2c.exe inside it

STATUS_KEYS = ["gid", "status", "totalLength", "completedLength", "downloadSpeed", "uploadSpeed",
               "connections", "numSeeders", "errorCode", "errorMessage", "followedBy", "bittorrent",
               "dir", "infoHash", "seeder", "verifyIntegrityPending"]


class TorrentError(Exception):
    pass


# ----------------------------------------------------------------------------- Windows performance
def win_no_throttle(handle=None):
    """Opt a process out of Windows 11 'efficiency mode' / EcoQoS throttling, which slows down
    background processes that have no visible window (SDM in the tray, aria2)."""
    if not sys.platform.startswith("win"):
        return False
    try:
        import ctypes
        from ctypes import wintypes

        class PowerThrottlingState(ctypes.Structure):
            _fields_ = [("Version", wintypes.ULONG), ("ControlMask", wintypes.ULONG), ("StateMask", wintypes.ULONG)]

        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.GetCurrentProcess.restype = wintypes.HANDLE
        k32.SetProcessInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
        k32.SetProcessInformation.restype = wintypes.BOOL
        h = wintypes.HANDLE(handle) if handle else k32.GetCurrentProcess()
        # EXECUTION_SPEED (+ IGNORE_TIMER_RESOLUTION on Windows 11): controlled, state 0 = never throttle
        for mask in (0x1 | 0x4, 0x1):
            st = PowerThrottlingState(1, mask, 0)
            if k32.SetProcessInformation(h, 4, ctypes.byref(st), ctypes.sizeof(st)):    # ProcessPowerThrottling
                return True
    except Exception:
        pass
    return False


def win_keep_awake(on):
    """While downloads run: stop Windows from sleeping (the screen may still turn off)."""
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes
        ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if on else 0))
    except Exception:
        pass


# ----------------------------------------------------------------------------- bencode / metadata
def bdecode(data):
    """Minimal bencode decoder (dict keys stay bytes)."""
    def dec(i):
        c = data[i:i + 1]
        if c == b"i":
            j = data.index(b"e", i)
            return int(data[i + 1:j]), j + 1
        if c == b"l":
            i += 1
            out = []
            while data[i:i + 1] != b"e":
                v, i = dec(i)
                out.append(v)
            return out, i + 1
        if c == b"d":
            i += 1
            out = {}
            while data[i:i + 1] != b"e":
                k, i = dec(i)
                v, i = dec(i)
                out[k] = v
            return out, i + 1
        if c.isdigit():
            j = data.index(b":", i)
            n = int(data[i:j])
            return data[j + 1:j + 1 + n], j + 1 + n
        raise ValueError("bad bencode at %d" % i)
    v, _ = dec(0)
    return v


def _text(b):
    if isinstance(b, bytes):
        try:
            return b.decode("utf-8")
        except UnicodeDecodeError:
            return b.decode("latin-1")
    return str(b or "")


def torrent_meta(data):
    """(name, total_size, file_count, info_hash_hex) of a .torrent file."""
    try:
        t = bdecode(data)
        info = t[b"info"]
    except Exception as e:
        raise TorrentError("not a valid .torrent file (%s)" % e)
    name = _text(info.get(b"name.utf-8") or info.get(b"name") or b"torrent")
    if b"files" in info:
        size = sum(int(f.get(b"length", 0)) for f in info[b"files"])
        count = len(info[b"files"])
    else:
        size = int(info.get(b"length", 0))
        count = 1
    start = data.index(b"4:info") + 6
    _, end = _bdecode_span(data, start)
    ih = hashlib.sha1(data[start:end]).hexdigest()
    return name, size, count, ih


def _bdecode_span(data, i):
    """Return (value, end) for the bencoded value starting at i (used to hash the info dict)."""
    c = data[i:i + 1]
    if c == b"i":
        return None, data.index(b"e", i) + 1
    if c in (b"l", b"d"):
        i += 1
        while data[i:i + 1] != b"e":
            _, i = _bdecode_span(data, i)
        return None, i + 1
    j = data.index(b":", i)
    return None, j + 1 + int(data[i:j])


def magnet_meta(uri):
    """(display name, size or None, info hash) from a magnet link."""
    q = parse_qs(urlparse(uri).query)
    xt = [x for x in q.get("xt", []) if x.lower().startswith("urn:btih:") or x.lower().startswith("urn:btmh:")]
    if not xt:
        raise TorrentError("magnet link has no BitTorrent hash (xt=urn:btih:...)")
    ih = xt[0].split(":")[-1]
    name = unquote(q.get("dn", [""])[0]) or ("Magnet " + ih[:10])
    size = None
    try:
        size = int(q.get("xl", [None])[0]) if q.get("xl") else None
    except (TypeError, ValueError):
        size = None
    return name, size, ih


def is_magnet(url):
    return bool(re.match(r"^magnet:\?", url or "", re.I))


def looks_like_torrent_url(url, mime=""):
    path = urlparse(url).path.lower() if re.match(r"^https?://", url or "", re.I) else ""
    return path.endswith(".torrent") or "bittorrent" in (mime or "").lower()


# ----------------------------------------------------------------------------- aria2 process
class Aria2:
    def __init__(self, app_dir, root, get_cfg):
        self.app_dir = Path(app_dir)
        self.root = Path(root)
        self.get_cfg = get_cfg
        self.proc = None
        self.port = None
        self.secret = secrets.token_hex(16)
        self.session = None
        self.lock = asyncio.Lock()
        self.version = ""

    # -- locate binary
    def exe(self):
        names = ["aria2c.exe"] if sys.platform.startswith("win") else ["aria2c"]
        for n in names:
            p = self.root / "bin" / n
            if p.exists():
                return str(p)
        return shutil.which("aria2c")

    @property
    def available(self):
        return bool(self.exe())

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    # -- trackers
    async def trackers(self):
        cache = self.app_dir / "trackers.txt"
        try:
            if cache.exists() and time.time() - cache.stat().st_mtime < 24 * 3600:
                lst = [l.strip() for l in cache.read_text(encoding="utf-8").splitlines() if l.strip()]
                if lst:
                    return lst
        except OSError:
            pass
        for src in TRACKER_SOURCES:
            try:
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10), trust_env=True) as s:
                    async with s.get(src) as r:
                        if r.status == 200:
                            txt = await r.text()
                            lst = [l.strip() for l in txt.splitlines() if re.match(r"^(udp|https?|wss?)://", l.strip())]
                            if len(lst) >= 5:
                                merged = list(dict.fromkeys(lst + FALLBACK_TRACKERS))
                                try:
                                    cache.write_text("\n".join(merged), encoding="utf-8")
                                except OSError:
                                    pass
                                return merged
            except Exception:
                continue
        return FALLBACK_TRACKERS

    # -- start / stop
    def _free_port(self):
        for p in range(16800, 16900):
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                s.bind(("127.0.0.1", p))
                return p
            except OSError:
                continue
            finally:
                s.close()
        raise TorrentError("no free local port for the torrent engine")

    async def download_windows_engine(self, dest=None):
        """Fetch the official aria2 Windows build and keep aria2c.exe only if its SHA-256 matches."""
        dest = Path(dest) if dest else self.root / "bin" / "aria2c.exe"
        log.info("downloading torrent engine (aria2 1.37.0)")
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=300), trust_env=True) as s:
            async with s.get(ARIA2_WIN_URL) as r:
                if r.status != 200:
                    raise TorrentError("could not download the torrent engine (HTTP %d)" % r.status)
                data = await r.read()

        def _unpack():
            zf = zipfile.ZipFile(io.BytesIO(data))
            names = zf.namelist()
            exe_name = next((n for n in names if n.lower().endswith("/aria2c.exe")), None)
            if not exe_name:
                raise TorrentError("torrent engine download is not the expected file")
            exe = zf.read(exe_name)
            if hashlib.sha256(exe).hexdigest() != ARIA2_WIN_SHA256:
                raise TorrentError("torrent engine download failed its integrity check - not installed")
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_name(dest.name + ".tmp")
            tmp.write_bytes(exe)
            os.replace(tmp, dest)
            lic = next((n for n in names if n.endswith("/COPYING")), None)
            if lic:
                (dest.parent / "aria2-COPYING.txt").write_bytes(zf.read(lic))
        await asyncio.get_running_loop().run_in_executor(None, _unpack)
        log.info("torrent engine installed: %s", dest)
        return str(dest)

    async def ensure(self):
        async with self.lock:
            if self.running() and await self._alive():
                return
            exe = self.exe()
            if not exe and sys.platform.startswith("win"):
                exe = await self.download_windows_engine()
            if not exe:
                raise TorrentError("Torrent engine (aria2c) is missing - install aria2 (e.g. 'apt install aria2' / 'brew install aria2')")
            await self._stop_proc()
            self._kill_orphan()
            self.port = self._free_port()
            cfg = self.get_cfg()
            trackers = await self.trackers()
            log_file = self.app_dir / "aria2.log"
            try:
                if log_file.exists() and log_file.stat().st_size > 5 * 1024 * 1024:
                    log_file.unlink()
            except OSError:
                pass
            args = [
                exe,
                "--enable-rpc=true", "--rpc-listen-all=false", f"--rpc-listen-port={self.port}",
                f"--rpc-secret={self.secret}", "--rpc-max-request-size=64M",
                f"--stop-with-process={os.getpid()}",
                "--continue=true", "--file-allocation=none", "--disk-cache=64M",
                "--max-concurrent-downloads=100", "--auto-file-renaming=false", "--allow-overwrite=false",
                "--follow-torrent=mem", "--bt-save-metadata=false",
                "--rpc-save-upload-metadata=false",  # no "<hash>.torrent" copies in the download folder
                "--enable-dht=true", "--enable-dht6=true",
                f"--dht-file-path={self.app_dir / 'dht.dat'}", f"--dht-file-path6={self.app_dir / 'dht6.dat'}",
                "--dht-entry-point=dht.transmissionbt.com:6881", "--dht-entry-point6=dht.transmissionbt.com:6881",
                "--listen-port=6881-6999", "--dht-listen-port=6881-6999",
                "--bt-enable-lpd=true", "--enable-peer-exchange=true",
                "--bt-max-peers=200", "--bt-request-peer-speed-limit=100M",
                "--bt-tracker-connect-timeout=10", "--bt-tracker-timeout=15", "--bt-tracker-interval=0",
                "--bt-tracker=" + ",".join(trackers),
                "--bt-detach-seed-only=true", "--bt-remove-unselected-file=false",
                "--seed-time=0",                     # per-download options override this when seeding is on
                "--summary-interval=0", "--console-log-level=error", "--quiet=true",
                f"--log={log_file}", "--log-level=warn",
                f"--max-overall-download-limit={int(cfg.get('speed_limit_kb', 0) or 0) * 1024}",
            ]
            proxy = (cfg.get("proxy") or "").strip()
            if proxy:
                args.append("--all-proxy=" + (proxy if "://" in proxy else "http://" + proxy))
            flags = 0x08000000 if sys.platform.startswith("win") else 0
            self.proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, creationflags=flags, cwd=str(self.app_dir))
            try:
                (self.app_dir / "aria2.pid").write_text(str(self.proc.pid))
            except OSError:
                pass
            if sys.platform.startswith("win"):
                try:
                    win_no_throttle(int(self.proc._handle))     # full speed while SDM sits in the tray
                except Exception:
                    pass
            for _ in range(60):
                await asyncio.sleep(0.15)
                if not self.running():
                    raise TorrentError("torrent engine failed to start (see aria2.log)")
                if await self._alive():
                    log.info("torrent engine started (aria2 %s, %d trackers)", self.version, len(trackers))
                    return
            raise TorrentError("torrent engine did not respond")

    def _kill_orphan(self):
        """A previous SDM that crashed may have left its aria2 running (holding ports and files)."""
        pidf = self.app_dir / "aria2.pid"
        try:
            pid = int(pidf.read_text().strip())
        except (OSError, ValueError):
            return
        try:
            if sys.platform.startswith("win"):
                out = subprocess.run(["tasklist", "/FI", "PID eq %d" % pid, "/FO", "CSV", "/NH"], capture_output=True,
                                     text=True, creationflags=0x08000000, timeout=15).stdout.lower()
                if "aria2c" in out:
                    subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True,
                                   creationflags=0x08000000, timeout=15)
                    log.info("stopped leftover torrent engine (pid %d)", pid)
            else:
                cmd = Path("/proc/%d/cmdline" % pid).read_bytes() if Path("/proc/%d" % pid).exists() else b""
                if b"aria2c" in cmd and b"--enable-rpc" in cmd:
                    os.kill(pid, 9)
                    log.info("stopped leftover torrent engine (pid %d)", pid)
        except Exception:
            pass
        try:
            pidf.unlink()
        except OSError:
            pass

    async def _alive(self):
        try:
            v = await self.call("getVersion", timeout=3)
            self.version = v.get("version", "")
            return True
        except Exception:
            return False

    async def call(self, method, *params, timeout=15):
        if self.session is None or self.session.closed:
            self.session = aiohttp.ClientSession(trust_env=False)
        body = {"jsonrpc": "2.0", "id": secrets.token_hex(4), "method": "aria2." + method,
                "params": ["token:" + self.secret] + list(params)}
        async with self.session.post(f"http://127.0.0.1:{self.port}/jsonrpc", json=body,
                                     timeout=aiohttp.ClientTimeout(total=timeout)) as r:
            j = await r.json(content_type=None)
        if "error" in j:
            raise TorrentError(j["error"].get("message", "aria2 error"))
        return j.get("result")

    async def set_limits(self):
        if not self.running():
            return
        cfg = self.get_cfg()
        opts = {"max-overall-download-limit": str(int(cfg.get("speed_limit_kb", 0) or 0) * 1024)}
        try:
            await self.call("changeGlobalOption", opts)
        except Exception:
            pass

    async def _stop_proc(self):
        if self.proc is not None and self.proc.poll() is None:
            try:
                await self.call("shutdown", timeout=3)
            except Exception:
                pass
            for _ in range(30):
                if self.proc.poll() is not None:
                    break
                await asyncio.sleep(0.1)
            if self.proc.poll() is None:
                try:
                    self.proc.kill()
                except OSError:
                    pass
        self.proc = None

    async def stop(self):
        await self._stop_proc()
        if self.session is not None:
            await self.session.close()
            self.session = None
