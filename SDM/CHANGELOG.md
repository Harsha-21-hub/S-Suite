# Changelog

## 2.1.1
- **Open .torrent files from your PC**: the new **.TORRENT** button next to the link bar, drag & drop onto the
  window, or Ctrl+V of a copied file. Several files at once work; name and size show straight away.
- **Task Manager shows "SDM - S Download Manager"** (with the SDM icon) instead of "Python": SDM.exe now runs
  the engine inside its own process (Python 3.11+; older or unusual Python setups keep the previous start).
  The browser helper starts SDM.exe too.
- Fix: torrents no longer leave an extra "<long hash>.torrent" file in the download folder.

## 2.1.0
- **Faster engine for 1 GB/s-class lines**: connections spread over several CPU cores (network lanes),
  1 MB network reads, zero-copy buffering with disk writes running while the next data arrives
  (about 2x the previous throughput per core in tests).
- Up to **256 connections** per file; memory per connection shrinks automatically with very many connections.
- Speed limit is now exact for any number of connections and holds from the first second.
- **Clear** menu: clear done / failed / both. Clearing never deletes downloaded files; finished torrents
  stop seeding. New **Stop seeding** action, **Retry** for failed downloads, **Retry all**, queue positions.
- Settings reorganised into tabs (General, Speed, Network, Browser, Torrents, Look & about); speed limit
  shows its value in MB/s and Mbps; keyboard focus rings; the Browser link pill opens browser settings.
- Torrent save dialog: BT tile, read-only torrent name.
- Moving or renaming the SDM folder: shortcuts repair themselves on the next start.
- Fix: removing a download and adding the same link right away could delete the new download's file.

## 2.0.0 — SDM
- Renamed FastDL to **SDM — S Download Manager**; first start migrates settings, history and unfinished downloads from FastDL.
- **Torrents & magnet links** (built-in aria2 engine: DHT, PEX, local discovery, daily public tracker list, up to 200 peers).
- **System tray**: closing the window keeps SDM running; quit from the tray. No start at sign-in unless enabled.
- Full speed in the background (opts out of Windows efficiency throttling) and no sleep while downloading.
- **Download first** priority, elapsed time per download, free space for every drive, clear recent folders.
- Connection limits cut by a server recover automatically; fix: quitting no longer marked running downloads as failed.

## 1.2.0
- Large files are pre-sized instantly (no zero-filling), file work moved off the engine loop, auto-replace of old/frozen engines.

## 1.1.0
- Native launcher, self-checking dependencies, drive-aware free space, fixed save dialog re-opening.

## 1.0.0
- Multi-connection engine, Nothing OS–style UI, browser extension, FTP, DNS-over-HTTPS fallback, yt-dlp media.
