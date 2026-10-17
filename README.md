# S-Suite

A collection of lightweight applications built under one ecosystem.

## 📱 Apps

### 📓 S Notes

A lightweight, high-performance note-taking app built around a custom drawing canvas.

**Highlights**
- Infinite and multiple-infinite notebook pages
- Handwriting and freehand drawing
- Pen, eraser, stroke eraser and laser pointer
- Shapes with resize and rotation
- Text and image insertion
- Object selection and editing
- Undo and redo
- Light and dark themes
- Presets
- PDF, JPG and PNG export
- Portable `.hesi` notebook format
- Share and open `.hesi` notebooks between devices
- Portrait and landscape layouts
- Local notebook storage

**Requirements**
- Android 12 or newer

[Open S Notes](./SNotes)

---

### 🔋 S Charge

A lightweight battery monitoring and smart charging app focused on local battery telemetry, analysis and optional charging control.

**Highlights**
- Real-time battery monitoring
- Battery percentage and precise battery telemetry
- Voltage, current, power and temperature
- Charging and discharging monitoring
- Battery health and actual capacity analysis
- Battery capacity calibration
- Charging-session monitoring and notifications
- Background charging monitoring
- Battery analytics and live graphs
- Rooted and unrooted device support
- Smart Charge on supported rooted devices
- Light and dark themes
- Local-first operation
- No internet connection required

**Requirements**
- Android 8.0 or newer
- Root is required only for Smart Charge
- Smart Charge also requires compatible kernel charging-control support

[Open S Charge](./SCharge)

---

### 📋 S Log

A habit and daily-log tracker with exact reminders, on-device AI messages, history-safe streaks and live sync between Android and the web.

**Highlights**
- Logs with multiple daily times and per-time ticks
- Max, Monthly and Current streaks (partly done days count)
- History-safe: adding logs or times never changes earlier days
- Animated milestone popups (3, 10, 50, 100 ... 365 days)
- Exact reminders with Zomato-style, Gen Z AI messages generated on the device
- Congrats notification when a task is done before its reminder
- Daily summary of what's left
- Calendar with week / month views
- Share logs as `.csv` / `.slog` files and import with preview
- Live sync between phone and laptop, works offline
- Web version for laptops
- Nothing-style dot-matrix design

**Requirements**
- Android 8.0 or newer
- A Firebase project (free Spark plan) for accounts and sync, see `SLog/SETUP.md`

[Open S Log](./SLog)

---

### 🎬 S-MediaReconstruct

A lightweight Windows media reconstruction and normalization tool designed to rebuild fragmented video and audio into a continuous, playable media file.

**Highlights**
- Reconstruct fragmented `.exo` media caches
- Continuous H.264 video reconstruction
- Continuous AAC audio reconstruction
- Copy-first workflow to avoid unnecessary re-encoding
- NORMALIZE ALL workflow for incompatible media
- Automatic video and audio stream detection
- Support for mixed and separate A/V clip layouts
- Random preflight tests before full processing
- Boundary and beep artifact detection
- Local boundary repair when required
- Hardware-accelerated H.264 encoding with NVIDIA NVENC when available
- CPU encoding fallback
- MKV and MP4 output
- Dynamic clip-duration handling
- Disk-space preflight protection
- Processing workspace stored inside the selected input folder
- Automatic cleanup of processing intermediates after completion, cancellation or failure
- Source media is never deleted
- Local-first operation
- No cloud account required

**Requirements**
- Windows 10 or newer
- FFmpeg and FFprobe are bundled with the packaged Windows release
- NVIDIA GPU is optional; supported NVIDIA hardware enables NVENC acceleration

[Open S-MediaReconstruct](./SMediaReconstruct)

---

### 📁 S Drive

A file manager, music/video player and transfer app for compatible Android phones and Wear OS watches, with a browser-based file interface hosted locally by the phone or watch.

**Highlights**
- File manager with copy, cut, paste, rename, delete and multi-select actions
- Category views for Downloads, Images, Videos, Audio, Documents, Apps and Archives
- Video player with seeking gestures, speed control, subtitles, repeat/shuffle and sleep timer
- Background music playback and software volume boost up to 300%
- Shrink videos on the phone for smoother playback on a watch
- Transfer files between watch and phone over Wi-Fi or Bluetooth
- Browser-based PC upload/download page served by the device
- Six-digit transfer PIN and an ON/OFF switch for transfers
- Share files from other phone apps using **Share → S Drive**
- Nothing-style black/white/red design with dot-matrix branding
- Local-first operation; no cloud file-storage account required

**Requirements**
- Android phone: Android 10 or newer
- Compatible Wear OS watch: minimum Android API 30
- Wi-Fi recommended for faster transfers; Bluetooth is available as a fallback

[Open S Drive](./SDrive)

---

### ⬇️ SDM — S Download Manager

A Windows download manager with parallel and resumable downloads, torrent and magnet-link support, video downloads, and browser integration.

**Highlights**
- HTTP, HTTPS and FTP downloads with up to 256 parallel connections per file
- Resumes interrupted downloads and supports per-download connection limits
- Built-in BitTorrent support for `.torrent` files and magnet links, powered by aria2
- Video pages and streams through yt-dlp, including supported HLS and DASH sources
- Browser extension integration for handing downloads to SDM, including supported logged-in downloads
- Download queue, retry controls, download prioritization and total speed limits
- System-tray controls for opening SDM, pausing/resuming downloads and quitting
- Nothing OS–inspired interface

**Requirements**
- Windows for the packaged launcher and installer
- Python 3.11 or newer when running from source
- Chrome or Edge extension installation is optional

[Open SDM](./SDM)

---

## 🚀 Releases

Each app has its own GitHub release so the apps can be updated independently.

- **S Notes** — download the latest APK from the S Notes release.
- **S Charge** — download the latest APK from the S Charge release.
- **S Log** — download the latest APK from the S Log release.
- **S-MediaReconstruct** — download the latest Windows executable from the S-MediaReconstruct release.
- **S Drive** — distribute the Android phone and Wear OS APKs together in the S Drive release so their application ID and signing setup remain compatible for phone/watch communication.
- **SDM** — download the latest Windows release ZIP, which includes the launcher and torrent engine.

See the repository's **Releases** section for available versions.

---

## 🔒 Privacy

S-Suite is designed with a local-first approach.

S Notes, S Charge and S-MediaReconstruct do not require a cloud account for their core functionality. S Drive transfers files locally over Wi-Fi or Bluetooth and serves its browser interface from the phone or watch; use its local HTTP page only on a trusted network and keep the transfer PIN private.

SDM runs its download interface locally on `127.0.0.1` and uses a browser helper for optional browser integration. Review extension permissions and keep the local interface on the intended device.

The exception is **S Log**, which syncs logs between devices: it uses an account and stores logs in your own Firebase project, protected by database rules and a registration PIN. It still works offline.

---

## 🛠️ Repository Structure

```text
S-Suite/
├── SNotes/
├── SCharge/
├── SLog/
├── SMediaReconstruct/
├── SDrive/
├── SDM/
├── .gitignore
└── README.md
```

Each application is maintained as a separate project with its own source code, resources, build configuration and release cycle.

## 📄 License

S-Suite-wide licensing information will be added when the ecosystem licensing terms are finalized. SDM includes its own MIT License; see `SDM/LICENSE` and each project’s license file where present.
