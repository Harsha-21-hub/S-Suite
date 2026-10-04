# S Log setup

S Log saves everything in **Firebase** (free). The Android app and the website use the same
accounts and data. New accounts need a **registration PIN** that only you know.

Already done: Firebase project, Email/Password sign-in, Firestore database, Android app +
`google-services.json`. If you're starting fresh, see "Fresh setup" at the bottom.

---

## 1. Publish the new database rules (do this again after every update that changes `firestore.rules` - this update does)
Firebase console -> **Firestore Database -> Rules** -> delete everything -> paste the whole
`firestore.rules` file -> **Publish**.

## 2. Set the registration PIN (stored in Firebase, NOT in the code)
Firestore Database -> **Data** tab:
1. **+ Start collection** -> Collection ID: `config` -> Next
2. Document ID: `registration`
3. Field: `pin` - Type: **string** - Value: `7755`
4. Save

That's it. The PIN lives only in your database; the rules check it and nobody (not even the app)
can read it. So putting the code on GitHub doesn't reveal it.

- New accounts: enter the PIN on the sign-up screen.
- Your existing account: the app asks for the PIN once on next start.
- Want to stop new sign-ups / change the PIN: just edit that `pin` value. Everyone (you
  included) enters the new PIN once.

> Without `config/registration` nobody can use the app, so don't skip this step.

## 3. Android
Extract the updated files, then in Android Studio: **File -> Sync Project with Gradle Files** -> Run.

## 4. Web version - test on your laptop
1. Firebase console -> Project Overview -> **+ Add app** -> Web (`</>`) -> nickname `slog-web`
   -> Register (don't tick Firebase Hosting).
2. Copy the values from the `firebaseConfig = { ... }` block it shows into
   `web/firebase-config.js` (replace every `PASTE_...`). These values are meant to be public;
   your data is protected by the rules + PIN.
3. Run it locally (needs Node.js from https://nodejs.org), in the `SLog` folder:
   ```
   npx serve web
   ```
   Open http://localhost:3000 and log in with your account.
   (No Node? With Python: `cd web` then `python -m http.server 3000`.)

## 5. Push to GitHub
In the `SLog` folder:
```
git init
git add .
git commit -m "S Log"
git branch -M main
git remote add origin https://github.com/<your-username>/slog.git
git push -u origin main
```
(Create the empty `slog` repo on github.com first.) `app/google-services.json` is in
`.gitignore`, so it isn't uploaded; keep your own copy.

## 6. Put the website on Vercel
1. https://vercel.com -> sign in with GitHub -> **Add New -> Project** -> import the `slog` repo.
2. **Root Directory**: click Edit -> choose `web`.
3. **Framework Preset**: `Other`. Leave build/output settings empty -> **Deploy**.
4. You get an address like `https://slog-xyz.vercel.app`.
5. Firebase console -> **Authentication -> Settings -> Authorized domains -> Add domain** ->
   `slog-xyz.vercel.app` (without https://). Without this, login on the website fails.

Every `git push` redeploys the website automatically.

---

## Using S Log
- **Sign up**: name, email, password + registration PIN. You're in straight away.
- **Share a log**: tap the share icon on a log (or Settings -> SHARE ALL LOGS) and pick
  - **CSV** - name + times; also opens in Excel / Google Sheets
  - **.SLOG** - S Log's own file: names, times, on/off and your tick history
  - **BOTH** - sends both files together
  On Android this opens the share sheet (WhatsApp, Gmail, Drive...). On a laptop the files download.
- **Import**: IMPORT button (or Settings -> IMPORT LOGS) -> pick a `.csv` or `.slog` file ->
  preview -> untick what you don't want -> ADD. For `.slog` files you can also choose to import
  the tick history. Examples: `sample_logs.csv`, `sample_logs.slog`.
- **Open with S Log**: tap a `.slog` / `.csv` file in WhatsApp, Files, Gmail... -> choose S Log ->
  the import preview opens.
- **Calendar**: in portrait it shows the current week; use the ↑ (DRAG) ↓ control to drag down
  for the whole month and up for one week. In landscape (and on laptops) the whole month is always
  shown, with no drag control.
  Green ✓ = everything due that day ticked. Amber ✓ = every log at least partly done (counts for
  the streak). ✕ = a past day where some log had no tick at all. Days before your logs existed get
  no mark, so older months keep their real marks.
- **History stays as it was**: every day is judged with the logs and times it had *on that day*.
  A log added on the 25th only counts from the 25th; a time added (or a log switched off) today
  only changes today and later. Earlier ticks, marks and streaks never change. Past days in the list
  show only the logs and times they had then.
- **Streaks** (dot-matrix icons, in this order; top = all logs, each log also has its own):
  crown = Max (best run ever, saved in the database, never lost), calendar = Monthly (best run this
  month, starts fresh each month), flame = Current (run up to today).
  A log counts for a day when at least one of its times is ticked (partly done counts); the whole
  day counts when every log that was due is at least partly done.
- **Milestones**: when a Max streak reaches 3, 10, 50, 100, 150, 200, 250, 300, 365 days (then
  every 50, and every year) an animated popup celebrates it. Each milestone shows once (remembered
  in the database, so not again on your other devices).
- **Reminders** (Android): each reminder shows a short Zomato-style message written by the
  on-device model for that kind of log (gym, water, reading, coding, fasting...), a new set every
  week (retraining: `ai/README.md`). Already ticked that time before the reminder? You get a
  congrats notification instead.
- **Notification sound**: the S Log tone by default. Settings -> SELECT CUSTOM NOTIFICATION SOUND
  to change it, RESET TO S LOG TONE to go back.
- **Daily summary**: "LEFT TODAY" line in the app, plus a notification at the end of the day
  (11:59 PM) listing what's left (change the time in Settings -> DAILY SUMMARY). Nothing left -> no
  notification.
- **Avatar** (top left, Nothing-style dotted ring with your initials): profile, SIGN OUT,
  DELETE ACCOUNT (asks your password, deletes your account and all your logs for good).
- **Offline**: everything you do without internet (ticks, new logs, edits, deletes) is saved on
  the device and uploaded automatically when you're back online, even if you closed the app.

## Free limits (Spark plan)
About 50,000 database reads and 20,000 writes per day and 1 GB of storage. Firebase only charges
if you upgrade the plan yourself. Backups need the paid plan - S Log doesn't need them.

---

## Fresh setup (new Firebase project)
1. https://console.firebase.google.com -> Create project.
2. Build -> Authentication -> Get started -> Sign-in method -> Email/Password -> Enable -> Save.
3. Build -> Firestore Database -> Create database -> Standard edition -> ID `(default)`,
   location `asia-south1 (Mumbai)` -> production mode -> no backups -> Create.
4. Steps 1 and 2 above (rules + PIN).
5. Project Overview -> Add app -> Android -> package `com.hesi.slog` -> Register ->
   download `google-services.json` into `SLog/app/` -> Next -> Continue to console.
6. Steps 3-6 above.
