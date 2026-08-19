# Kobe CamScanner

**Scan. Enhance. Keep it private.**

An offline-first Android document scanner. Kobe turns a phone camera into a professional scanner —
edge detection, perspective correction, shadow removal, OCR, searchable PDFs, and a local document
library — and does all of it on the device.

The application declares **no `INTERNET` permission**. It is structurally incapable of transmitting a
user's documents. Everything in the list below works with Airplane Mode on.

---

## What it does

| Area | Capability |
| --- | --- |
| **Capture** | CameraX viewfinder, live boundary detection, auto-capture on steady, flash, pinch zoom, framing grid, gallery import |
| **Correction** | OpenCV contour pipeline, homography perspective correction, manual four-corner adjustment with a magnifier loupe, deskew |
| **Enhancement** | Illumination-field normalisation (shadow removal), grey-world white balance, CLAHE in LAB, paper whitening, unsharp mask; eight filters plus five manual controls |
| **Multi-page** | Batch scanning, drag-to-reorder page grid, rotate, crop, duplicate, retake, delete |
| **PDF** | Creation with page size / quality / compression, merge, split, extract, reorder, rotate, delete pages, rasterising compressor |
| **OCR** | Bundled on-device ML Kit Latin recognition, invisible text layer for searchable PDFs, extract / copy / share text |
| **Library** | Local index, folders, starred, trash with 30-day retention, sort, grid and list, multi-select |
| **Search** | SQLite FTS4 across filenames and recognised text, with matched-snippet results |
| **Productivity** | Pen and highlighter annotation, signature capture, text watermarks, one-tap WhatsApp and Save-to-Files, Android print framework |
| **Local AI** | Heuristic document classification and smart filename suggestion from recognised text |

---

## Download

Every push builds a debug APK on CI and publishes it here:

**[→ Latest build](https://github.com/Afrinicky/Kobe-CamScanner/releases/tag/dev-latest)**

Take `kobe-camscanner-arm64.apk` unless you know your phone is 32-bit. It is signed with the
standard Android debug key, so it installs by hand: download it on the phone, allow *install from
unknown sources* for your browser or file manager when prompted, and open the file. The package
carries a `.debug` suffix so it can sit alongside a Play Store copy.

---

## Building

Kobe is a standard Gradle Android project. Open it in Android Studio (Ladybug or newer) and run, or:

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # minified release APK
./gradlew bundleRelease          # release AAB
```

**Requirements**

- JDK 17
- Android SDK 35, build tools 35+
- minSdk 24, targetSdk 35

> **Note:** the project was authored in an environment where `dl.google.com` — and therefore
> Google's Maven repository and the Android SDK — was blocked by network policy, so it could not be
> compiled locally. It is compiled on CI instead; see the workflow in `.github/workflows/`.

---

## Architecture

```
                    KOBE CAMSCANNER
                          │
                  Jetpack Compose
                          │
                     ViewModels
                          │
              ┌───────────┼───────────┐
              ↓           ↓           ↓
           Camera      Scanner      Library
           CameraX      Engine       Files
                       OpenCV      Metadata
                          │
                     Image Engine
                          │
                    OCR (ML Kit)
                          │
                     PDF Engine
                          │
                    Local Storage
                          │
              Share / Print / Export
```

MVVM over a small set of engines. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module
map, the decisions behind the image pipeline, and the pieces that are engine-complete but not yet
surfaced in the UI.

**Stack:** Kotlin · Jetpack Compose · Material 3 · Hilt · Room (FTS4) · DataStore · CameraX ·
OpenCV · ML Kit (bundled) · PDFBox-Android · Coil · Coroutines

---

## Storage layout

```
<app files>/Kobe/
 ├── Documents/     finished PDFs
 ├── Scans/         per-session page originals and processed images
 ├── Exports/       copies produced for export
 ├── Signatures/    saved signatures
 ├── Thumbnails/    library grid bitmaps
 └── Trash/         soft-deleted documents
```

Everything lives in the app's private files directory. That needs no storage permission on any API
level, no other app can read it, and it is removed cleanly on uninstall. Files leave only through an
explicit share or export, handed out as temporary read-only URIs via `FileProvider`.

Cloud backup is excluded for the whole `Kobe/` tree and the library database.

---

## Privacy

- No account, no server, no analytics
- No `INTERNET` permission — and because ML Kit's transitive dependencies declare one, it is
  explicitly removed at manifest-merge time and the build fails if it reappears in the APK
- OCR is a bundled on-device model — no download, no upload
- Document classification and naming are local heuristics, not a cloud service
- Signatures never leave the device
- Two permissions total: camera, and read access below API 33 so gallery import works

---

## Design

The palette is drawn from the Adobe Acrobat family — a deep signal red used only for brand, primary
action and active state — on warm neutral paper tones chosen so scanned pages (almost always white)
do not fight the interface. Editing surfaces are always dark so the page is the brightest thing on
screen. One motion vocabulary throughout: springs for anything a finger drives, short eased tweens
for anything the app drives, nothing longer than 320 ms.

Dynamic colour is deliberately not used. Kobe's red is the product.

---

## Licence and naming

Development name only. Before any commercial release, run a trademark and name-conflict review of
"CamScanner" and, if needed, ship under a distinct final name such as **Kobe Scan** or
**Kobe Scanner**.

Copyright © Nickland Sales & Services.
