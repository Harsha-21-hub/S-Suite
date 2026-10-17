# SDM — S Download Manager

**A Windows download manager for parallel HTTP/HTTPS/FTP downloads, torrents and magnet links, video pages and streams, with browser integration and a Nothing OS–inspired interface.**

SDM brings downloads together in one desktop application, with resumable transfers, configurable connections, a queue, and system-tray controls.

---

## Features

- Windows desktop application with a Nothing OS–inspired interface.
- Parallel HTTP/HTTPS/FTP downloads with configurable per-file connection limits.
- Resume support for interrupted downloads when supported by the server.
- BitTorrent support for `.torrent` files and magnet links through aria2.
- Video-page and stream downloads through `yt-dlp` for supported sites.
- Browser integration through the SDM Catcher extension and local helper.
- Download queue with pause, resume, retry and prioritization controls.
- Configurable speed limits and per-download destination folders.
- System-tray operation with open, pause, resume and quit controls.
- Optional startup and background operation.
- Settings and history stored locally under `%APPDATA%\SDM`.
- Local helper bound to `127.0.0.1`; requests from web pages are rejected.
- Installer scripts to help configure the application and browser integration.

---

## Download Types

### 1. HTTP / HTTPS / FTP

SDM supports direct file downloads with configurable parallel connections and resume support where the server allows it.

```text
Add URL
  ↓
Choose destination
  ↓
Start download
  ↓
Monitor progress
  ↓
Completed file
```

### 2. Torrents and Magnet Links

SDM uses aria2 for BitTorrent downloads.

- Open `.torrent` files with the torrent control or drag them into the application.
- Add magnet links.
- Configure torrent options in Settings.
- Enable **Open magnet links with SDM** to make SDM handle magnet links by default.

Peer availability and download speed depend on the torrent swarm, network and firewall configuration.

### 3. Video Pages and Streams

SDM uses `yt-dlp` for supported video sites and media formats, including supported HLS and DASH sources. Results depend on the website, link validity, authentication and downloader capabilities.

### 4. Browser Downloads

The SDM Catcher extension can hand supported browser downloads to SDM, including cookies where supported. If SDM is unavailable, the browser can continue downloading normally.

---

## Using SDM

- Paste links with `Ctrl+V` or drop them into the application.
- Use the torrent control or drag a `.torrent` file into the window.
- Select a destination folder for each download.
- Use **Download first** to prioritize a queued item.
- Retry failed items from the Failed view.
- Configure connection counts and speed limits in Settings.
- Clearing completed items from the list does not delete downloaded files from disk.

---

## Windows Installation

1. Download the latest Windows ZIP from the project's GitHub Releases page.
2. Extract it to a permanent folder, for example `D:\Softwares\SDM`.
3. Run `install.bat` once to configure the application, dependencies and available browser integration.
4. To install the unpacked browser extension, open `chrome://extensions` in Chrome or `edge://extensions` in Edge.
5. Enable **Developer mode**, select **Load unpacked**, and choose the project's `extension` folder.

Keep the SDM folder in a stable location. If you move or rename it, quit SDM first and launch `SDM.exe` from the new location so shortcuts and browser integration can be refreshed. Reload the unpacked extension if its folder path changed.

---

## Run From Source

```powershell
git clone <repository-url> SDM
Set-Location SDM
python -m pip install -r requirements.txt
python sdm.py
```

Use the Python version specified by the current project configuration or release instructions. On Windows, the torrent engine can download itself when required; on Linux or macOS, install aria2 through the platform's package manager if using the source there.

---

## Build the Launcher

The repository includes launcher source and resource files. The Windows launcher build uses `ziglang` and the Windows GNU target.

```powershell
python -m pip install ziglang
Set-Location launcher
Copy-Item ..\sdm.ico .
python -m ziglang cc -target x86_64-windows-gnu -O2 -municode -Wl,--subsystem,windows -s sdm_launcher.c sdm.rc -o ..\SDM.exe -luser32 -lshell32 -lkernel32
```

The project release workflow can build and publish Windows release artifacts when a version tag is pushed. Refer to the repository's current workflow and release instructions for exact versioning details.

---

## Starting and Closing SDM

- Launch `SDM.exe` or the SDM shortcut.
- Closing the main window can leave SDM running in the system tray.
- Use the tray menu to reopen SDM, pause or resume downloads, or quit.
- SDM does not start with Windows unless startup is enabled in Settings.
- Startup checks can identify missing packages and refresh the browser helper after the application folder is moved.

---

## Moving From FastDL

SDM is the new name of FastDL. On first start, SDM may take over from a running FastDL installation and migrate supported settings, history and unfinished downloads. Verify migrated items in SDM before removing any previous installation.

---

## Project Structure

```text
SDM/
├── SDM.exe
├── sdm.py
├── sdm_torrent.py
├── sdm_tray.py
├── ui/
├── extension/
├── native_host.py
├── install.bat
├── uninstall.bat
└── README.md
```

The complete project may include additional source, configuration, asset, workflow and third-party notice files. Preserve the project's existing structure when working with the repository.

---

## Settings and Data

SDM stores settings and history under:

```text
%APPDATA%\SDM
```

Downloaded files are saved to the destination selected for each download. Removing completed entries from SDM's list does not remove those files from disk.

---

## Troubleshooting

- **Browser reports that the extension is not seen:** Check that the extension is loaded or reloaded and that SDM's local helper is installed.
- **Torrent is stuck on “Looking for peers”:** The torrent may have no active seeds. If prompted, allow SDM or aria2 through Windows Firewall.
- **Magnet links open another application:** Enable **Open magnet links with SDM** in Settings → Torrents.
- **A video or website link returns an error such as 403 or 410:** The link may have expired or require renewed access. Try capturing the link again from the browser.
- **Browser integration stops working after moving SDM:** Start SDM from its new location and reload the unpacked extension if its path changed.

---

## Privacy and Network Behavior

SDM is designed to run locally. Its local helper listens on `127.0.0.1` and rejects requests from web pages. Browser integration may pass download information, including cookies where supported, to the local SDM helper so protected downloads can work. Review extension permissions and only use the integration with websites you trust.

---

## Third-Party Components

SDM uses third-party components, including aria2, `yt-dlp`, `aiohttp`, `pystray` and Pillow. Bundled aria2 is licensed under GPL-2.0. Font assets include Doto, Space Grotesk and Space Mono under the SIL Open Font License. See the project's bundled notices and license files for applicable terms.

---

## License

SDM is released under the MIT License. Bundled aria2 is licensed under GPL-2.0; see `bin\aria2-COPYING.txt` where included.
