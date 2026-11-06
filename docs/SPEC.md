# Ndalamo PDF — Project Specification

> Name: **Ndalamo** (means "prosperity"). Full product name: **Ndalamo PDF**. Window title and header show "Ndalamo".
> This document is the single source of truth for building the app.
> UI mockups: source files in `docs/ui/screens/` (index in `docs/ui/README.md`).

---

## 1. What we are building

A **free, fully offline Java desktop app** for working with PDFs, similar in scope to online PDF toolkits, plus a small **Microsoft Word add-in** that adds a "Delete Page" button.

**Target users**
- **Students:** merge, compress (especially "under 2 MB" for upload portals), image to PDF, PDF to Word, page numbers. Often on low-end laptops.
- **Small offices:** scan to PDF, OCR, merge, sign, protect, redact, PDF to Excel, batch work, pipelines and watch folders.

**Non-negotiable principles**
1. **100% offline.** No network calls of any kind: no telemetry, no update checks, no crash reporting, no cloud APIs, no AI services.
2. **Never overwrite the original file.** Every tool writes a new file. This cannot be turned off.
3. **Never freeze the UI.** All processing runs on background threads with progress and cancel.
4. **Plain-language errors.** Say what happened, confirm the original is safe, offer a next step. Technical details only on request.
5. **Free for now.** No licensing, tiers or paywalls. But keep tools behind a clean interface so gating is easy later.
6. **Permissive dependencies only:** Apache 2.0, MIT, BSD, EPL, LGPL, SIL OFL (fonts), GPL + Classpath Exception (JavaFX); MPL only as a separate process (LibreOffice). **Do not use iText** (AGPL) or any other GPL/AGPL library linked into the app.

**Explicitly out of scope**
- AI summariser, translation, anything that needs the internet.
- Web to PDF (needs the internet).
- Accounts, cloud storage, sharing links.

---

## 2. Tech stack

| Area | Choice | Licence |
|---|---|---|
| Language | Java 22 (see decision 002) | — |
| UI | JavaFX 22 | GPL + Classpath Exception (fine) |
| Build | Maven, multi-module | — |
| Core PDF work | Apache PDFBox 3.x | Apache 2.0 |
| OCR | Tess4J (Tesseract wrapper) + bundled `tessdata` | Apache 2.0 |
| Table extraction | Own table finder on PDFBox (decision 010; Tabula 1.0.5 needs PDFBox 2) | — |
| Office files (read/write) | Apache POI | Apache 2.0 |
| Office to PDF | LibreOffice (headless, external process), run directly with `soffice --convert-to pdf` (decision 008; JODConverter not needed) | LibreOffice (MPL) runs as a separate program |
| Markdown (read) | commonmark-java + tables, strikethrough, task-list add-ons (approved 2026-11-06, decision 011) | BSD-2 |
| JSON (settings, pipelines) | Gson | Apache 2.0 |
| Logging | SLF4J + Logback (local log file only) | MIT / EPL-LGPL dual |
| Testing | JUnit 5, AssertJ, TestFX (UI smoke tests) | EPL / Apache |
| Packaging | jpackage (bundled JRE, Windows installer first) | — |
| Word add-in | C# VSTO add-in (separate project) | — |

Use the latest stable release of each library that is **at least two weeks old**. Pin exact versions in the parent `pom.xml` `<dependencyManagement>`.

**Platform:** Windows first (Word add-in, Explorer integration, tray). Keep tools and services platform-neutral so macOS/Linux builds are possible later.

---

## 3. Architecture

One Maven project with five folders, each with one job. Tools never contain screen code and screens never contain PDF code, so each file is about one thing and pipelines/watch folders can reuse the tools.

### 3.1 Project layout

```
ndalamo-pdf/
├── pom.xml
├── src/main/java/com/ndalamo/
│   ├── NdalamoApp.java     starts the app and opens the window
│   ├── screens/            what the user sees: screens, dialogs, toasts, header
│   ├── tools/              what happens to the PDF: PdfTool, Tools (list of all tools), one class per tool
│   ├── services/           behind-the-scenes jobs: JobService, SettingsService, PipelineService, WatchFolderService, FileInspector
│   ├── models/             plain data: Settings, FileResult, OptionField, Progress, Pipeline, WatchFolder
│   └── utils/              small helpers: Theme, OutputNames, Icons
├── src/main/resources/     brand/, css/app.css, fonts/, logback.xml
├── src/test/java/com/ndalamo/   tests, same folders
├── word-addin/             C# VSTO project, built separately (Phase 6)
└── docs/                   SPEC.md, decisions/, ui/
```

### 3.2 How the pieces connect

```
HomeScreen ──click "Compress"──► ToolScreen ──click "Compress 3 files"──► JobService
                                                                              │
                                                     runs in background ──► CompressTool
                                                                              │
ResultScreen ◄──────────── list of FileResult ◄──────────────────────────────┘
```

1. The screen collects the files and options.
2. `JobService` runs the tool in the background so the window never freezes.
3. The tool does the PDF work and returns one `FileResult` per file.
4. The result screen shows them.

### 3.3 The tool contract (shape, not final code)

Every tool is one class in `tools/` that implements `PdfTool`, and is added to the list in `Tools`. The home screen, search, pipeline builder and drop suggestions all read that list.

```java
public interface PdfTool {
    String id();                          // "compress" — never changes once released
    String name();                        // "Compress PDF"
    String description();                 // card text: "Smaller files, same pages"
    String category();                    // "Optimize"
    List<OptionField> optionFields();     // the options panel is built from these
    List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress) throws Exception;

    // Optional, with defaults: fileTypes() ("pdf"), minFiles() (1), oneOutput() (false),
    // outputSuffix() (id), checkOptions(options) (catches mistakes like "1-x" before running),
    // saveToNote(), buttonLabel/workingLabel/doneLabel ("Compress 3 files", ...)
}
```

- `SaveTo` (model): where outputs go and how they're named, from Settings. Tools call `saveTo.outputFor(input, "compressed", "pdf", pages)` and never pick names themselves.
- `FileResult` (model): input, output, size before/after, status (`SUCCESS`, `PARTIAL`, `FAILED`, `SKIPPED`) and a plain-language note.
- `Progress` (model): the tool calls `progress.update(0.62, "Compressing images · 62%")` and checks `progress.isCancelled()` between pages.

Rules:
- A tool **never** touches JavaFX.
- A tool checks `progress.isCancelled()` between pages and stops cleanly, leaving no output behind.
- Every tool writes with `SafeSave` (a temp file first, moved to the final name only on success).
- One bad file in a batch must not stop the others: record it as `FAILED` or `SKIPPED` and continue.

### 3.4 Threading

- `JobService` runs tools on background threads (a fixed thread pool) and passes progress to the screen with `Platform.runLater`, at most ~10 updates per second.
- Files processed at once comes from Settings: `Automatic` = `max(1, cores / 2)`, or a fixed 1/2/4.
- "Low-memory mode" (Settings) makes PDFBox use temp files instead of memory and forces one file at a time.

### 3.5 Output naming

- Pattern from Settings, default `{name}_{tool}` → `Assignment2_compressed.pdf`.
- Tokens: `{name}`, `{tool}`, `{date}` (yyyy-MM-dd), `{pages}`.
- Destination: same folder as the original (default), a fixed folder, or ask each time.
- If the target exists: add a number (`Assignment2_compressed (2).pdf`) by default, or ask, or replace — **but never replace the original input file under any setting.**

### 3.6 Storage locations (Windows)

- Settings, pipelines, watch folders, recent files: `%APPDATA%\Ndalamo\` as JSON.
- Logs: `%LOCALAPPDATA%\Ndalamo\logs\` (rolling, local only).
- Temp work: system temp in a per-job subfolder, deleted when the job ends.

---

## 4. Feature list and implementation notes

### Organize
| Tool | Notes |
|---|---|
| Merge PDF | Ordered list; drag to reorder; images allowed (converted on the fly). |
| Split PDF | Modes: by ranges (`1-3, 5, 8-10`), every N pages, every page, by max file size. |
| Page workspace | Visual editor: reorder, rotate, delete, insert blank, add pages from file, extract, split after selection, crop. Multiple PDFs in tabs; move pages between them. Changes applied on Save. |
| Rotate | Per page or all; 90/180/270. |
| Remove pages | Range input or via workspace. |
| Crop | Per page or all; drag a box on a preview. |

### Optimize
| Tool | Notes |
|---|---|
| Compress | Recommended / Strong / **Target size (MB per file)**. Recompress images (downscale above a DPI, JPEG quality), optional greyscale, remove metadata, drop unused objects. Target size = iterative: try settings from mild to strong until under target; if impossible without unreadable output, stop at the best readable result and report `PARTIAL` ("Reached 2.6 MB — smaller would make it unreadable"). |
| Repair | Re-parse leniently with PDFBox and re-save; report what was recovered. |
| PDF to PDF/A | PDF/A-2b: embed fonts, add output intent and XMP metadata. Validate where possible; report if it can't comply. |

### Convert
| Tool | Notes |
|---|---|
| Image to PDF | JPG/PNG/BMP/TIFF (multi-page). Page size: fit image, A4, Letter; margins; orientation auto. |
| PDF to JPG | Render each page at chosen DPI (72/150/300); or extract embedded images. Output a folder (or ZIP option). |
| Office to PDF | Word, Excel, PowerPoint via LibreOffice headless (`soffice --convert-to pdf`, decision 008). Detect LibreOffice automatically; if missing, show the "needs LibreOffice" dialog. Everything else must work without it. |
| OCR PDF | Tess4J; render each page at 300 DPI, OCR, add an invisible text layer over the original image. Skip pages that already have text (option). Languages from bundled `tessdata` (start with `eng`; add `afr` if available). |
| PDF to Excel | Own table finder (decision 010: ruled tables from drawn lines, else tables from spacing) → Apache POI `.xlsx`, one sheet per table or per page (option). OCR first if the page has no text layer. |
| PDF to Word | **Custom engine** (see §4.1). OCR mode for scans. |
| PDF to Markdown | Same reading as PDF to Word (`DocumentReader`), written as Markdown: #, lists, **bold**/*italic*, pipe tables; pictures in a folder beside the .md (decision 011). |
| Markdown to PDF | commonmark-java reads it; our own renderer draws it with PDFBox (no LibreOffice). Local pictures only; web pictures shown as their description (decision 011). |

### Edit and sign
| Tool | Notes |
|---|---|
| Page numbers | Position (6 spots), format (`1`, `Page 1 of N`, `- 1 -`), start number, skip first page, font size. |
| Watermark | Text (font, size, colour, opacity, angle) or image; position or tiled; pages range. |
| Sign | Draw (mouse/pen), type (choose a script font), or image upload. Place and resize on a page preview. Visual signature only for v1 (not certificate-based). |
| Redact | Draw boxes or search text and mark all matches. **True redaction:** remove the underlying text and image content, not just cover it. Flatten the page if needed. Warn the user this is permanent in the output. |
| Fill forms | Read AcroForm fields, show them in a side panel, write values, optional flatten. |

### Security
| Tool | Notes |
|---|---|
| Protect | AES-256; open password; optional permissions (printing, copying). |
| Unlock | Needs the known password; removes encryption. Never attempts to crack passwords. |

### Compare
| Tool | Notes |
|---|---|
| Compare PDF | Text diff per page (added/removed highlighted) plus optional visual overlay diff of rendered pages. Side-by-side viewer. |

### 4.1 PDF to Word engine (build in sub-steps)
A PDF stores positioned glyphs, not paragraphs. The goal is **clean, editable output**, not pixel-perfect layout.
1. **Text runs:** use a `PDFTextStripper` subclass to collect each run with x, y, width, font name, size, bold/italic, colour.
2. **Lines and paragraphs:** group runs into lines by baseline; group lines into paragraphs by left edge, line spacing and indentation; handle hyphenated line breaks.
3. **Styles:** most common font size = body text; larger sizes map to Heading 1/2/3; keep bold/italic/underline; detect bullet and numbered lists.
4. **Images:** extract and place inline at their paragraph position.
5. **Tables:** detect with the table finder from PDF to Excel (decision 010) on the same page; replace those text runs with a real Word table.
6. **Columns:** detect two-column layouts and read in column order.
7. **OCR mode:** for pages without a text layer, OCR first, then run the same pipeline.
8. Write with Apache POI (`XWPFDocument`): page size and margins from the PDF, page breaks between PDF pages (option).

Keep a folder of **test PDFs** (simple letter, academic paper with headings, two-column article, invoice with a table, scanned page) and compare outputs after every change.

### 4.2 File-drop suggestions
`FileInspector` reads a dropped file and returns suggested tools, best first:
- Scanned PDF (no text layer) → OCR (recommended), Compress, PDF to Word (OCR).
- Large PDF (> 5 MB) → Compress.
- Several PDFs → Merge.
- Images → Image to PDF.
- Office file → Office to PDF.
- Encrypted PDF → Unlock (after password), Protect settings.
- Always show "Run a pipeline" (if any exist) and "See all tools".

---

## 5. Pipelines and watch folders

**Pipeline** = a named, ordered list of `{toolId, options}` steps, saved as JSON.
- Output of step N is the input of step N+1 (temp files between steps; only the last step writes to the real output location).
- `PipelineValidator` rules (shown as warnings with a one-click fix in the builder):
  - Protect must be the last step (later steps can't modify an encrypted PDF).
  - Steps whose input type doesn't match the previous output (e.g. PDF to Excel then Compress) are errors.
  - Merge must be the first step if used.
- Pipelines can be **pinned** to the home screen.
- Password steps can be set to "ask on each run" (never store passwords in plain text; if stored, use Windows DPAPI).
- "Save as pipeline" on the result screen creates a pipeline from the steps just used.

**Watch folder** = `{inputFolder, pipelineId, outputFolder, enabled}`.
- Implemented with `java.nio.file.WatchService` plus a fallback poll every 10 s (network drives don't always send events).
- Wait until a file is fully written (size stable for 2 s and file can be opened exclusively) before processing.
- Processed originals move to `inputFolder\processed\` (option: leave in place, delete is never offered).
- Failures go to the activity log and leave the file in place.
- Runs while the app is open or minimised to the system tray.

---

## 6. UI design specification

Mockups: `docs/ui/screens/*.dc.html` (see `docs/ui/README.md` for which file is which screen) — follow them closely for layout, wording, colours and spacing. Implement styles in a single `app.css` with CSS variables (`-fx-` looked-up colours) so a dark theme is a second stylesheet.

### 6.0 Logo
Chosen mark: **a document outline with three rising green bars; the tallest bar sprouts a leaf** (growth you can measure + growth that is alive = "prosperity").
- Files (in `src/main/resources/brand/`): `ndalamo-mark.svg` (colour, for light backgrounds), `ndalamo-mark-white.svg` (for dark/blue backgrounds), `ndalamo-app-icon.svg` (white mark on a #2F5BEA rounded square, 256 px), `ndalamo-favicon-small.svg` (simplified two-bar version for 16–24 px).
- Lockup: mark + "Ndalamo" in IBM Plex Sans Bold, `#16181D`, mark height ≈ 1.6× the cap height.
- Colours: outline `#16181D`, bars and leaf `#1E8A4C`; app icon background `#2F5BEA`.
- Where it appears: Home header (white mark on blue tile + "Ndalamo"); every other screen's header (28 px colour mark at the far left, before the back button); Settings → About (48 px mark + "Ndalamo PDF"); system notifications and the tray icon (app icon); window title bar and taskbar (app icon); installer.
- Generate the Windows `.ico` (16, 24, 32, 48, 64, 128, 256) from the SVGs during packaging (chunk 20). Use the small version for 16–24 px.

### 6.1 Design tokens

**Colours (light theme)**
| Token | Value | Use |
|---|---|---|
| `bg` | `#F4F5F7` | Window background |
| `surface` | `#FFFFFF` | Cards, panels, header |
| `border` | `#E2E4E9` | Card borders, dividers |
| `border-strong` | `#D5D8DF` | Inputs, secondary buttons |
| `text` | `#16181D` | Primary text |
| `text-muted` | `#5B616E` | Secondary text, labels |
| `accent` | `#2F5BEA` | Primary buttons, selection, progress |
| `accent-soft` | `#E8EDFB` | Active nav, selected backgrounds |
| `link` | `#2448C9` | Text buttons, links |
| `success` / `success-soft` | `#1E6B3A` / `#E6F4EC` | Done states |
| `warning` / `warning-soft` | `#9A3412` / `#FFF4E5` | Partial results, pipeline warnings |
| `danger` / `danger-soft` | `#B42318` / `#FDECEA` | Errors, destructive buttons |

**Tool category tints** (icon background / icon colour)
- Organize `#E8EDFB` / `#2448C9`
- Optimize `#E6F4EC` / `#1E6B3A`
- Convert `#FDEEE3` / `#B4440C`
- Edit & sign `#F1ECFB` / `#5B35B5`
- Security & compare `#ECEEF2` / `#2E333D`

A dark theme follows the same roles (dark surfaces, lighter accent). "Follow system" reads the Windows theme.

**Typography:** IBM Plex Sans (bundle the font files; it's under the SIL Open Font License), fallback Segoe UI.
- Page title 22–24 px semibold · Section title 15–16 px semibold · Body 14 px · Secondary 12–13 px · Uppercase labels 12 px with letter-spacing.
- Monospace for paths and file-name patterns.

**Shape and spacing:** radius 8 px (buttons, inputs), 10–12 px (cards), 14 px (dialogs), pill 999 px (chips). Spacing scale 4/8/12/16/20/24/28/32. Minimum click target 40 px tall (44 px for primary actions).

**Icons:** simple line icons (2 px stroke). Use a permissively licensed set such as Ikonli with an icon pack whose licence allows bundling. No emoji.

**Buttons**
- Primary: accent fill, white semibold text.
- Secondary: white fill, `border-strong` border.
- Destructive: `danger` fill (only for confirm-delete) or danger-soft with danger text.
- Text button: no border, `link` colour, semibold.

### 6.2 Screens

**1. Home**
- Header: logo + "Ndalamo" · tool search with `Ctrl+K` hint · Pipelines button · Settings icon button.
- Left sidebar: Recent files (name + what was last done), Recent tools (chips), bottom badge "Offline · files never leave this PC".
- Main: large dashed **drop zone** ("Drop files anywhere to start" + Choose files), then tool cards grouped by category. Each card: tinted icon, name, one-line description.
- Pinned pipelines appear as a row above the categories.
- Dropping files anywhere on the window opens screen 3.

**2. Page workspace**
- Header: Home · tabs for open PDFs (+ to open another) · Undo · **Save as new PDF** (primary).
- Action bar: Rotate left, Rotate right, Insert blank page, Add pages from file, Split after selection, Crop, Delete (danger text). Zoom slider for thumbnail size.
- Grid of page thumbnails (A4 ratio), number under each. Click to select, Ctrl/Shift for multi-select, drag to reorder, drag onto another tab to move.
- Selected pages: accent border + check badge.
- Right panel: "N pages selected", Extract to new PDF, Move to [other file], Delete selected (danger-soft); Save-as filename; note "The original file is never overwritten." Footer: page count and size.
- Rendering: thumbnails rendered in the background at low DPI, cached, visible ones first.

**3. File dropped — suggested tools**
- File summary card: thumbnail, name, pages, size, detected traits ("scanned pages, no selectable text"). Add more files button.
- One **Recommended** suggestion (accent border + badge), then 3–4 other suggestions with a one-line reason each. "See all tools" link.

**4. Tool screen — files and options** (one generic screen used by every tool)
- Header: Home · tool name · "Step 1 of 2 · Files and options".
- Left: file count and total size, + Add files, list of file rows (drag handle, thumbnail, name, pages · size, remove button).
- Right panel: options generated from `optionFields()`; common choices visible, rest under "Advanced options"; "Save to" summary with Change link; primary run button with a specific label ("Compress 3 files").

**5. Tool screen — progress**
- "Compressing 3 files…", overall progress bar, "1 of 3 done · about 20 seconds left · you can keep using the app".
- Per-file cards: name, status text (Done · 4.1 → 1.8 MB / Compressing images · 62% / Waiting), small bar (green when done).
- Cancel · Run in background (returns to Home; a background toast tracks it).

**6. Tool screen — result**
- Success icon, "3 files compressed", "11.2 MB → 4.9 MB · 56% smaller", **Show in folder** (primary).
- Result rows: output name, note (green "Under 2 MB" or warning text for partial results), before → after size, Open.
- "Continue with these files" chips (Merge, Add page numbers, Protect, Split…) — passes outputs straight into the next tool.
- Banner: "Do this often? Save … as a one-click pipeline." → opens builder pre-filled.

**7. Pipelines**
- Header nav: Pipelines | Watch folders; + New pipeline (primary).
- Cards: name, badges (Pinned, Watching a folder), steps as chips joined by arrows, "Last run … · N runs", Edit · Duplicate · **Run**.

**8. Pipeline builder**
- Header: back to Pipelines · editable name · Test on a file · **Save pipeline**.
- Main: "Input: … · steps run top to bottom"; numbered step cards (drag handle, tool name, options summary, Options button, remove). Validation warnings appear under the offending step in warning colours with a fix button ("Move to end"). + Add step (dashed). Output summary card.
- Right panel: tool search + tool list to add steps; Pin to home screen checkbox; Link to a watch folder.

**9. Watch folders**
- Header nav as screen 7; + Add watch folder.
- Rows: input folder → pipeline → output folder (paths in monospace chips), status ("Watching · 63 processed" green / "Paused" grey), Pause/Resume.
- Activity log: time, file, pipeline, result (green done with sizes / red failure reason). Export log button.

**10. Settings** (left section nav, cards on the right)
- **Output:** save location (same folder / fixed folder + Browse / ask each time), file-name pattern with live preview and tokens, if-exists behaviour, "Never overwrite the original file (always on)" shown as a disabled checked box.
- **Tool defaults:** compression level, OCR language, PDF to JPG quality, page number style.
- **Office conversion:** LibreOffice status (found/not found), Change location, note that only Office to PDF needs it.
- **Performance:** files processed at once (Automatic / 1 / 2 / 4), low-memory mode.
- **Appearance:** Light / Dark / Follow system.
- **Privacy:** statement that nothing leaves the PC, remember recent files toggle, Clear recent files and tools.
- **Word add-in:** status, Install / Remove.
- **About:** "Ndalamo PDF", version, the line "Ndalamo means prosperity", open-source licences list (with full licence texts).

**11. Toasts and notifications** (bottom-right stack, max 3 visible, newest on top)
| Type | Example | Behaviour |
|---|---|---|
| Success | "3 files compressed" · Show in folder, Open | Auto-hide 6 s. Only shown when the result screen isn't already visible. |
| Partial | "2 of 3 files under 2 MB" · View details, Try Strong | Stays until dismissed. |
| Error | "Couldn't convert Report.docx — LibreOffice stopped responding. Your file wasn't changed." · Try again, Copy error details | Stays until dismissed. |
| Background task | "OCR running · page 4 of 10" + bar · Show, Cancel | Appears when leaving a running task; turns into success/error toast at the end. |
| Undo | "2 pages deleted — nothing is saved until you click Save" · Undo | Workspace only; 8 s or until the next action. |
| System (OS) notification | "Watch folder: 3 files processed · 1 failed" | Only when the window isn't focused (minimised/tray). Drawn by Windows; we only supply icon, title and text. Click opens the activity log. |

Toasts: white card, 12 px radius, shadow, round tinted status icon, title (semibold), body, text-button actions, dismiss ×. Use `role=status`-equivalent accessibility (JavaFX `AccessibleRole` / announce text).

**12. Dialogs** (modal, dimmed background, 14 px radius; primary action on the right)
- **Password required:** file name, "only used for this task and never stored", password field, inline error "That password didn't work. Try again.", Skip this file · Unlock.
- **Confirm delete pages:** "Delete 2 pages?", explains undo is possible until save and original is untouched, "Don't ask again", Cancel · Delete pages (danger).
- **File error:** red icon, "Couldn't open Thesis_final.pdf", plain cause + suggestion, collapsible Technical details (monospace), Copy details · Close · suggested fix (e.g. Repair PDF).
- **LibreOffice missing:** explains it's free and only needed for Office to PDF; I've installed it — check again · Locate it · How to install (opens a local help page bundled with the app, no web link required).
- **Unsaved changes (workspace):** lists what changed; Don't save · Cancel · Save as new PDF.
- **Quit while a task runs:** shows progress; Cancel task and quit · Minimise to tray.

### 6.3 Interaction rules
- Keyboard: `Ctrl+K` command palette/search, `Ctrl+O` open, `Ctrl+S` save (workspace), `Ctrl+Z` undo, `Delete` delete selected pages, `Esc` closes dialogs/toasts.
- Drag and drop works on every screen that accepts files.
- Every long action shows progress within 200 ms.
- Error copy formula: **what happened** + **original file is safe** + **what to do next**.
- Never use the word "fatal" or show a stack trace by default.

---

## 7. Word add-in: "Delete Page"

A .docx has no fixed pages; Word computes pagination when rendering. So this feature lives **inside Word**, not in the Java app.

- **Technology:** C# VSTO add-in for Word on Windows (ribbon button + right-click menu entry).
- **Core logic:** delete the page the cursor is on:
  `ActiveDocument.Bookmarks("\Page").Range.Delete()`
  - Handle edge cases: last page (remove the trailing paragraph mark or page break that creates an empty page), pages that only contain a page/section break, tables spanning pages (warn), the only page in the document (refuse).
- **Extras:** "Delete pages…" dialog accepting ranges (`3, 5-7`), processed from the highest page number down so numbering doesn't shift.
- Wrap each action in one undo record (`Application.UndoRecord`) so a single Ctrl+Z restores it.
- Installed and removed from the Java app's Settings → Word add-in (runs the add-in's installer).

---

## 8. Build plan (one chunk at a time)

Each chunk must end with: code compiles, tests pass, the feature can be run and checked by hand. **Do not start the next chunk until the current one is confirmed working.**

### Phase 1 — Foundation
1. **Project skeleton:** `pom.xml` with pinned versions, `NdalamoApp`, `Theme`, `Header` + `LogoMark` (in `screens/`), `Settings` + `SettingsService`, `OutputNames`, `PdfTool` + `Progress` + `FileResult` + `OptionField`, `JobService` with progress and cancel, logging. App opens a window with the header. *Tests:* OutputNames patterns and collision numbering; JobService cancel.
2. **Home screen and navigation:** design tokens in `Theme` and `app.css`, font bundling, header, sidebar, tool grid from `Tools`, search filtering, screen navigation, window-wide drag-and-drop hook.
3. **Generic tool flow + Merge:** files-and-options screen generated from `optionFields()`, progress screen, result screen, toast system, error dialog. Implement **Merge** end to end.

### Phase 2 — Core PDF tools
4. **Split, Rotate, Remove pages**
5. **Compress** including target-size mode and partial results.
6. **Page numbers, Watermark, Protect, Unlock** + password dialog.
7. **Image to PDF, PDF to JPG**
8. **Page workspace** (thumbnails, selection, drag reorder, tabs, undo stack, save-as, unsaved-changes dialog).

### Phase 3 — Heavy conversions
9. **OCR** (Tess4J, bundled tessdata, invisible text layer).
10. **Office to PDF** (LibreOffice detection, JODConverter, missing-LibreOffice dialog).
11. **PDF to Excel** (Tabula + POI, OCR fallback).
12. **PDF to Word** — split into 12a text runs & paragraphs · 12b headings, styles, lists · 12c images · 12d tables · 12e columns · 12f OCR mode · 12g Markdown to PDF and PDF to Markdown (added at the owner's request).

### Phase 4 — Remaining tools
13. **Sign, Redact, Crop, Fill forms** — built as 13a Crop · 13b Fill forms · 13c Redact · 13d Sign.
14. **Repair, PDF/A, Compare** — built as 14a Repair · 14b PDF/A · 14c Compare.
15. **File-drop suggestions** (`FileInspector` + screen 3).

### Phase 5 — Automation and polish
16. **Pipelines** (model, validator, runner, store, list screen, builder, "save as pipeline" from results, pinned on home).
17. **Watch folders + system tray** (service, activity log, OS notifications, quit-while-running dialog).
18. **Settings screen** complete, **dark theme**, keyboard shortcuts, accessibility pass.

### Phase 6 — Extras and shipping
19. **Word add-in** (separate C# project) + install/remove from Settings.
20. **Packaging:** jpackage Windows installer with bundled JRE, fonts, tessdata, and LibreOffice's installer run silently as a ticked option (decision 009); Start-menu shortcut; optional Explorer right-click "Open with Ndalamo"; licences file.

---

## 9. Rules for building

1. Read this file before starting a chunk. Work on **one chunk only**.
2. Before writing code, briefly state the plan for the chunk and the files that will change.
3. Follow the layout in §3: tools have no JavaFX code, screens have no PDF code. Keep classes plain and easy to follow; no extra layers.
4. Every tool gets unit tests in `src/test/java/com/ndalamo/tools/` using small PDFs (generate them with PDFBox in a test helper where possible).
5. Never add a dependency that is GPL/AGPL or that makes network calls. The libraries in §2 are pre-approved; ask before adding any other dependency.
6. Never write to an input file. Assert this in tests.
7. All user-facing text follows the error-copy formula in §6.3.
8. Run `mvn -q verify` before declaring a chunk done.
9. At the end of each chunk, update the **Progress log** below.

---

## 10. Progress log

| Chunk | Status | Notes |
|---|---|---|
| 1 Project skeleton | Done 2026-11-04 | Single project (decision 003). Window opens with the header and logo; settings saved with Gson; JobService runs tools in the background with progress and cancel; OutputNames never picks the original file. 19 tests. |
| 2 Home & navigation | Done 2026-11-04 | Home matches Main.dc.html: header with search (Ctrl+K, Esc clears), Pipelines and Settings buttons, sidebar, drop zone, tool cards by category. All tools listed as "Coming soon" placeholders (decision 004). IBM Plex Sans bundled. Pipelines, Settings, tool cards and dropped/chosen files open placeholder screens until their chunks. Recent files/tools show empty states until tools run; pinned-pipelines row comes with chunk 16. |
| 3 Tool flow + Merge | Done 2026-11-04 | Merge PDF works end to end: tool screen (drag to reorder, add/remove, page counts, options built from optionFields, Save to with Change), progress (Cancel, Run in background with a progress toast), result (Open, Show in folder, Continue with chips), toasts, error dialog with Try again. Merge joins PDFs and images, skips damaged/password-protected files with a note. PdfTool.run now takes SaveTo; SafeSave writes via a temp file. Per-file progress cards come with Compress (chunk 5); recent files/tools in the sidebar are still empty. 34 tests. |
| 4 Split, Rotate, Remove pages | Done 2026-11-04 | Split by page ranges, every N pages, every page or file size; parts go in a "<name>_split" folder named after their pages. Rotate (right/180°/left, all or listed pages, adds to an existing turn). Remove pages (refuses to remove every page). Rotate and Remove pages have their own Home cards (decision 005). Options panel: hints, options that show only for a matching choice, mistakes caught before running, options scroll on short windows. Open/Show in folder on a deleted result explains it instead of doing nothing. 54 tests. |
| (between 4 and 5) Page preview | Done 2026-11-04 | Tool screens show the pages under the file list: Merge (drag pages to reorder the result), Rotate/Remove (click pages to choose), Split (click a page to start a new file). Clicks fill the page box, so box and preview always agree. Real first-page thumbnails in file rows. |
| 5 Compress | Done 2026-11-04 | Recommended (150 dpi), Strong (100 dpi), Target size (steps down to 72 dpi; PARTIAL "Reached 2.6 MB…" if still too big). Advanced: downscale, greyscale, remove metadata. Already-small files saved as unchanged copies. "Compress to size" card = same tool starting on Target size. Per-file progress cards; choices shown as cards with descriptions. Only pictures are compressed (text and drawings untouched); 1-bit scans and transparent pictures are skipped. 71 tests. |
| 6 Page numbers, Watermark, Protect, Unlock | Done 2026-11-04 | Page numbers: 6 positions, 3 styles, start number, skip first page. Watermark: text or picture; diagonal, centre, top, bottom, tiled; opacity, colour, only some pages. Both draw in the page's visible orientation (turned scans included) and preview the stamp on thumbnails. Text uses embedded IBM Plex Sans, falling back to Noto Sans for letters Plex lacks (e.g. Tshivenda ṱ, ḓ) — decision 006. Protect: AES-256, typed twice, allow printing/copying. Unlock: password dialog per locked file with "Skip this file"; restrictions need the owner password. Options panel restyled to match Tool.dc.html (inputs inside choice cards, styled controls). 87 tests. |
| 7 Image to PDF, PDF to JPG | Done 2026-11-04 | Image to PDF: JPG/PNG/BMP/GIF/TIFF (multi-page) into one PDF; A4, Letter or the picture's shape; landscape for wide pictures; margins; drag pages to reorder. Phone photos come out the right way up (EXIF orientation read without a library). Big photos shrunk to ~200 dpi (Advanced, on by default); unchanged JPEGs embedded byte for byte. PDF to JPG: each page at 72/150/300 dpi, or the pictures inside the PDF; click pages to choose; folder per PDF or one ZIP. Merge now shares the picture handling (TIFFs, phone photos). Result screen suggests tools for the new file type. 101 tests. |
| 8 Page workspace | Done 2026-11-04 | Tabs per PDF (opened in the order chosen; + to open more; × to close), real thumbnails, click/Ctrl/Shift selection, drag to reorder or onto a tab to move pages, zoom. Rotate left/right, insert blank page, add pages from file, split after selection (new tab), crop (drag a box on a large view), delete (confirm with "Don't ask again" saved in Settings, then an 8 s Undo toast). Undo for every edit; Delete, Ctrl+Z, Ctrl+S, Ctrl+A. Right panel: extract selected pages to a new PDF, move to another tab, Save as name. "Save changes?" dialog lists the edits when leaving or closing a tab. Edits change a page list only; WorkspaceService writes the new PDF on save, never the original. Quitting the app with unsaved edits is guarded in chunk 17. 111 tests. |
| 9 OCR | Done 2026-11-04 | OCR PDF: each page drawn at 300 dpi (grey) and read by Tesseract (Tess4J 5.19.0); every word written invisibly over the page at its place, so the page looks the same but text can be searched, selected and copied. Languages: English, Afrikaans, or both (tessdata_fast, bundled, copied to %LOCALAPPDATA%\Ndalamo\tessdata on first use). Skip pages that already have text (on by default). Notes: "N pages read · M words found"; blank or unreadable scans come back as PARTIAL with a hint. If Tesseract can't start (old/missing VC++ runtime), a plain error explains it instead of failing every file. Tess4J 5.20 crashes with JDK 22's older VC++ runtime — decision 007. 117 tests. |
| 10 Office to PDF | Done 2026-11-04 | Word/Excel/PowerPoint, OpenDocument, RTF and CSV to PDF by running `soffice --headless --convert-to pdf` directly (decision 008; no JODConverter). Each file is copied to a temp folder under a plain name and converted there; LibreOffice uses its own profile folder; one conversion at a time; Cancel stops LibreOffice; 3-minute hang limit. Started with SAL_DISABLE_PRINTERLIST=1: unreachable network printers (e.g. campus PaperCut) otherwise made every file take ~190 s, now 1–3 s. Found via Settings ("Locate it"), Program Files, or the registry. Missing → "Office to PDF needs LibreOffice" dialog (Cancel, check again, Locate it, How to install → bundled help page). Office files show a coloured type label instead of a blank thumbnail. LibreOffice will ship inside the installer (decision 009). 125 tests, all run against a real LibreOffice 26.8. |
| 11 PDF to Excel | Done 2026-11-05 | One .xlsx per PDF: each table on its own sheet ("Page 2 table 1") or one sheet per page. Own table finder on PDFBox 3 instead of Tabula, which needs PDFBox 2 (decision 010): ruled tables from crossing lines (pieces joined, double borders merged), else tables from aligned gaps. Numbers become real numbers (SA formats, brackets = minus); leading zeros, currency and long ID numbers stay text. Scanned pages read with OCR first (option). Click pages / page list to choose pages. No tables → SKIPPED with a reason, nothing saved. Apache POI 5.5.1; Log4j pointed at its simple logger. Also: "% smaller" only shown when the file kind stays the same; page-pick hint says "use" not "save". Checked on LibreOffice-made PDFs. 140 tests. |
| 12a PDF to Word: text and paragraphs | Done 2026-11-05 | Words read with font, size, bold, italic and colour (colour instructions switched on in the text reader; PDF font names mapped to Word's: Helvetica → Arial, TimesNewRomanPSMT → Times New Roman). Words split at real spaces (LibreOffice/Word write whole lines in one go; the table finder had the same gap, fixed with it). Lines grouped by baseline; a new paragraph when the gap above is bigger than usual, the size changes, the line before stopped short (its next word would have fitted), or the line is indented. Hyphenated line ends joined. Alignment: left, centre, right, justified (every line but the last reaches the edge). Indents and space above kept. Page size and margins from the PDF; option to start each PDF page on a new page (else a sentence running over a page stays one paragraph); choose pages. Scanned pages come out empty with a note to run OCR first (OCR mode is 12f). Round trip Word → PDF (LibreOffice) → Word: all 8 paragraphs, alignments and styles came back. Table finder: words a normal space apart stay in one cell even when text spills over the next column. 150 tests. |
| 12b PDF to Word: headings, styles, lists | Done 2026-11-05 | Body size = the size most of the document's letters have. Short paragraphs (≤ 20 words) in bigger text → Heading 1/2/3, biggest first; a short all-bold body-size line (e.g. "1. Contributions") → the next level down, its number kept as text. Heading styles are named like Word's own ("heading 1") with outline levels, so the navigation pane and tables of contents work; the PDF's look stays on the runs. Underlines: a horizontal line just under most of a word; lines touching a vertical line are borders, not underlines; the space before or after an underlined phrase isn't underlined. Lists: bullets (•, ◦, ▪, –, Word's private-use Symbol bullet → •, …) and numbers (1. / 1) / a) / (iv) / II.) start a new paragraph and become real Word lists: neighbouring items form one list, items further right a level deeper, numbering starts at the PDF's first number ("3. 4." stays 3, 4). Round trip of a Word handbook with headings, bullets, nested numbered list, a list continuing at 4 and an underline: all came back. 154 tests. |
| 12c PDF to Word: pictures | Done 2026-11-05 | Pictures found from the page's drawing instructions with their box (`PageImages`). JPEG photos copied byte for byte; other pictures saved as PNG with transparency; pictures that can't be decoded (e.g. JPEG 2000) are left out instead of failing the file. Skipped: smaller than 16 pt (bullets, dots, rules) and covering most of the page (scans, backgrounds; scans are 12f). Each picture goes in its own paragraph between the paragraphs above and below it, at its PDF size (shrunk to fit the margins), centred / right / indented like on the page; the space under a picture is measured from the picture. Option "Include pictures" (on). Margins now include pictures, and a page of short lines gets a right margin matching the left. Checked visually: PDF → Word → PDF (LibreOffice) looks like the original. 157 tests. |
| 12d PDF to Word: tables | Done 2026-11-06 | The table finder from PDF to Excel now works on any positioned word and returns the words in each cell, the table's box, whether it had borders, and column widths (Excel just joins the text). In Word: ruled tables always count; tables made of spacing alone need 3 rows and 3 columns, so two-column text and address blocks stay paragraphs. A table's words are taken out of the paragraphs and the table goes in its place (pictures and tables share one "waiting" list sorted by position). Columns as wide as in the PDF (spaced tables get Word's cell padding added back), shrunk to fit the margins; borders only when the PDF drew them; each cell keeps its words' look and its alignment (ruled: from where the text sits in the cell; spaced: a column whose cells all end at the same x is right-aligned). Also fixed in Office to PDF: the temp copy keeps the file's name (unusual characters removed) instead of "document", which spreadsheets printed as the sheet name in the page header. Round trip of a Word marks table and a statement: both came back as real tables. 160 tests. |
| 12e PDF to Word: columns | Done 2026-11-06 | Two-column pages read in column order (`Columns`). Gutter = the widest empty strip in the middle half of the text with at least 4 lines on each side, crossed by at most one line in six (titles, footers), and with real text on both sides (each side's typical line fills at least half its width, so a form with short labels and answers isn't split). The right column may be much shorter than the left (last page of an article). Lines whose words span the gutter's middle are read full width; each two-column stretch is read left column then right, each with its own edges; a paragraph cut off at the foot of the left column and continuing in lowercase at the top of the right is joined. Paragraph rules now measure "stopped short" and "justified" against where full lines actually end on the page (80th-percentile right edge), so a narrow single column isn't one paragraph per line. A line with a tab-like gap (form: "Name:   Thabo") is its own paragraph. Word output stays one column in reading order. Round trip of a Word article in two columns (LibreOffice fills the left column first): title and all 10 paragraphs whole and in order. 163 tests. |
| 12e+ PDF to Word: headers, footers, column placement | Done 2026-11-06 | Page furniture (`PageFurniture`): lines in the top or bottom 10% of a page that are a page number ("3", "Page 3", "3 of 10", "– 3 –", also "bladsy"/"siaṱari") or repeat on at least two pages and half of them (numbers ignored) come out of the body; repeated lines become Word's header/footer text, page numbers Word's own PAGE field (left/centre/right as in the PDF; header if they were at the top). Scan check counts letters before they're removed. Pictures and tables now go in by reading order (`Columns.Page`: which stretch of the page, then which column, then height), so a picture in the right column comes after the left column. 166 tests. |
| 12f PDF to Word: OCR mode | Done 2026-11-06 | Pages with almost no text (< 25 letters) are drawn at 300 dpi and read by the bundled Tesseract (`ScanText`), then go through the same layout as real text: paragraphs, headings, lists, columns, tables, headers/footers. Option "Read scanned pages with OCR" (on) with the OCR PDF language choice (Advanced). Tesseract gives only word boxes, so: size estimated per word from its height and letters (dipping letters like g/y, tall letters and capitals, or only small letters), the middle estimate per line, lines within −25%/+30% of the page's usual size snapped to it so body text is one size and headings stand out; baseline = middle word bottom on the line; font Arial. The scan image itself isn't pasted in (pictures covering most of the page are skipped). OCR engine started once per run, only if a scan turns up. Note: "N scanned pages read with OCR"; with OCR off, scans come out empty with a hint. Known limit: evenly spaced short lines with no gap read like one wrapped paragraph. 167 tests. PDF to Word (chunk 12) complete. |
| 12g Markdown to PDF, PDF to Markdown | Done 2026-11-06 | Added at the owner's request. commonmark-java 0.31.0 approved (decision 011). PDF to Word's reading moved into `DocumentReader` (no change to its output); PDF to Markdown writes from it: # headings (bold not repeated), - and 1. lists (4 spaces per level, PDF's start number kept; letters/Roman as text after -), ** and *, pipe tables, pictures saved byte for byte in "<name>_pictures" and linked, Markdown characters escaped. Markdown to PDF: own renderer on PDFBox (`MarkdownRenderer`): headings kept with the next text, wrapped paragraphs, bold, slanted italics, Courier code on grey, strikethrough, clickable links (one underline per link), bullet/numbered/task lists (drawn tick boxes), quote bars, code blocks across pages, tables with shaded header repeated on new pages and per-column alignment, local pictures (96 dpi, fitted), web pictures shown as their description (PARTIAL), page numbers (option), A4/Letter, title from the first heading, non-UTF-8 files fail plainly. Round trip PDF → Markdown → PDF keeps the words. 177 tests. |
| 13a Crop | Done 2026-11-06 | Crop PDF (Edit & sign): "Trim the white margins" (each page drawn at 48 dpi, the box around everything darker than 238/255 plus 1.2% room; blank pages left alone) or "Keep the area I draw" (new option type AREA: "Draw the area…" opens the workspace's crop dialog on page 1 of the first file, after reading its turn off the JavaFX thread; stored as fractions of the page as shown, so pages turned differently get the same visible area). Sets the crop box only; the screen says the rest is still in the file and points to Redact. Pages option + page picking. 182 tests. |
| 13b Fill forms | Done 2026-11-06 | New option type FORM: "Fill in the form…" reads the first file's fields off the JavaFX thread and opens `FormDialog` (fields in page order; text, long text, tick box, radio and drop-down as a list; labels from the field's tooltip, else its name made readable; read-only fields shown but never written; answers already in the PDF filled in; "N of M answered"). `FillFormsTool` writes answers by field name (so several copies of one form can be filled at once), stores drop-down answers as the value the form expects, keeps letters the form's font can't show (e.g. Tshivenda) by letting the reader draw them, optionally locks the answers in (flatten). XFA forms get a plain explanation; a form with both kinds has its XFA copy dropped so readers show the new answers; a PDF without a form is skipped. 189 tests. |
| 13c Redact | Done 2026-11-06 | True redaction (decision 012): typed words/phrases (whole words, spaces match line breaks, optional exact case), South African ID numbers, phone numbers and email addresses, or a drawn area (AREA option). Pages with something to remove are redrawn as 200 dpi pictures with black boxes painted in; old content, fonts, pictures and annotations go; other words written back as invisible text so the page stays searchable; same page object reused so bookmarks work. Untouched pages stay as they were. Form fields locked in first. Hidden details (author, title, keywords, XMP) cleared by default; bookmark titles get "[redacted]". Nothing found → nothing saved. Found by looking at a render: boxes from PDFBox letter heights left word tops visible; now sized from the font size, with a pixel test. Tests also check the removed words are absent from the extracted text and the file's raw bytes (with a control showing the check sees them when present). 199 tests. |
| 13d Sign | Done 2026-11-06 | Sign PDF: new option type SIGNATURE. `SignatureDialog`: Draw (mouse/pen pad with a signing line, Clear), Type (name in Dancing Script, bundled at the owner's choice, decision 013; Noto Sans if the name has letters it lacks), Picture (photo or scan of a signature on paper: paper made see-through, soft ink edges kept; blank paper refused); black or blue ink. `PlaceSignatureDialog`: the page shown large, drag to move, drag the corner to resize (shape kept), arrows between pages, starts on the last page near the bottom right. `SignatureMaker` makes the transparent, cropped PNG (no JavaFX). `SignTool` draws it in the page's visible orientation; optional date under it; a shorter file gets it on its last page. Screen note: a picture of a signature, not a certificate-based digital signature. 208 tests. Chunk 13 complete. |
| 14a Repair | Done 2026-11-06 | Checks the file first so it can say what was wrong (no PDF header, cut off before %%EOF, table of contents missing or pointing to the wrong place), lets PDFBox's lenient reader rebuild the table of contents, draws every page once to name any still-damaged page (PARTIAL), saves a clean copy. Locked files: unlock first. Not a PDF, or no pages found: plain failure. Healthy file: "No damage found; a clean copy was saved". 212 tests. |
| … | | |

---

## 11. Open decisions
- Tagline.
- Which OCR languages to bundle beyond English and Afrikaans (installer size vs. usefulness; decision 007).
- Whether to open-source the project.
- Future paid tier (not now).
