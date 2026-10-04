# 📋 S Log

S Log is a habit and daily-log tracker developed as part of the **S-Suite** ecosystem.

It tracks habits with multiple daily times, exact reminders with on-device AI messages, history-safe streaks, a dot-matrix (Nothing-style) interface, and live sync between the Android app and a web version for laptops.

S Log works offline and syncs automatically when the internet is back.

---

## ✨ Features

### ✅ Logs & Daily Ticks

Each log (habit) has one or more times in the day, e.g. **Gym** at 7:00 AM and 7:00 PM.

- Tick each time when it's done
- Today and the previous two days can be edited
- Add, edit, rename, reorder (drag) and switch logs on/off
- A log added on an earlier day starts on that day and can be ticked from then on
- "LEFT TODAY" line shows what's still pending

---

### 🔥 Streaks

Three streaks for all logs together and for every single log, shown with dot-matrix icons:

| Streak | Meaning |
|---|---|
| **Max** (crown) | Best run ever, saved in the database, never lost |
| **Monthly** (calendar) | Best run inside the month on screen |
| **Current** (flame) | Run of days up to today |

- A log counts for a day when at least one of its times is ticked (partly done counts)
- The whole day counts when every log that was due is at least partly done
- An animated popup celebrates Max-streak milestones: 3, 10, 50, 100, 150, 200, 250, 300, 365 days and beyond

---

### 🕰️ History That Stays

Every day is judged with the logs and times it had **on that day**.

- Adding a log or a time never changes earlier days' ticks, marks or streaks
- Switching a log off only affects that day and later
- Older months keep their real ✓ / ✕ marks; days before a log existed get no mark

---

### 📅 Calendar

- Green ✓ = everything due that day was ticked
- Amber ✓ = every log at least partly done (counts for the streak)
- ✕ = a past day where some log had no tick at all
- Portrait: current week, drag the **↑ DRAG ↓** control for the whole month
- Landscape / laptop: whole month

---

### 🔔 Reminders & AI Messages

- Exact-time reminders for every log time, with the S Log notification tone by default (custom sound selectable)
- Short **Zomato-style, Gen Z** messages written on the device by a small TensorFlow Lite model, picked from the log name (gym, water, reading, coding, fasting / IMD...)
- 7 messages per log for each week; a new week is generated automatically
- Already done before the reminder? A congrats notification instead
- Daily summary notification (default 11:59 PM) listing what's left

The model, its 10,000-message dataset and the training script are in [`ai/`](./ai).

---

### ☁️ Sync, Sharing & Offline

- Account with name, email and password (Firebase Authentication)
- New accounts need a **registration PIN** (stored in the database, not in this code)
- Logs and ticks sync live between phone and web (Cloud Firestore)
- Share a log with another S Log user, or as a file: `.csv`, `.slog` (with history) or both
- Import `.csv` / `.slog` files with a preview; S Log appears in "Open with" for these files
- Works offline: everything is saved on the device and uploaded when the network is back

---

### 💻 Web Version

A web version for laptops in [`web/`](./web) (plain HTML/JS, no build step) with the same account, data and features, hosted on Vercel.

---

## 📦 Download

Get the latest APK from the **SLog** release in the S-Suite repository's **Releases** section.

The web version runs in the browser; no install needed.

---

## 🔒 Permissions & Access

| Permission | Why |
|---|---|
| Internet | Sync with the cloud database |
| Notifications | Reminders, congrats and daily summary |
| Exact alarms | Reminders at the exact time |
| Boot completed | Restore reminders after a restart |
| Battery optimization exemption (optional) | Reliable reminders in the background |

---

## 🛠️ Technical Specs

- **Minimum SDK:** Android 8.0 (API 26)
- **Target / Compile SDK:** API 36
- **Package:** `com.hesi.slog`
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Backend:** Firebase Authentication + Cloud Firestore (offline persistence)
- **On-device AI:** TensorFlow Lite 2.16 (character-level LSTM)
- **Background work:** AlarmManager + WorkManager
- **Web:** HTML / CSS / JavaScript modules + Firebase JS SDK, deployed on Vercel
- **Internet Required:** only for sync (works offline)

---

## 🔐 Privacy

- Your logs and ticks are stored in **your own Firebase project**
- Database rules allow each account to read only its own logs and the logs shared with it
- Registration is protected by a PIN stored in the database
- AI messages are generated **on the device**; nothing is sent to an AI service
- `app/google-services.json` and signing keys are **not** part of this repository

---

## 🚀 Build

1. Follow **[SETUP.md](./SETUP.md)** once: Firebase project, database rules, registration PIN.
2. Put your own `google-services.json` in `app/` (it is git-ignored).
3. Open the `SLog` folder in **Android Studio** and run, or:

```bash
./gradlew assembleDebug
```

Web version locally:

```bash
cd web
python -m http.server 3000
```

Then open http://localhost:3000.

---

## 🧩 Project Structure

```text
SLog/
├── app/
│   ├── src/main/java/com/hesi/slog/   # Compose UI, view model, Firebase, streaks, reminders, AI
│   ├── src/main/assets/               # reminder_model.tflite + vocab.json
│   └── src/main/res/                  # fonts, icons, raw/slog_sound.ogg
├── web/                               # web version (Vercel root directory)
├── ai/                                # dataset, dataset builder, training script
├── firestore.rules                    # database security rules
├── sample_logs.csv / sample_logs.slog # example import files
├── SETUP.md
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🌐 S-Suite

S Log is one application in the **S-Suite** ecosystem.
