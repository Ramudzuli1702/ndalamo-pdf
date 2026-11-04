# 009. Ship LibreOffice inside Ndalamo's installer
Date: 2026-11-04
## Context
Office to PDF needs LibreOffice (decision 008). Asking users to find, download and install it themselves is a poor first experience. Ndalamo itself must never download anything (offline only), so it can't fetch LibreOffice when it's needed.
## Decision
- The Windows installer (chunk 20) carries LibreOffice's official, unchanged `.msi`. A ticked option, "Also install LibreOffice (for Office to PDF)", runs it silently (`msiexec /i … /qn`) during Ndalamo's own install, under the same admin prompt.
- LibreOffice is installed normally into Program Files, so `LibreOffice.find()` picks it up with no setup. It can be removed separately from Windows' Apps list.
- The "Office to PDF needs LibreOffice" dialog stays. It covers people who unticked the option or removed LibreOffice later.
- Redistributing LibreOffice's unchanged installer is allowed by its licence (MPL 2.0). It still runs only as a separate program. Its licence text goes in Ndalamo's licences file.
## Consequences
- The installer grows by about 370 MB (to roughly 520 MB). Consider also offering a small "Ndalamo only" installer for people on mobile data; the dialog then explains how to add LibreOffice later.
- Each Ndalamo release should ship a current LibreOffice for its security fixes.
