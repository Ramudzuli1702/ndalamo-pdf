# 018. PDF to Word keeps the PDF's look, and stays editable
Date: 2026-11-10
## Context
SPEC section 4.1 aimed for "clean, editable output, not pixel-perfect layout". The owner
compared a PDF with its Word copy and found two different-looking documents: Times New Roman
instead of the PDF's IBM Plex Sans, a strange heavy font for bold, cramped lines, and no code
backgrounds, quote bar, dividing lines or table shading. The owner wants the Word copy to look
like the PDF. Word could fake that with text boxes placed at fixed positions, but the copy
would then be hard to edit.
## Decision
Keep real Word paragraphs, lists and tables, and carry the PDF's look over onto them:
- Fonts: the family the font inside the PDF names itself, matched ignoring spaces
  ("IBMPlexSans" is "IBM Plex Sans"). Fonts Ndalamo ships are embedded whole in the .docx
  (Word's scrambled font parts), so they show on any PC; Windows fonts are just named; a font
  only the PDF has is embedded from the PDF if it names itself properly; otherwise a close
  Windows font. Never fonts that forbid embedding.
- Sizes to the half point (PDFBox's getFontSizeInPt drops fractions; the text scale doesn't).
- Line spacing: exactly the PDF's distance between baselines; the space above each paragraph
  puts its first baseline where the PDF has it.
- Boxes and lines (new PageShapes and WordShapes): a background behind lines becomes paragraph
  shading with the PDF's padding; a small background behind words, run shading; a thin bar
  beside text, a left border; a long thin line, a divider; inside tables, cell shading, border
  colour and thickness, and the cells' padding.
- Wide gaps on a line (forms, columns lined up with spaces) become tab stops at the PDF's
  positions; spaces between words only where the PDF has a gap.
- Drawings (icons, logos, arrows) become pictures, rendered without the page's text.
## Consequences
- A PDF made by Ndalamo's Markdown to PDF now comes back from Word page for page, line for line
  (the specification's 15 pages, checked by rendering both).
- Word files carry embedded fonts, so they're bigger (about 0.4 MB for Plex regular and bold).
- Line heights are fixed, so text made much larger in Word overlaps until the spacing is changed.
- Found on the way and fixed: two same-width tables on one page lost the upper table (TableFinder
  joined border pieces by position only); ligatures ("ﬁ") now come out as plain letters.
