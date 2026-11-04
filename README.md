# Ndalamo PDF

A free, fully offline Windows desktop app for working with PDFs: merge, split, compress (including "under 2 MB" for upload portals), convert, OCR, sign, redact, protect and more. It also has pipelines and watch folders for repeated work, plus a small Microsoft Word add-in that adds a "Delete Page" button.

*Ndalamo* means "prosperity".

## Principles
- **100% offline.** Files never leave the PC: no telemetry, update checks or cloud services.
- **The original file is never overwritten.** Every tool writes a new file.
- **The UI never freezes.** Work runs in the background with progress and cancel.
- **Plain-language errors** that say what happened, confirm the original is safe, and suggest a next step.

## Tech stack
Java 22 · JavaFX 22 · Maven · Apache PDFBox · Tess4J (OCR) · Tabula-java · Apache POI · Gson · JUnit 5. The Word add-in is a separate C# VSTO project.

## Project layout
| Folder | What it holds |
|---|---|
| `src/main/java/com/ndalamo/screens/` | What the user sees: screens, dialogs, toasts |
| `src/main/java/com/ndalamo/tools/` | The PDF work, one class per tool |
| `src/main/java/com/ndalamo/services/` | Background jobs, saving settings, pipelines, watch folders |
| `src/main/java/com/ndalamo/models/` | Plain data classes |
| `src/main/java/com/ndalamo/utils/` | Small helpers: colours, output file names |
| `docs/SPEC.md` | The full specification and build plan (source of truth) |
| `docs/decisions/` | Decision records |
| `docs/ui/` | UI mockups for every screen |

## Build and run
Requires JDK 22 and Maven 3.9+.

```
mvn verify        # build and run all tests
mvn javafx:run    # start the app
```

## Status
Early development. See the progress log in [docs/SPEC.md](docs/SPEC.md#10-progress-log).
