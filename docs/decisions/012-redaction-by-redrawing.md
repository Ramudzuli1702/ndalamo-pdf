# 012. Redact by redrawing the affected pages
Date: 2026-11-06
## Context
SPEC §4 asks for true redaction: what's under a black box must be gone from the file, not just covered (a box drawn over text leaves the text there to copy, a well-known way redactions fail). Removing text precisely from a PDF's drawing instructions means rewriting its text operators letter by letter, across fonts that may have no usable character map, and dealing with pictures and drawn shapes under the box too. That is hard to get right for every PDF, and a mistake leaks exactly what the user wanted gone.
## Decision
- Every page with something to remove is redrawn as a picture at 200 dpi, with the black boxes painted into the picture. The page's old drawing instructions, fonts, pictures and annotations are all thrown away, so nothing under a box can survive. The same page object is reused, so bookmarks keep pointing to it.
- The page's other words are written back as invisible text (as OCR PDF does), so the page can still be searched and copied apart from what was removed.
- Boxes are sized from the font size (top of tall letters to below g/p/y), plus padding. PDFBox's letter height alone ran short and left the tops of words readable; a test now checks pixel by pixel that nothing of a removed word shows.
- Pages with nothing to remove are not touched. Form fields are locked into the page first, so typed answers can be redacted. By default the stored author, title, keywords and XMP details are cleared, and redacted words are replaced in bookmark titles.
## Consequences
- Redacted pages are pictures: they print sharply, but they're bigger, and their text can't be edited any more (it can still be searched).
- Vector drawings on redacted pages become part of the picture.
- Scanned pages can't be searched for words; the result says so and suggests drawing areas or running OCR PDF first.
