# 016. Compare PDF writes a side-by-side report PDF
Date: 2026-11-07
## Context
SPEC section 4 asks for a text diff per page, an optional visual diff, and a side-by-side
viewer. Every other tool fits the same flow (files and options, run, result files), which
pipelines and watch folders rely on. A separate viewer screen would be the only tool
without an output file, and the comparison couldn't be kept, sent or printed.
## Decision
Compare is a normal tool. The first file is the original; each other file is compared with
it and gets a report PDF: a summary page (what changed, page by page, with a colour key),
then each changed page of the original beside its new version, removed words marked red,
added words green, changed pictures and drawings outlined orange.
The text is compared as one sequence of lines for the whole document (Myers' diff), then
word by word inside changed lines, so an inserted page doesn't mark every later page as
changed. Pages are paired by the words they share. The picture check compares both pages
drawn at 40 dpi and keeps only changed areas that are mostly not text.
## Consequences
- The side-by-side "viewer" is any PDF viewer, including the app's result screen "Open".
- Reports work in pipelines and can be shared; pages without changes are left out.
- Pages are shown as pictures (110 dpi) with the marks on top, so the report looks the same
  everywhere, but its text can't be selected.
- Scanned pages have no text to compare; only the picture check finds their changes.
