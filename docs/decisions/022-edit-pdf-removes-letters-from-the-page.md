# 022. Edit PDF takes letters out of the page itself
Date: 2026-11-11

## Context
The owner asked for an Edit PDF tool that removes selected or highlighted text and adds
new text. Redact already removes text for good, but it redraws each touched page as a
picture: right for blacking out, wrong for tidying a document, where the rest of the page
should stay sharp, searchable and small. Covering text with a white box would leave it in
the file to copy.

## Decision
`TextEdits` follows each page's own drawing instructions with a small PDFBox stream
engine (text and graphics-state operators only; embedded drawings are not followed, so
every recorded instruction belongs to the page). It records where each letter lands. To
remove, every text-showing instruction holding a chosen letter is written again as TJ:
kept letters as strings, each run of removed letters as a spacing number of exactly the
removed width (glyph width × size + character and word spacing), so the rest of the line
and the lines after stay where they were. `'` and `"` are written as T* (and Tw, Tc)
followed by the TJ. Highlighter marks over removed text are removed with it.

Added text is drawn on top in the page's visible orientation (PageCanvas) with a bundled
font: Liberation Sans, Serif or Mono, or Dancing Script, falling back to Noto Sans for
letters a font lacks (Tshivenda's ṱ, ḓ).

The editor (`EditDialog`) works in fractions of the page as shown, like Sign: drag over
text or click a word, Delete; "Select highlighted text"; click and type to add, drag to
move, double-click to change; Ctrl+Z. Saving goes through the preview (decision 021).

## Consequences
Text inside embedded drawings (form XObjects), Type 3 and vertical fonts can't be removed
this way, and scanned pages have no letters at all; the editor says so and points to
Redact. Removing leaves a gap: PDFs don't reflow, so nothing moves up to fill it.
