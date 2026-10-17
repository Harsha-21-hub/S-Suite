"""
SDM system-tray icon (Windows).

Closing the SDM window leaves the engine running here. The icon shows live speed
in its tooltip; its menu opens the window, pauses/resumes everything, or quits.
"""
import asyncio
import logging
import threading
import time

log = logging.getLogger("sdm")


def _fmt_speed(bps):
    for unit in ("B/s", "KB/s", "MB/s", "GB/s"):
        if bps < 1024 or unit == "GB/s":
            return ("%.0f %s" if unit in ("B/s", "KB/s") else "%.1f %s") % (bps, unit)
        bps /= 1024.0
    return ""


class Tray:
    def __init__(self, engine, icon_path, open_window, quit_coro):
        self.engine = engine
        self.icon_path = icon_path
        self.open_window = open_window
        self.quit_coro = quit_coro
        self.icon = None
        self.loop = engine.loop

    def start(self):
        try:
            import pystray
            from PIL import Image
        except Exception as e:  # not installed yet / no desktop session
            log.info("tray icon unavailable: %s", e)
            return False
        try:
            image = Image.open(self.icon_path)
            image.load()
        except Exception as e:
            log.warning("tray icon image: %s", e)
            return False

        def on_open(icon, item):
            self.open_window()

        def on_pause(icon, item):
            self.loop.call_soon_threadsafe(self.engine.pause_all)

        def on_resume(icon, item):
            self.loop.call_soon_threadsafe(self.engine.resume_all)

        def on_quit(icon, item):
            try:
                icon.visible = False
            except Exception:
                pass
            asyncio.run_coroutine_threadsafe(self.quit_coro(), self.loop)
            icon.stop()

        menu = pystray.Menu(
            pystray.MenuItem("Open SDM", on_open, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Pause all", on_pause),
            pystray.MenuItem("Resume all", on_resume),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit SDM", on_quit),
        )
        self.icon = pystray.Icon("SDM", image, "SDM", menu)
        threading.Thread(target=self._run, name="sdm-tray", daemon=True).start()
        threading.Thread(target=self._titles, name="sdm-tray-title", daemon=True).start()
        return True

    def _run(self):
        try:
            self.icon.run()
        except Exception as e:
            log.warning("tray icon stopped: %s", e)

    def _titles(self):
        last = ""
        while True:
            time.sleep(2)
            try:
                active = sum(1 for i in list(self.engine.items.values()) if i.state == "downloading")
                if active:
                    title = "SDM - %s - %d downloading" % (_fmt_speed(self.engine.speed), active)
                else:
                    title = "SDM - idle (running in background)"
                if title != last and self.icon is not None:
                    self.icon.title = title[:127]
                    last = title
            except Exception:
                pass

    def stop(self):
        try:
            if self.icon is not None:
                self.icon.stop()
        except Exception:
            pass
