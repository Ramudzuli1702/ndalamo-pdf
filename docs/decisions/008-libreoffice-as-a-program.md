# 008. Run LibreOffice as a program instead of through JODConverter
Date: 2026-11-04
## Context
Office to PDF needs LibreOffice to read Word, Excel and PowerPoint files. SPEC §2 suggests JODConverter. It starts LibreOffice as a server and talks to it over a local network port, using five extra jars (JODConverter core and local, plus OpenOffice's UNO jars). LibreOffice also has a simple command-line mode: `soffice --headless --convert-to pdf --outdir <folder> <file>`.
## Decision
- Run `soffice.exe --headless --convert-to pdf` directly with `ProcessBuilder`, one file at a time (`tools/LibreOffice.java`). No new dependencies.
- The input is copied into a fresh temp folder under a plain name (`document.docx`) and converted there. LibreOffice never sees the original, and unusual characters in file names can't trip it up. The PDF is then saved to the usual output name with `SafeSave`.
- LibreOffice gets its own settings folder (`%LOCALAPPDATA%\Ndalamo\libreoffice-profile`), so it works while the user has LibreOffice open and never shows first-start screens. Only one conversion runs at a time, because two LibreOffices can't share that folder.
- LibreOffice is started with `SAL_DISABLE_PRINTERLIST=1`. On start it asks Windows about every printer, and a network printer that can't be reached (found on the owner's laptop: two campus PaperCut printers, away from campus) made every conversion take about 190 seconds. A PDF export never prints, so the printer list isn't needed; with it switched off a file takes 1–3 seconds.
- Cancel stops LibreOffice (soffice.exe and the soffice.bin it starts). A file that takes over 3 minutes counts as hung.
- LibreOffice is found in this order: the soffice.exe the user picked with "Locate it" (saved in Settings), then Program Files, then the folder the installer wrote to the registry.
- LibreOffice is not bundled (about 350 MB). If it's missing, the "Office to PDF needs LibreOffice" dialog explains, with a help page bundled in the app (`resources/help/install-libreoffice.html`).
## Consequences
- LibreOffice starts fresh for every file, about 1–3 seconds each. JODConverter would keep it running, so large batches would be faster. Switch if that becomes a problem; the change stays inside `LibreOffice.java`.
- No network port is opened, even a local one, so Windows Firewall never asks.
- The licence position is the simplest possible: LibreOffice (MPL) only ever runs as a separate program (SPEC §1.6).
