# S Drive — file manager + music/video player + file transfer for Galaxy Watch 6

(Project folder and package id are still `SDrive` / `com.sdrive.app`, so permissions you already granted keep working.)

One Android Studio project, three pieces with **the same design**:

| Module | Runs on | What it is |
|---|---|---|
| `wear` | Galaxy Watch 6 (Wear OS 4/5/6) | File manager, audio + video player, send/receive |
| `mobile` | Your Android phone | Same app for the phone (+ "shrink video for watch", share-to-watch) |
| built-in web page | Any PC / laptop / tablet browser | Served by the watch or phone itself — nothing to install |

---

## 1. Build & install

1. **Open** the `SDrive` folder in Android Studio (Ladybug or newer). Let Gradle sync (first sync downloads ~300 MB of libraries).
2. **Watch: turn on developer mode** – Settings → About watch → Software → tap *Software version* 5×.
   Then Settings → Developer options → **ADB debugging ON** and **Wireless debugging ON**.
3. In Android Studio: device dropdown → **Pair Devices Using Wi-Fi** → enter the pairing code shown on the watch.
4. Pick run configuration **wear**, select the watch, press ▶ Run.
5. Pick run configuration **mobile**, select your phone (USB or Wi-Fi), press ▶ Run.

> Build both apps on the same PC (same debug key) — the Bluetooth link only works when the watch and phone apps share the same `applicationId` **and** signing key. Release builds are also signed with the debug key for that reason.

For the smallest/fastest app use **Build → Select Build Variant → release** (R8 shrinking on).

### Allow access to all files (watch)
The app asks for *All files access* on first launch. Galaxy Watches sometimes have no settings page for it — then run this once from your PC (with the watch connected over ADB):

```
adb shell "appops set --uid com.sdrive.app MANAGE_EXTERNAL_STORAGE allow"
```

Without it the app still works but only inside its own folder (`Android/data/com.sdrive.app/files`).

---

## 2. Using it

### File manager
* Tap folder = open, **BACK key** = up one folder (swipe-to-close is disabled on purpose so swipes can seek video).
* **Long-press** = select. Then: Send · Share · Copy · Cut · Rename · Select all · Delete.
* Copy/Cut → open target folder → **Paste**. Sort by name / newest / largest. Rotate the bezel/crown to scroll.

### Video player (60 fps)
* Hardware decoder → `SurfaceView` (zero-copy, no GPU composition) → frames released on the display's VSYNC. This is the smoothest and lowest-power path Android has.
* Gestures: **tap** show/hide · **double-tap left/right** −10 s / +10 s · **double-tap centre** play/pause · **swipe ←→** scrub with live preview · **swipe ↑↓ right side** volume (up to 300 %) · **swipe ↑↓ left side** brightness · **pinch** zoom/pan · **hold** = 2× speed while held · **bezel/crown** = volume.
* Buttons: previous/next file, −10/+10, play, seek bar, **lock screen**, speed (0.25×–4×), aspect (Fit/Fill/Stretch), **⋮ more**.
* ⋮ panel: volume 0–300 %, speed, zoom, audio track, subtitles (embedded or `.srt/.vtt/.ass` with same name), subtitle size, repeat/shuffle, **A-B repeat**, **sleep timer**, playback info (resolution, fps, decoder, dropped frames).
* Phone extras: rotate button, picture-in-picture when you leave the app.
* Resumes where you left off.

**About 60 fps:** the player itself never adds stutter, but the watch's hardware decoder has limits. Smooth on the Watch 6: up to **720p60** (H.264/H.265) or **1080p30**. A 1080p60/4K file is more than the watch chip can decode in real time → on the phone, turn on **"Shrink videos for watch"** when sending (re-encodes to 480p H.264 on the phone's hardware encoder, keeps the frame rate, and the file becomes 5–10× smaller). The info panel shows "dropped frames" so you can verify.

### Audio player + 300 % volume
* 0–100 % drives the normal media volume; 100–300 % adds a software boost inside the audio pipeline (like VLC) with a soft limiter so it doesn't crackle.
* Keeps playing with the screen off / app closed (media controls in notifications & on the watch face).
* Shuffle, repeat, A-B repeat, sleep timer, speed.

### Transfer — every direction

| From → To | How | Speed (typical) |
|---|---|---|
| Watch ↔ phone (same Wi-Fi) | Both open **Transfer**. Select files → **Send** → tap the device under *Wi-Fi*. Or on Transfer tap the other device to browse it and pull files. | 3–10 MB/s |
| Watch ↔ phone (no Wi-Fi) | **Send** → *Bluetooth* section | 100–300 KB/s |
| PC → watch/phone | Open **Transfer** on the watch → type the shown `http://IP:8080` in the PC browser → enter PIN → drag & drop files/folders | Wi-Fi speed |
| Watch/phone → PC | In the PC page click any file to download, **or** on the watch select files → **Send → Offer to PC browser** (the open PC page downloads them automatically) | Wi-Fi speed |
| Phone → watch from any app | In Gallery/Files/Quick Share etc: **Share → S Drive** → pick the watch | — |
| Anything → other apps | **Send → Quick Share / Nearby / other apps** (Android share sheet) | — |

* Received files go to `Download/S-Drive/` (folders keep their structure).
* **Wi-Fi stays on during transfers**: the app requests a Wi-Fi network from Wear OS (this is what overrides the watch's "turn off Wi-Fi when connected to phone"), holds a high-performance Wi-Fi lock + wake lock, and runs as a foreground service. Everything is released automatically when transfers finish (or 15 min after the last activity in receive mode) to save battery.
* Two files transfer in parallel, 256 KB buffers, no extra copies.
* Every device has a 6-digit **PIN**; nothing on the watch is accessible without it (8 wrong tries → 60 s lock).

---

## 3. Why it's light on RAM / CPU / battery / storage
* No heavy libraries: own 400-line HTTP server, own icon set, plain Compose Foundation (no Material libs).
* One shared ExoPlayer with small buffers (4–15 s) for local files.
* Server, Wi-Fi lock and wake lock exist only while needed; discovery only runs while Transfer/Send is open.
* Release build: R8 code + resource shrinking.

## 4. Project layout
```
shared/   all logic + UI (used by both apps)
  Core.kt            app context, prefs, PIN
  Storage.kt         file ops, types, sizes
  WristServer.kt     HTTP server for PC/phone (range requests, uploads)
  Transport.kt       Wi-Fi client, Bluetooth (Data Layer) send/receive, queue
  Net.kt             Wi-Fi keep-alive, mDNS discovery
  TransferService.kt foreground service
  player/PlayerEngine.kt  ExoPlayer + 300 % gain + background playback
  ui/…               screens (browser, player, transfer, send, remote, image)
  assets/web/index.html   PC browser app
wear/     watch app shell
mobile/   phone app shell + video shrinker + share target
```

## 5. Known limits (honest list)
* Quick Share/Nearby Share does not exist on Wear OS itself, so the watch's share sheet only shows what the watch offers; use the phone share target or Wi-Fi instead.
* Formats: everything ExoPlayer supports (MP4, MKV, WebM, MOV, AVI, TS, MP3, AAC, FLAC, OGG/Opus, WAV…). WMV/WMA and DTS/AC3 audio aren't decoded by the watch hardware.
* If the watch isn't on Wi-Fi yet, the Transfer screen shows a **Wi-Fi settings** button; the watch must know the network once.

---

## 6. Update 1.1 — S Drive

Everything that worked before (file manager, players, gestures, transfers) is unchanged. Added on top:

* **Categories on the home screen** (top of Internal storage): Downloads, Images, Videos, Audio, Documents, Apps, Archives.
  Tap one → its files **grouped by folder** (e.g. every folder that has videos) → tap a folder → its files
  (thumbnails for pictures / videos / music). "All …" shows every file of that type. Long-press a folder to open it in the file browser.
* **Nothing-style look**: black, white and a red accent; dot-matrix "S DRIVE" title; dotted app icon (same icon as before). Sizes and layout are the same as before.
* **Smoother UI**: the bezel now scrolls smoothly instead of jumping; Compose baseline profiles are installed with the app.
  For the best result install the **release** variant, then run once:
  `adb shell cmd package compile -m speed -f com.sdrive.app` (done automatically by `S-Drive-PC.bat` or the phone Terminal on connect).
* **No sleeping during transfers**: the screen is kept on while a transfer runs (Transfer → *Keep screen on*, can be turned off),
  *Allow background running* button (battery optimisation exemption), Bluetooth receives also run as a foreground service.
* **Terminal in the phone app** (button with `>_` on the phone home): pair/connect to the watch over Wireless debugging,
  run any shell command, one-tap: grant all-files access, never sleep, fix UI lag, allow notifications, watch IP, storage,
  battery, install APK on the watch, reboot. Also the phone's own shell.
* **PC** `pc/S-Drive-PC.bat` (double-click): opens the watch's S Drive page in the browser, no terminal menu.
  If the watch is paired over Wireless debugging it also opens Transfer on the watch and grants all-files access,
  notifications, never sleep and speed-compiles S Drive automatically.
  `pc/S-Drive-Tools.bat`: pair, connect, devices, set up now, install APK, shell, watch info + Wi-Fi link.
* **Help** (Transfer → *How to transfer*, and *? Help* on the PC page): every transfer direction + speeds.

## 7. Update 1.2

* **Left-edge swipe = back** on the watch.
* **Transfer page: Send / Receive tabs** (watch, phone and PC page). Receive shows the link + PIN and nearby devices;
  Send → *Choose files to send* → long-press files → Send.
* **PC page split in two**: left = storage used, Transfer (Send to watch / Receive from watch) and categories;
  right = internal storage folders (or the category folders / files you picked on the left).
* **Colours only**: black / white / red everywhere (no green); app icon dots recoloured, new monochrome icon (Android 13+ themed icons).
* **Permissions by default**: first launch asks for notifications and no battery limits; all-files access and speed-compile
  are applied automatically by `S-Drive-PC.bat` or the phone Terminal on connect.
* **adb push removed** everywhere; transfers use Wi-Fi or Bluetooth.

### How to transfer
| From → To | How |
|---|---|
| PC → watch | Double-click `pc\S-Drive-PC.bat` (or open the link shown on watch Transfer → Receive, enter the PIN). Page → Transfer → *Send to watch*: drag & drop or Upload. |
| Watch → PC | Watch: Transfer → Send → Choose files → long-press → Send → *Offer to PC browser*. Page → Transfer → *Receive from watch*: they download. Or click any file on the page. |
| Phone → watch | Watch: Transfer → Receive. Phone: Transfer → Send → Choose files → long-press → Send → the watch → watch PIN. Or Share → S Drive from any app. No Wi-Fi: Send → Bluetooth. |
| Watch → phone | Phone: Transfer → Receive. Watch: Transfer → Send → Choose files → long-press → Send → the phone. |

### Which is fastest?
| Method | Typical speed |
|---|---|
| S Drive over Wi-Fi (app ↔ app, web page) | fastest: up to ~4 MB/s on 2.4 GHz, more on 5 GHz with a strong signal |
| Bluetooth | ≈ 0.1–0.3 MB/s |

A weak signal (below about -70 dBm) slows Wi-Fi a lot: keep the watch near the router. Any file type can be transferred.

### Phone terminal: first connection
1. Watch: Developer options → **Wireless debugging** ON → **Pair new device**.
2. Phone: S Drive → `>_` → **Pair** → enter the IP:port and the 6-digit code shown on the watch.
3. **Connect** → the IP:port at the top of the Wireless debugging screen (not the pairing port). Setup runs automatically.

### If Gradle sync shows an error
* "Unable to load class …" / corrupt cache: run `.\gradlew --stop`, close Android Studio, end all Java processes, reopen and sync.
* JitPack (only needed for the phone terminal) unreachable: remove the 3 ADB lines in `mobile/build.gradle.kts`,
  delete `mobile/.../AdbManager.kt` and the line `Platform.adb = SDriveAdb` in `mobile/.../MainActivity.kt`.

## 8. Update 1.3

* **Debugging is NOT needed for transfers.** Wi-Fi and Bluetooth transfers work with Developer options OFF.
  Wireless debugging is only used ONCE after installing (all-files access + speed-compile): `pc\S-Drive-Tools.bat` → 1 Pair → 2 Connect,
  or phone Terminal → Connect. Then switch Wireless debugging / Developer options OFF again; payment apps work and the grants stay.
* **Smooth watch UI.** The watch build is no longer "debuggable" (that made Compose run several times slower on the watch CPU),
  and indexing / thumbnails run at low priority. Android Studio *Run* works as before. For the very best result pick the
  **release** variant (Build → Select Build Variant).
* **PIN entry on the watch** uses its own number pad (the watch keyboard was dropping digits).
* **More formats.** FFmpeg audio decoder added (AC-3, E-AC-3, DTS, TrueHD, Vorbis… common in MKV/AVI/TS), plus .mts/.m2ts/.vob/.divx
  video and .ac3/.eac3 audio. Videos the watch chip can't decode (e.g. HEVC 10-bit/4K) show a clear message; the PC page says when
  the browser can't play a format.
* **Transfer: ON / OFF** switch on the watch, the phone (Transfer screen) and the PC page. OFF = nothing can be sent or received.
* **Received files go to `Download/S-Drive`** on the watch and the phone, and to **`Downloads\S-Drive`** on the PC.
  (Files received earlier stay in the old `Download/S Drive` folder.)
* **PC: `S-Drive-PC.bat` runs without a window.** It opens the page and keeps running quietly as the PC receiver that saves
  files into `Downloads\S-Drive` (files the watch sends to the PC, and files you click to download on the page).
  **Stop** on the page closes the watch's port and exits it; double-click the .bat again to start.
  If the watch isn't found, a small Retry / Cancel box appears.
