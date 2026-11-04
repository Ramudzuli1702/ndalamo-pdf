# 001. Use Apache PDFBox, not iText
Date: 2026-11-04
## Context
Ndalamo needs a Java library to read, edit and write PDFs. The two main options are Apache PDFBox and iText.
## Decision
Use Apache PDFBox (Apache 2.0). Do not use iText.
## Consequences
- iText 7+ is AGPL: using it would force Ndalamo to be open-sourced under AGPL or require a paid licence. PDFBox has no such restriction.
- Some features (e.g. PDF/A, redaction) need more of our own code with PDFBox.
- All future dependencies must be permissively licensed (see SPEC section 2).
