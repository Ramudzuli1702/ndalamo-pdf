# 005. Rotate and Remove pages get their own Home cards; tool output drops copy/print restrictions
Date: 2026-11-04
## Context
SPEC §4 lists Rotate and Remove pages as tools, but the Home mockup only has a "Page workspace" card for reorder/rotate/remove/crop, and the workspace isn't built until chunk 8. Separately, a PDF can open without a password yet still carry owner restrictions (no copying, no printing); every Organize tool needs to decide what its output does with those.
## Decision
- "Rotate pages" and "Remove pages" are tools with their own cards in the Organize section, next to Merge, Split and Page workspace. Home differs slightly from the mockup.
- Organize tools open PDFs with `setAllSecurityToBeRemoved(true)` (in `PdfFiles.open`): the output is a new file without the original's copy/print restrictions. Files that need a password to open are skipped with "needs a password"; nothing tries to get around a password.
## Consequences
- Both tools are usable now, findable by search, and can become pipeline steps later.
- Page workspace remains the place for visual, page-by-page editing.
- A restricted PDF's merged/split/rotated copy is unrestricted. If that turns out to matter, a later chunk can keep the restrictions instead (Protect PDF, chunk 6, can also re-add them).
