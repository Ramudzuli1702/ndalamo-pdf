# 017. How Ndalamo is installed, opened from Explorer, and kept to one copy
Date: 2026-11-10
## Context
Chunk 20 packages Ndalamo for Windows with jpackage (SPEC section 2). Decision 009 wants
LibreOffice's installer offered as a ticked option. The owner chose an "Ndalamo only"
installer first, with the full one made as soon as a LibreOffice .msi is added, and an
"Open with" entry for PDFs that doesn't change anyone's default viewer. Watch folders (chunk
17) mean two running copies would process the same files twice.
## Decision
- jpackage makes the Ndalamo installer (bundled Java runtime, Start menu, optional desktop
  shortcut, folder choice, fixed upgrade code). Its installer template is kept as it is, with
  one fragment added: "Open with" entries through `OpenWithProgids`, which only add Ndalamo to
  the list. jpackage's own file associations were not used, because they make the app a
  file type's handler.
- The full setup is a WiX Burn bundle chaining Ndalamo's .msi and LibreOffice's unchanged .msi
  (an .msi can't start another .msi itself). LibreOffice runs silently, without taking over
  Office file types or checking for updates, and stays installed if Ndalamo is removed.
- One copy at a time: the first copy holds a lock on a file in %APPDATA%\Ndalamo. A second copy
  (say, from "Open with") writes its files to a note in a folder there and quits; the first
  copy opens them. Files only, no network socket, keeping the offline promise.
## Consequences
- Two setups per release when LibreOffice is present: about 115 MB, and about 115 MB plus
  LibreOffice. The LibreOffice .msi isn't committed; it's added before building.
- The installer isn't code-signed, so Windows SmartScreen may warn on first run until it is.
- Each "Open with" of several files starts a copy per file; all hand over to the one running.
