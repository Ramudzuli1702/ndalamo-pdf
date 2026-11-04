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
6. **Permissive dependencies only** (Apache 2.0, MIT, BSD, MPL used as a separate process). **Do not use iText** (AGPL) or any GPL library linked into the app.

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
| Table extraction | Tabula-java | MIT |
| Office files (read/write) | Apache POI | Apache 2.0 |
| Office to PDF | LibreOffice (headless, external process) via JODConverter | Apache 2.0 (JODConverter); LibreOffice runs as a separate program |
| JSON (settings, pipelines) | Jackson | Apache 2.0 |
| Logging | SLF4J + Logback (local log file only) | MIT / EPL-LGPL dual |
| Testing | JUnit 5, AssertJ, TestFX (UI smoke tests) | EPL / Apache |
| Packaging | jpackage (bundled JRE, Windows installer first) | — |
| Word add-in | C# VSTO add-in (separate project) | — |

Use the latest stable release of each library that is **at least two weeks old**. Pin exact versions in the parent `pom.xml` `<dependencyManagement>`.

**Platform:** Windows first (Word add-in, Explorer integration, tray). Keep core code platform-neutral so macOS/Linux builds are possible later.

---

## 3. Architecture

### 3.1 Maven modules

```
ndalamo-pdf/
├── pom.xml                 (parent: versions, plugins)
├── core/                   (no JavaFX; pure logic, fully unit-tested)
├── tools/                  (one class per tool; depends on core)
├── app/                    (JavaFX UI; depends on core + tools)
├── word-addin/             (C# VSTO project, built separately — Phase 6)
├── docs/
│   ├── SPEC.md             (this file)
│   ├── decisions/          (decision records)
│   └── ui/                 (mockup sources + index)
```

### 3.2 Packages (`core`)

```
com.[org].ndalamo.core
├── tool        PdfTool, ToolCategory, ToolDescriptor, ToolRegistry
├── options     ToolOptions, OptionField (text, number, choice, checkbox, file, password)
├── job         Job, JobRunner, JobProgress, JobResult, FileResult, CancellationToken
├── io          OutputNamer, FileInspector (page count, size, has text layer, encrypted?)
├── pipeline    Pipeline, PipelineStep, PipelineValidator, PipelineRunner, PipelineStore
├── watch       WatchFolder, WatchFolderService, ActivityLog
├── settings    Settings, SettingsStore (JSON in user app-data folder)
└── error       ToolException, UserMessage (plain text + optional technical detail + suggested action)
```

### 3.3 Key interfaces (shape, not final code)

```java
public interface PdfTool {
    ToolDescriptor descriptor();            // id, name, short description, category, accepted input types
    List<OptionField> optionFields();       // drives the options panel automatically
    ToolOptions defaultOptions(Settings s);
    void validate(List<Path> inputs, ToolOptions opts) throws ToolException;
    JobResult run(List<Path> inputs, ToolOptions opts, JobProgress progress, CancellationToken cancel)
        throws ToolException;
}

public record FileResult(Path input, Path output, long sizeBefore, long sizeAfter,
                         Status status, UserMessage message) {
    public enum Status { SUCCESS, PARTIAL, FAILED, SKIPPED }
}

public record UserMessage(String summary, String technicalDetail, SuggestedAction action) {}
```

Rules:
- A tool **never** touches JavaFX. It reports through `JobProgress` (fraction 0–1 plus a short status line, e.g. "Compressing images · 62%").
- A tool checks `cancel.isCancelled()` between pages and stops cleanly, deleting any half-written output.
- Every tool writes to a **temp file first**, then moves it to the final name only on success.
- One bad file in a batch must not stop the others: record it as `FAILED` or `SKIPPED` and continue.
- `ToolRegistry` lists all tools; the home screen, search, pipeline palette and drop suggestions all read from it.

### 3.4 Threading

- `JobRunner` uses a bounded executor. Parallelism comes from Settings: `Automatic` = `max(1, cores / 2)`, or a fixed 1/2/4.
- Progress updates are pushed to the UI with `Platform.runLater`, throttled to at most ~10 per second.
- "Low-memory mode" (Settings) makes PDFBox use temp-file-backed memory (`MemoryUsageSetting.setupTempFileOnly()`) and forces parallelism to 1.

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
| Office to PDF | Word, Excel, PowerPoint via LibreOffice headless (JODConverter). Detect LibreOffice automatically; if missing, show the "needs LibreOffice" dialog. Everything else must work without it. |
| OCR PDF | Tess4J; render each page at 300 DPI, OCR, add an invisible text layer over the original image. Skip pages that already have text (option). Languages from bundled `tessdata` (start with `eng`; add `afr` if available). |
| PDF to Excel | Tabula-java (lattice for ruled tables, stream for whitespace tables, auto-try both) → Apache POI `.xlsx`, one sheet per table or per page (option). OCR first if the page has no text layer. |
| PDF to Word | **Custom engine** (see §4.1). OCR mode for scans. |

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
5. **Tables:** detect with Tabula on the same page; replace those text runs with a real Word table.
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
- Files (in `app/src/main/resources/brand/`): `ndalamo-mark.svg` (colour, for light backgrounds), `ndalamo-mark-white.svg` (for dark/blue backgrounds), `ndalamo-app-icon.svg` (white mark on a #2F5BEA rounded square, 256 px), `ndalamo-favicon-small.svg` (simplified two-bar version for 16–24 px).
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
1. **Project skeleton:** parent POM with pinned versions, `core`/`tools`/`app` modules, `PdfTool` and supporting interfaces, `JobRunner` with progress and cancel, `OutputNamer`, `SettingsStore`, logging. App opens an empty window with the header. *Tests:* OutputNamer patterns and collision numbering; JobRunner cancel.
2. **Home screen and navigation:** design tokens in `app.css`, font bundling, header, sidebar, tool grid from `ToolRegistry`, search filtering, screen navigation, window-wide drag-and-drop hook.
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
12. **PDF to Word** — split into 12a text runs & paragraphs · 12b headings, styles, lists · 12c images · 12d tables · 12e columns · 12f OCR mode.

### Phase 4 — Remaining tools
13. **Sign, Redact, Crop, Fill forms**
14. **Repair, PDF/A, Compare**
15. **File-drop suggestions** (`FileInspector` + screen 3).

### Phase 5 — Automation and polish
16. **Pipelines** (model, validator, runner, store, list screen, builder, "save as pipeline" from results, pinned on home).
17. **Watch folders + system tray** (service, activity log, OS notifications, quit-while-running dialog).
18. **Settings screen** complete, **dark theme**, keyboard shortcuts, accessibility pass.

### Phase 6 — Extras and shipping
19. **Word add-in** (separate C# project) + install/remove from Settings.
20. **Packaging:** jpackage Windows installer with bundled JRE, fonts, tessdata; Start-menu shortcut; optional Explorer right-click "Open with Ndalamo"; licences file.

---

## 9. Rules for building

1. Read this file before starting a chunk. Work on **one chunk only**.
2. Before writing code, briefly state the plan for the chunk and the files that will change.
3. Keep `core` free of JavaFX. Keep tools free of UI code.
4. Every tool gets unit tests using small PDFs in `core/src/test/resources` / `tools/src/test/resources` (generate test PDFs with PDFBox in a test helper where possible).
5. Never add a dependency that is GPL/AGPL or that makes network calls. Ask before adding any new dependency.
6. Never write to an input file. Assert this in tests.
7. All user-facing text follows the error-copy formula in §6.3. Keep strings in a single resource bundle (`messages.properties`) to allow translation later.
8. Run `mvn -q verify` before declaring a chunk done.
9. At the end of each chunk, update the **Progress log** below.

---

## 10. Progress log

| Chunk | Status | Notes |
|---|---|---|
| 1 Project skeleton | Not started | |
| 2 Home & navigation | Not started | |
| 3 Tool flow + Merge | Not started | |
| … | | |

---

## 11. Open decisions
- Tagline.
- Java package root (`com.[org]`).
- Which OCR languages to bundle beyond English (installer size vs. usefulness).
- Whether to open-source the project.
- Future paid tier (not now).
