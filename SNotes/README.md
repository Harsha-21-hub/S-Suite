# 📓 S Notes

**S Notes** is a lightweight Android note-taking app built around a custom drawing canvas. It supports handwriting, drawing, temporary laser pointers, shapes, text, images, notebook organization, and portable `.snotes` notebooks.

S Notes is part of the **S-Suite** ecosystem.

## ✨ Features

### 🖊️ Drawing & Writing
- Custom canvas for handwriting and freehand drawing.
- Automatic stylus pressure sensitivity (S Pen, OnePlus Stylo, USI / MPP pens): light pressure draws thin, heavy pressure draws thick. It turns on by itself when a stylus touches the screen — no setting. Finger drawing keeps a constant width, and pens without a pressure sensor draw at the normal size.
- Pressure is saved with each stroke and kept in exports and `.snotes` notebooks.
- Strokes are stored exactly as drawn, without beautification.
- Pen color and size controls.
- Normal eraser for partial erasing.
- Stroke eraser for removing complete strokes.
- Undo and redo support.
- Stylus-only mode for devices with a pen/stylus.

### 🔴 Laser Pointer
- Temporary laser strokes for pointing or highlighting.
- The trail stays visible while drawing.
- The trail fades smoothly after you stop.

### ◻️ Shapes
- **Shape detector (on/off, top of the Shapes menu).**
  - **OFF** - the classic tool: pick a shape and drag to draw it.
  - **ON** - automatic: draw any shape freehand and it snaps into a perfect one when you lift the pen: line, arrow, circle, ellipse, triangle, rectangle, square, diamond, quadrilateral, pentagon, hexagon, star, arc and connected straight lines. Drawings it doesn't recognise stay as normal ink. Tapping a shape in the menu while the detector is on drags that shape once; as soon as it is drawn the tool is automatic again.
  - **Draw and hold** - with the detector on, the normal pen also snaps: draw a shape and keep the pen still for about half a second at the end (a short vibration confirms it).
  - Runs fully on-device: a small neural network (`ShapeModel`, trained offline on 156k synthetic hand-drawn strokes, see `tools/shape-model/`) picks the shape, then the shape is fitted to your ink (corners from intersected least-squares edges, ellipse from region moments, arcs from a circle fit) and tidied up: near-right-angle quads become rectangles, near-equal rectangles become squares, near-horizontal/vertical lines and edges snap straight, near-regular polygons become regular. ~99% correct on held-out test strokes.
- Lines, arrows, rectangles, circles, triangles, and other supported shapes.
- Shape size is independent from pen size.
- Shapes can be drawn in different drag directions.
- Shapes can be selected, moved, resized, rotated, and deleted.

### 🔲 Selection & Editing
- Tap to select individual strokes, shapes, text, or images.
- Area selection for selecting multiple drawing elements.
- Move, resize, rotate, and delete selected objects.
- On-canvas editing controls for text and images.

### 📝 Text
- Tap the page to create a text area.
- Edit text later with the Select tool.
- Resize text areas using the editor handles.
- Text color follows the current drawing color.

### 🖼️ Images
- Insert images into a notebook.
- Move, resize, rotate, and edit inserted images.
- Images remain separate editable objects rather than being flattened into the page.
- Handwriting can be drawn over inserted images.

### 📄 Notebook Pages
When creating a notebook, choose between:

- **Infinite Length** — the page grows continuously as you write.
- **Multiple Infinite Pages** — the notebook grows using page-sized sections.

The current page position is preserved when reopening a notebook. Reset Zoom restores the default zoom while keeping the current page position.

### 📑 PDF Notebooks
- **Import PDF** from the home page (third button in the bottom pill).
- Every PDF page becomes its own **separate canvas sheet**: the white PDF page plus free drawing space around it.
  - Each sheet = the PDF page + a writing panel of the same size, plus extra writing space on all four sides (top, bottom, left, right) in both landscape and portrait sheets. Notebooks made by earlier versions are upgraded automatically the first time they are opened (all ink shifts with its page).
  - **More pages:** writing near the bottom of the last page adds a new empty canvas page after it automatically, and the **+ ADD PAGE** button in the empty space under the last page adds one at the end. Added pages have the same size as the last page, follow the page-orientation button, and are exported like the PDF pages (just without a PDF underneath).
  - Portrait PDFs start as landscape sheets (PDF left, notes right); landscape PDFs start as portrait sheets (PDF top, notes below). Switch any time with the page orientation button.
- Write, draw, erase, add shapes, text and images anywhere on the sheet — on the PDF or beside it. Erasers only remove ink, never the PDF.
- The PDF keeps its real white paper in both themes; ink on the PDF stays dark so it is always readable.
- Pages render in the background and sharpen as you zoom in.
- **Reset Zoom** fits the current sheet on screen; rotating between portrait and landscape re-fits the sheet you were on.
- **Page orientation** button (PDF notebooks): switch every sheet between landscape (writing space right of the PDF) and portrait (writing space below). The PDF always stays upright; ink on the PDF stays on it, and notes move with their writing space. A line drawn from the PDF into the notes area is cut neatly at the edge and joins up again when you switch back.
- Page numbers are shown under every sheet.

### 📚 Home Page
- Create new notebooks.
- Open existing `.snotes` notebooks.
- Rename notebooks.
- Select multiple notebooks.
- Export or share selected notebooks.
- Move notebooks to Trash.
- Restore notes from Trash.
- Permanently delete notes or empty the Trash.
- View last-saved time, created time, storage size, and notebook page type.
- Portrait and landscape layouts are supported.

### 📊 Stats popup
- The bar-chart button in the editor (landscape: top bar; portrait: ⋮ menu) shows a small live card: app RAM (Java / native / GPU split) and free device RAM, app CPU, frames per second and janky frames, note + cache storage and free device storage, and the stroke count.
- Drag it anywhere; hide it with its × or the button. It samples only while visible (once a second on a background thread).

### ⚡ Performance
- Finished ink, PDF pages and objects are cached in a GPU layer that is only re-drawn when the page or the view changes. While writing, each frame only draws the stroke under the pen, so CPU/GPU use no longer grows with the number of strokes on the page.
- Long strokes freeze their older part, so a frame never gets slower the longer the pen stays down.
- Stroke points are stored as primitive float buffers (no boxed objects): ~5x less RAM per point and no garbage while writing.
- Autosave streams the note to disk without building a JSON tree, skips notes that didn't change, and loading uses a streaming reader.
- Dragging a selection redraws only the selection; PDF page cache is bounded tighter and released under memory pressure.

### 🎨 Themes
- Light and dark themes.
- Theme-aware dialogs, controls, and toolbars.
- S Notes uses a monochrome/red visual style with a custom dot-matrix-inspired font.

### 🎛️ Presets
- Built-in `DEFAULT` preset.
- Create custom presets.
- Select a preset and load it when needed.
- Delete custom presets.

### 📤 Share & Export
Notes can be exported or shared as:
- PDF
- JPG
- PNG
- Gallery images

PDF notebooks export every sheet in full — the PDF page together with everything written on and around it — as one PDF page / one image per sheet, and `.snotes` notebooks include the original PDF so they open identically on another device.

Export supports:
- Light output: black ink on white paper.
- Dark output: white ink on black paper.
- Single-image or split-page image layouts where supported.

### 📦 Portable `.snotes` Notebooks

S Notes has its own editable notebook format:

```text
.snotes
```

A `.snotes` notebook stores the notebook structure and editable content instead of flattening everything into one image.

It can contain:
- Notebook name
- Page mode
- Page/canvas information
- Drawing strokes
- Shapes
- Text objects
- Image objects
- Inserted image data

A `.snotes` notebook can be shared to another device with S Notes and imported there.

S Notes is also registered to handle `.snotes` files from supported file managers, sharing apps, and document providers. Notebooks shared from older versions with the `.hesi` extension still open.

## 🔒 Privacy

S Notes is designed to work locally on the device. Notebook data is stored locally and the app does not require a server for normal note creation or editing.

## 🛠️ Technical Details

- **Platform:** Android
- **Minimum Android version:** Android 12 (API 31)
- **Target SDK:** 34
- **Compile SDK:** 34
- **Language:** Kotlin
- **UI:** Android XML layouts and custom Views
- **Package:** `com.snotes.snotes`
- **Java/Kotlin target:** Java 17 / JVM 17
- **Main drawing engine:** Custom `DrawingView`
- **Portable notebook format:** `.snotes`
- **Rendering:** Hardware accelerated Android canvas
- **Dependencies:** AndroidX Core KTX, AppCompat, RecyclerView (the shape detector needs no extra library)

## 🚀 Build

Open the `SNotes` folder in **Android Studio**.

Or build from the command line with JDK 17 and the Android SDK:

```bash
./gradlew assembleDebug
```

For a release build:

```bash
./gradlew assembleRelease
```

The release build is configured with resource shrinking and code minification.

## 📱 Project Structure

```text
SNotes/
├── app/
│   ├── src/main/java/com/hesi/snotes/
│   ├── src/main/res/
│   └── build.gradle.kts
├── gradle/
├── tools/shape-model/   (offline training pipeline for the shape detector - not part of the build)
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

## 🌐 S-Suite

S Notes is one application in the **S-Suite** ecosystem.
