# 📁 S Drive

S Drive is a lightweight file manager, music and video player, and file-transfer app for compatible Android phones and Wear OS watches. It also includes a browser-based interface served directly by the phone or watch for transferring and managing files from a PC.

S Drive is part of the **S-Suite** ecosystem and uses a compact black, white and red interface with a dot-matrix **S DRIVE** identity. The Android phone app, watch app and shared code live in one Android Studio project.

---

## ✨ Features

### 📂 File Manager & Categories

- Browse folders and files in device storage.
- Open category views for **Downloads, Images, Videos, Audio, Documents, Apps and Archives**.
- Browse category items grouped by their parent folders; view thumbnails where available.
- Long-press to select files, then **Send, Share, Copy, Cut, Paste, Rename, Select all** or **Delete**.
- Sort files by name, newest or largest.
- On supported watches, use the bezel/crown to scroll.

---

### 🎬 Video Player

- Uses Android Media3 / ExoPlayer and the device's available hardware decoding.
- Tap to show or hide controls; double-tap to seek backward or forward, or the centre to play/pause.
- Swipe horizontally to seek with a live preview.
- Adjust volume and brightness with vertical gestures; pinch to zoom/pan.
- Playback speed from **0.25× to 4×**, aspect modes (**Fit, Fill, Stretch**), repeat/shuffle, A–B repeat and sleep timer.
- Supports embedded audio tracks and subtitles, including matching `.srt`, `.vtt` and `.ass` files where supported by the player.
- Remembers playback position.
- On the phone, use **Shrink videos for watch** to create a smaller H.264 version for files that are too demanding for the watch to decode smoothly.

**Watch playback note:** the maximum smooth resolution and frame rate depend on the watch's decoder and the file. High-resolution or high-frame-rate videos may need to be shrunk on the phone first.

---

### 🎵 Audio Player

- Background audio playback with notification and media controls.
- Shuffle, repeat, A–B repeat, sleep timer and playback-speed controls.
- Software volume boost up to **300%**, with a limiter in the audio pipeline.
- Additional formats are supported through the bundled Media3 FFmpeg audio decoder; playback still depends on the specific file and device.

---

### 🔄 Transfers & Sharing

Transfer files between the watch, phone and PC without using a cloud file-storage service.

| From → To | How it works |
|---|---|
| Watch ↔ phone | Transfer screen over the same Wi-Fi network; Bluetooth is available as a fallback. |
| PC → watch/phone | Open the local address shown by S Drive in a PC browser, enter the PIN, then upload files or folders. |
| Watch/phone → PC | Download files from the browser page or use **Offer to PC browser** to send selected files to the open PC page. |
| Phone → watch from another app | Choose **Share → S Drive**, then select the watch. |
| S Drive → another app | Use Android's share sheet to send files to compatible apps. |

- Received files are saved under `Download/S-Drive/` on Android devices and `Downloads\S-Drive\` on the PC.
- The PC page is served locally by the phone or watch; it does not need a separate installation.
- Transfers have a **6-digit PIN**. After eight incorrect attempts, access is locked for 60 seconds.
- A **Transfer ON/OFF** switch controls whether the app can send or receive.
- During transfers, S Drive manages the Wi-Fi connection, wake lock and foreground service as needed, then releases resources when the work finishes.
- Transfer speed varies with signal strength, Wi-Fi band, Bluetooth connection and device hardware.

---

### 💻 Built-in PC Browser Interface

- Open the URL shown on the S Drive Transfer screen in a browser on a laptop, desktop or tablet.
- Enter the displayed PIN to access the local transfer page.
- Browse files and categories, upload files/folders to the device, and download files to the PC.
- The page is bundled in `shared/src/main/assets/web/index.html`; no separate web build step is required.
- The PC companion scripts are in `pc/`.

The browser page uses a local HTTP address. Use it on a trusted local network and do not share the URL or PIN publicly.

---

### 📱 Phone Terminal & PC Tools

- The phone app includes a terminal for pairing with the watch over Android Wireless Debugging and running setup or diagnostic commands.
- The terminal can help with device information, file access setup, notifications, storage, battery information and installing an APK on the watch.
- `pc/S-Drive-PC.bat` opens the PC interface and runs the companion receiver.
- `pc/S-Drive-Tools.bat` provides pairing, connection, device information, setup and shell tools.
- **Wireless debugging is not required for normal file transfers.** It is only used for certain one-time setup and device-management tasks.

---

## 📦 Requirements

- **Android phone:** Android 10 or newer (minimum SDK 29).
- **Wear OS watch:** a compatible watch running Android API 30 or newer (minimum SDK 30); developed with Galaxy Watch 6 in mind.
- **PC browser:** a modern browser and network access to the local address displayed by the phone or watch.
- **Recommended transfer method:** Wi-Fi. Bluetooth can be used when Wi-Fi is unavailable, at lower speeds.
- **Build environment:** Android Studio, JDK 17 and Android SDK 35.

---

## 🔐 Permissions & Access

| Permission / setting | Why it is used |
|---|---|
| All files access | Browse, organize, play and transfer files outside the app's private folder. |
| Notifications | Show transfer and media playback notifications. |
| Background / battery settings (optional) | Improve reliability during long transfers and background playback. |
| Network / Wi-Fi access | Discover connected devices and host the local browser transfer page. |
| Bluetooth / Wearable connection | Transfer files between a paired phone and watch when supported. |

### All-files access on a watch

If the watch does not show an all-files-access settings page, connect it to ADB and run this once from your PC:

```bash
adb shell "appops set --uid com.sdrive.app MANAGE_EXTERNAL_STORAGE allow"
```

Without this access, Android may restrict the app to its own app-specific storage directory.

---

## 🛠️ Technical Specs

- **Application ID:** `com.sdrive.app` (shared by the phone and watch apps for Wearable Data Layer communication).
- **Module namespaces:** `com.sdrive.mobile`, `com.sdrive.wear` and `com.sdrive.shared`.
- **Minimum SDK:** phone API 29; watch API 30.
- **Compile SDK:** API 35.
- **Target SDK:** API 34.
- **Language:** Kotlin.
- **UI:** Jetpack Compose Foundation.
- **Playback:** AndroidX Media3 / ExoPlayer with a Media3 FFmpeg audio decoder.
- **Transfer:** local HTTP browser interface, Wi-Fi networking and Android Wearable Data Layer / Bluetooth communication.
- **Web UI:** bundled HTML, CSS and JavaScript; no separate build step.
- **Backend / cloud:** no cloud account or cloud file-storage backend is required for transfers.
- **PC tools:** Windows batch and PowerShell scripts in `pc/`.

---

## 🔒 Privacy & Safety

- S Drive is designed for local file management and device-to-device transfers; it does not use a cloud file-storage backend for those transfers.
- The browser interface is hosted by the phone or watch and protected by the displayed PIN.
- The browser page uses local HTTP, not a public cloud endpoint. Use it on a trusted network and keep the transfer PIN private.
- Keep your signing keys and machine-specific files such as `local.properties` out of GitHub. They are local configuration and are excluded by `.gitignore`.

---

## 🚀 Build

1. Open the `SDrive/` folder in Android Studio and allow Gradle to sync. The first sync downloads project dependencies.
2. Build the phone and watch apps from the project root:

   **Windows PowerShell:**

   ```powershell
   .\gradlew.bat :mobile:assembleDebug :wear:assembleDebug
   ```

   **macOS / Linux:**

   ```bash
   ./gradlew :mobile:assembleDebug :wear:assembleDebug
   ```

3. Install the phone and watch builds created from the same project/signing setup. The phone and watch share `applicationId` `com.sdrive.app`; compatible signatures are required for the Wearable Data Layer connection.
4. For optional first-time watch setup, enable Developer options and Wireless debugging on the watch, then use `pc/S-Drive-Tools.bat` or the phone terminal as needed. Normal file transfers work without leaving Wireless debugging enabled.

> **Signing note:** the current Gradle configuration uses the debug signing configuration for release variants so the phone and watch builds can share a signature. Set up your own private release-signing process before distributing production releases, and never commit a keystore or signing credentials.

---

## 🧩 Project Structure

```text
SDrive/
├── mobile/                         # Android phone app and video shrinker
├── wear/                           # Wear OS watch app shell
├── shared/                         # Shared UI, file operations, playback and transfers
│   └── src/main/
│       ├── assets/web/index.html   # Built-in browser transfer interface
│       ├── java/com/sdrive/shared/ # Shared Kotlin code
│       └── res/                    # Shared icons, font and resources
├── pc/                             # Windows launcher and support scripts
├── gradle/wrapper/                 # Gradle wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── .gitignore
└── README.md
```

---

## 🌐 S-Suite

S Drive is one application in the **S-Suite** ecosystem.

[Open S-Suite](../)
