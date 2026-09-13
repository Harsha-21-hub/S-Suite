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

## 🚀 Releases

Each app has its own GitHub release so the apps can be updated independently.

- **S Notes** — download the latest APK from the S Notes release.
- **S Charge** — download the latest APK from the S Charge release.
- **S-MediaReconstruct** — download the latest Windows executable from the S-MediaReconstruct release.

See the repository's **Releases** section for the latest versions.

---

## 🔒 Privacy

S-Suite is designed with a local-first approach.

The applications do not require a cloud account for their core functionality, and normal app data is kept on the device.

---

## 🛠️ Repository Structure

```text
S-Suite/
├── SNotes/
├── SCharge/
└── SMediaReconstruct/

Each application is maintained as a separate Android project with its own source code, resources, Gradle configuration and release cycle.

## 📄 License

License information will be added when the S-Suite licensing terms are finalized.
