# S-MediaReconstruct

**High-quality Windows media reconstruction tool for fragmented `.exo` / DASH media and mixed/separate audio-video sources.**

S-MediaReconstruct is designed around a simple priority:

> **Preserve the original media whenever possible. Process only when necessary. Verify the result before considering the job complete.**

It provides a Windows desktop interface for analyzing, reconstructing, testing, merging, and exporting fragmented media.

---

## Features

- Windows desktop application with a Nothing-inspired technical UI.
- Select **individual files** or a **head/root folder**.
- Optional recursive subfolder scanning.
- Dynamic detection of:
  - `.exo` / fragmented MP4 / DASH media
  - separate audio + video streams
  - combined audio + video files
- Dynamic media analysis with FFprobe.
- EXO initialization and fragment detection using MP4 track metadata.
- Continuous video and audio reconstruction for fragmented sources.
- Two processing modes:
  - **Copy First**
  - **Normalize All**
- Dynamic clip/segment durations; no fixed 4-second assumption in the general engine.
- Batch processing with configurable engine batch size; default workflow uses **50 files per batch**.
- Two random preflight tests before full processing.
- Final verification before output completion.
- Diagnostic logging for failures and processing stages.
- Cancel operation support.
- Output format selection:
  - **MKV**
  - **MP4**
- User-selectable output folder and filename.
- GPU-assisted processing where supported by the selected processing profile.
- FFmpeg/FFprobe can be bundled into the Windows executable.

---

## Processing Modes

### 1. COPY FIRST

**Preferred mode for preserving the original encoded media.**

Workflow:

```text
Analyze
  ↓
Reconstruct original encoded streams
  ↓
Check compatibility
  ├─ Compatible → stream copy
  └─ Incompatible → normalize only what is necessary
  ↓
Verify
  ↓
Final output
```

This mode is intended to avoid unnecessary re-encoding.

### 2. NORMALIZE ALL

**Use when the source clips need a common timeline/format.**

Workflow:

```text
Analyze
  ↓
Normalize every clip
  ↓
Batch processing
  ↓
Merge
  ↓
Audio/boundary verification
  ↓
Final output
```

The validated workflow used during development normalizes video with H.264/NVENC and uses PCM audio during the normalization/batching stages before the final verified audio path is applied.

---

## Audio Boundary / Beep Protection

Fragmented media can contain audible clicks or beeps at segment boundaries when each audio fragment is decoded and joined independently.

S-MediaReconstruct therefore treats fragmented audio differently from ordinary clip concatenation.

For EXO/DASH sources, the preferred approach is:

```text
Audio initialization segment
        +
ordered audio fragments
        ↓
continuous encoded audio stream
        ↓
single timeline
```

The application must not blindly apply repeated audio crossfades to every boundary.

Boundary checking is used to identify suspicious transitions and verify the resulting timeline.

---

## EXO / DASH Reconstruction

The EXO scanner identifies actual MP4 tracks from container metadata instead of assuming codec strings alone are sufficient.

The expected detection sequence is:

```text
EXO files
   ↓
find MP4 initialization segments
   ↓
inspect MP4 tracks
   ↓
identify video/audio handler types
   ↓
identify track IDs
   ↓
match fragmented `moof` / `tfhd` / `tfdt` data
   ↓
order fragments by timestamp
   ↓
reconstruct continuous streams
```

This allows the engine to handle sources where audio and video use separate initialization segments as well as sources with combined initialization data.

---

## Verification

A job should not be considered successful merely because FFmpeg exits with code `0`.

The workflow verifies:

- input file count
- media structure
- stream detection
- codec information
- track IDs
- fragment counts
- ordering
- reconstructed stream duration
- preflight test #1
- preflight test #2
- batch outputs
- final output streams
- final duration
- final media integrity
- audio boundary behavior

A failed verification leaves the source media untouched.

---

## Preflight Tests

Before full processing, the application performs **two randomized test sections**.

Each test verifies a real portion of the reconstructed media rather than always testing the beginning.

Typical checks include:

- video readability
- audio readability
- stream presence
- timing
- A/V relationship
- boundary behavior

The full job should not proceed when a mandatory preflight test fails.

---

## Dynamic Media Handling

The application must **not assume that every source movie uses 4-second clips**.

Segment durations are determined from the actual source:

```text
4.000 sec
3.040 sec
5.200 sec
6.000 sec
...
```

The 4-second/3.04-second values seen during development belonged to one specific test source and are not application-wide constants.

---

## Output

Supported final containers:

- `MKV`
- `MP4`

The user selects:

```text
Output folder
Output filename
Output format
```

The interface shows the complete final output path before processing.

---

## Hardware

The application is intended to support:

- GPU-accelerated FFmpeg processing using NVIDIA hardware where applicable.
- CPU-only processing.
- CPU + GPU processing.

The goal is to use available hardware effectively without fabricating workload merely to increase reported RAM/VRAM utilization.

---

## Windows Build

The project is designed so the Windows build can produce a self-contained executable.

The intended end-user experience is:

```text
Download S-MediaReconstruct.exe
        ↓
Run
        ↓
No Python setup
No FFmpeg PATH setup
No manual media-tool installation
```

The build script installs the Python packages listed in `requirements.txt` and bundles FFmpeg/FFprobe into the executable when building on Windows.

### Build

Open PowerShell in the project directory:

```powershell
powershell -ExecutionPolicy Bypass -File ".\build_windows.ps1"
```

The generated executable is:

```text
dist\S-MediaReconstruct.exe
```

---

## Run From Source

For development/testing:

```powershell
powershell -ExecutionPolicy Bypass -File ".\run_windows.ps1"
```

---

## Project Structure

```text
SMediaReconstruct/
│
├── src/
│   ├── app.py
│   ├── api.py
│   ├── engine/
│   ├── ui/
│   ├── icons/
│   └── fonts/
│
├── build_windows.ps1
├── run_windows.ps1
├── requirements.txt
├── README.md
└── THIRD_PARTY_NOTICES.txt
```

Build-generated directories such as:

```text
.venv/
build/
dist/
__pycache__/
```

should normally not be committed to the repository.

---

## Requirements

### Development/build machine

- Windows 10/11
- Python 3.10+
- Internet access during dependency bootstrap/build
- PowerShell
- NVIDIA GPU is optional but recommended for GPU-accelerated workflows

The Python dependencies are listed in:

```text
requirements.txt
```

FFmpeg/FFprobe are handled by the Windows build workflow.

### End user

The preferred release is the packaged:

```text
S-MediaReconstruct.exe
```

---

## Release Distribution

The repository contains the source code.

The ready-to-run Windows executable should preferably be distributed through **GitHub Releases** rather than committed into the source tree.

Example:

```text
GitHub repository
└── SMediaReconstruct/
    ├── src/
    ├── build_windows.ps1
    ├── run_windows.ps1
    ├── requirements.txt
    ├── README.md
    └── THIRD_PARTY_NOTICES.txt
```

---

## Development Principles

1. **Copy first.**
2. **Never re-encode when it is unnecessary.**
3. **Never assume a fixed segment duration.**
4. **Never silently ignore audio/video mismatches.**
5. **Test before starting the full job.**
6. **Verify every major processing stage.**
7. **Never delete source media after a failure.**
8. **Do not submit an output that still contains a detected boundary artifact.**
9. **Stop safely when an operation is cancelled.**
10. **Report useful diagnostic information when anything fails.**

---

## Status

S-MediaReconstruct is an actively developed project.

The media-processing engine is being validated against real fragmented-media sources and should be treated as **development software until the current release has completed its full validation suite**.

---

