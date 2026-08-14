# 📓 S Notes

**S Notes** is a lightweight Android note-taking app built around a custom drawing canvas. It supports handwriting, drawing, temporary laser pointers, shapes, text, images, notebook organization, and portable `.hesi` notebooks.

S Notes is part of the **S-Suite** ecosystem.

## ✨ Features

### 🖊️ Drawing & Writing
- Custom canvas for handwriting and freehand drawing.
- Raw strokes are stored without pressure-based smoothing or beautification.
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

### 📚 Home Page
- Create new notebooks.
- Open existing `.hesi` notebooks.
- Rename notebooks.
- Select multiple notebooks.
- Export or share selected notebooks.
- Move notebooks to Trash.
- Restore notes from Trash.
- Permanently delete notes or empty the Trash.
- View last-saved time, created time, storage size, and notebook page type.
- Portrait and landscape layouts are supported.

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

Export supports:
- Light output: black ink on white paper.
- Dark output: white ink on black paper.
- Single-image or split-page image layouts where supported.

### 📦 Portable `.hesi` Notebooks

S Notes has its own editable notebook format:

```text
.hesi
```

A `.hesi` notebook stores the notebook structure and editable content instead of flattening everything into one image.

It can contain:
- Notebook name
- Page mode
- Page/canvas information
- Drawing strokes
- Shapes
- Text objects
- Image objects
- Inserted image data

A `.hesi` notebook can be shared to another device with S Notes and imported there.

S Notes is also registered to handle `.hesi` files from supported file managers, sharing apps, and document providers.

## 🔒 Privacy

S Notes is designed to work locally on the device. Notebook data is stored locally and the app does not require a server for normal note creation or editing.

## 🛠️ Technical Details

- **Platform:** Android
- **Minimum Android version:** Android 12 (API 31)
- **Target SDK:** 34
- **Compile SDK:** 34
- **Language:** Kotlin
- **UI:** Android XML layouts and custom Views
- **Package:** `com.hesi.snotes`
- **Java/Kotlin target:** Java 17 / JVM 17
- **Main drawing engine:** Custom `DrawingView`
- **Portable notebook format:** `.hesi`
- **Rendering:** Hardware accelerated Android canvas
- **Dependencies:** AndroidX Core KTX, AppCompat, RecyclerView

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
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

## 🌐 S-Suite

S Notes is one application in the **S-Suite** ecosystem.
