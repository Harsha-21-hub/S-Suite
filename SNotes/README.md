# S NOTES

A Nothing-OS-styled (red / white / black) handwriting & drawing notes app.
Package: `com.hesi.snotes` — Android 12+ (minSdk 31) — built for low-RAM
devices like the Galaxy Tab A 10.1 (2019, 2 GB RAM).

## Build

Open the project folder in **Android Studio** and press Run — or from the
command line (needs JDK 17 and the Android SDK):

```
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

For the small, optimized APK: `./gradlew assembleRelease` (sign it in
Android Studio via Build > Generate Signed App Bundle / APK).

## Why it's light

- Pure vector ink: no giant canvas bitmaps in RAM, ever.
- Zero heavy libraries — only appcompat + recyclerview (+ built-in org.json).
- One cached `Path` per stroke, rebuilt only when the stroke changes.
- Manual light/dark theming (no activity recreation when switching).
- Export bitmaps are capped at 2048 px (~16 MB peak, released immediately).
- Undo history bounded to 100 actions.

## Controls

| Action | How |
| --- | --- |
| Draw | 1 finger with Pen tool |
| Normal eraser | Eraser tool (paints background, size slider) |
| Stroke eraser | Slashed-eraser tool: touch a stroke to delete it whole |
| Scroll / zoom | 2 fingers anywhere (smooth pinch + pan) |
| Scrollbars | Drag the red thumb on the left edge (vertical) or bottom edge (horizontal) |
| Select | Select tool, drag a box; then drag inside to move, drag the red corner square to resize, DELETE button to remove |
| Undo / redo | Top bar arrows |
| Theme | Half-circle button — black↔white ink converts automatically |
| Colors | Palette button — 12 colors + your recent history |
| Sizes | Slider button — pen and eraser sliders |
| Shapes | Triangle/circle/square button — toggles line/circle/rectangle auto-detection (very cheap, off by default) |
| Clear all | Trash button (asks first, undoable) |
| Hide toolbars | Chevron in the top bar; they also auto-hide while scrolling |
| Page growth | Write near ANY edge and the page extends in that direction |
| Share | Share button — PNG / JPG / save to gallery. (A "custom link" needs a server to host the note, so it isn't possible in a fully offline app — PNG/JPG sharing covers every messenger.) |

Notes auto-save when you leave the editor. Rename by tapping the title, or
via the ⋮ menu in the list (rename / share / delete).
