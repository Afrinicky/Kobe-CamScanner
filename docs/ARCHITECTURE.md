# Kobe CamScanner — Architecture

This document records how the app is put together and, more usefully, *why* the non-obvious choices
were made. It is a companion to the SDS, not a restatement of it.

---

## 1. Module map

```
com.kobe.camscanner
│
├── core
│   ├── common        KobeResult, dispatcher qualifiers, filename hygiene, formatting
│   ├── storage       KobeStorage (the Kobe/ tree), ImageStore (all bitmap I/O)
│   ├── permissions   camera permission state
│   └── ui            theme (colour, type, shape, motion) + the component library
│
├── domain
│   ├── model         ScanDocument, ScanPage, Folder, Quad, filters, PDF options
│   └── usecase       SaveScanSession — the one path from scan to saved document
│
├── data
│   ├── local         Room entities, DAOs, FTS4 search table, compact encodings
│   ├── repository    DocumentRepository (the library), ScanSession (work in progress)
│   └── settings      DataStore-backed preferences
│
├── scanner           OpenCvLoader, DocumentDetector, PerspectiveTransformer,
│                     ImageEnhancer, StabilityTracker, ScanProcessor
├── camera            DocumentAnalyzer (the live detection loop)
├── ocr               OcrEngine (bundled ML Kit)
├── pdf               PdfBuilder (creation + invisible text layer), PdfTools (manipulation)
├── ai                SmartNaming (local classification and filename suggestion)
├── share             ShareHelper, PrintHelper
│
├── feature           one package per screen: home, library, search, camera, crop,
│                     filter, pages, document, annotate, signature, watermark, settings
└── navigation        Routes, KobeNavHost
```

---

## 2. The image pipeline

The whole product lives or dies on one sequence:

```
frame → detect → correct → enhance → store → recognise
```

**Detection runs small.** `DocumentDetector` resizes every frame to a 480 px proxy before doing
anything else. A 4000 px frame carries no additional edge information at the scale a page boundary
occupies and costs roughly twenty times as much to process. The live loop additionally throttles to
~14 detections per second and drops frames rather than queueing them
(`STRATEGY_KEEP_ONLY_LATEST`), so the preview stays fluid on a mid-range device.

**Detection returns normalised corners.** Every quad is expressed in 0..1 image space. The same
values therefore drive the preview overlay, the crop screen, and the full-resolution homography with
no rescaling logic at any call site — which is precisely where this kind of code usually goes wrong.

**Canny is auto-thresholded.** The hysteresis band is derived from the image median rather than
fixed, which is what lets the same code find a page under office fluorescents and under a dim room
lamp.

**Corner validation is a score, not a filter.** Candidates are ranked on squareness, opposite-side
symmetry and frame coverage together, so the *best* quadrilateral wins rather than merely the
largest. Coverage stops helping past about half the frame: a page filling the viewfinder is not more
likely to be a page than one filling 60% of it.

**Enhancement's core trick is a division.** `ImageEnhancer.normaliseBackground` divides the page by a
heavily blurred copy of itself. That blurred copy *is* the illumination field, so the division
cancels the phone's own shadow, lamp falloff and the grey cast of a photocopy in a single operation —
without the blotching a global threshold produces. Everything else (white balance, CLAHE in LAB so
hue is untouched, paper whitening, unsharp mask) is finishing.

**Full-resolution work happens once, after capture.** `ScanProcessor` caps the long edge at 3000 px
(about 250 dpi across A4 — past what any office printer resolves) and is the only place that touches
an image that large.

**Edits never compound.** Every re-render starts from the stored original, so a user who
over-cropped, over-sharpened, or picked the wrong filter can always walk it back.

---

## 3. State: two stores, deliberately

**`ScanSession`** is process-scoped state holding the scan currently being built. The camera, crop
screen, filter screen and page editor are four destinations operating on one growing list of pages;
passing that list through navigation arguments would mean serialising it on every hop and losing it
on rotation. A session is cheap to abandon — `discard()` deletes its whole directory.

**`DocumentRepository`** owns the saved library. Two invariants are enforced there rather than left
to callers: the full-text index is written in the same operation as the document it describes, and a
permanent delete removes the files as well as the row. Every screen therefore sees a library where
search results always open and thumbnails always load.

Editing a saved document loads its pages back into the session and records
`editingDocumentId`, so `SaveScanSession` updates in place instead of creating a duplicate.

---

## 4. Two decisions that depart from a literal reading of the SDS

**A local SQLite database.** SDS 23/24 ask for local metadata files and forbid a server database.
Kobe uses Room — a SQLite file inside the app's own sandbox, created and read only by Kobe — because
full-text search across the OCR of hundreds of documents (SDS 25) has to return as the user types,
and scanning hundreds of JSON sidecars on every keystroke cannot do that. Nothing is shared, synced
or uploaded; the PDFs and page images on disk remain the authoritative artefacts and the index can be
rebuilt from them. This is a local cache, not a backend.

**FTS4, not FTS5.** FTS4 is present in every Android SQLite build back to API 21. FTS5 is not
guaranteed before API 30, and the app targets mid-range devices.

User input never reaches FTS directly. `FtsQuery` rebuilds every query from quoted tokens with a
prefix wildcard on the last one, because an unbalanced quote, a leading `*` or a bare `-` is a
syntax error in FTS4 rather than a no-op.

---

## 5. Searchable PDFs

`PdfBuilder` draws each page image, then — when OCR text is available — draws every recognised word
again in `RenderingMode.NEITHER` (no fill, no stroke) at the position it was found. The visible layer
is the scan; an exactly-aligned invisible text layer sits with it, so selecting, copying and
searching work in any PDF reader.

Two details make the alignment hold up. Font size comes from each word's own box height, and
horizontal scaling is set so the invisible word spans exactly the visible one — without it, a
selection highlight drifts progressively across a long line.

Pages are embedded as JPEG rather than losslessly: a scanned page is a photograph, and at these
quality levels the difference is invisible while the file is several times smaller. That matters a
great deal when the next step is WhatsApp.

---

## 6. Failure handling

Nothing raw ever reaches the screen. `KobeResult.Failure` carries a `FailureReason` that maps to the
exact wording in SDS 49, and the engines degrade rather than throw:

- OpenCV fails to load → `OpenCvLoader.isAvailable` is false, detection returns the full frame, and
  `ImageEnhancer` falls back to a `ColorMatrix` path. The user gets a working scanner, not a crash.
- OCR finds nothing → an empty result, surfaced as "Text could not be recognised" only when the user
  explicitly asked for text.
- A malformed FTS expression → the search degrades to title matching.
- Storage is checked *before* a long scan begins rather than half-way through writing page four.
- A single bad camera frame never takes the viewfinder down.

---

## 7. Design system

`core/ui/theme` holds the whole visual language: `KobePalette` (Acrobat reds on warm neutral paper),
`KobeTypography` (one family, negative tracking at display sizes), `KobeShapes`/`KobeRadius`, and
`KobeMotion`.

Rules the components enforce:

- The brand gradient fills exactly one control — the primary action. That is what makes SCAN
  unmistakable.
- Selection is never carried by colour alone; chips also change border weight (SDS 50).
- Touch targets stay at 48 dp even where the visual box is smaller.
- Editing surfaces use `KobeCanvasTheme` — always dark, regardless of system setting, so the page
  being worked on is the brightest thing on screen.
- Dynamic colour is not used. The wallpaper does not get to recolour the product.

---

## 8. Performance notes

- Live detection: 480 px proxy, ~70 ms throttle, frames dropped not queued.
- Filter strip: eight previews at 320 px cost less than one full-size render.
- Slider drags: debounced, and the main preview renders at 900 px, not full resolution.
- Bitmaps are recycled at every hand-off; `ImageStore` decodes with `inSampleSize` so it never
  allocates more pixels than the caller asked for.
- Image work runs on `Dispatchers.Default`, file work on `Dispatchers.IO`, both injected so a long
  enhancement pass cannot starve the writes happening alongside it.

---

## 9. Built but not yet surfaced

Honest inventory of what exists as a tested engine capability without a screen in front of it:

| Capability | State |
| --- | --- |
| PDF merge / split / extract / reorder / delete pages | `PdfTools` complete; only compress is reachable from the UI |
| Signature insert and reposition on a page | `SignaturePad` and `renderStrokesToBitmap` complete; the placement UI is not built |
| ID-card front/back combined output | `ScanMode.ID_CARD` selectable; the two-capture combining step is not implemented |
| Book mode curved-page correction | Mode selectable; SDS 37 marks this explicitly post-MVP |
| App lock / biometrics | Setting persists; the lock screen itself is not built (SDS 48 defers this) |
| Shapes, text boxes and eraser in annotation | Pen and highlighter shipped; the rest of SDS 30 is not |

Everything else in the SDS V1 scope (§55) is wired end to end.

---

## 10. The rule that governs the codebase

From SDS 58, and worth restating because every architectural decision above follows from it:

> Do not build around a server and remove the server later. Build Kobe as a local application from
> the beginning.

```
Android → Local processing → Local storage → User-controlled export
```
