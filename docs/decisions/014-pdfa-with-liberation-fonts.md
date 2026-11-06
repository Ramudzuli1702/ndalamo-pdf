# 014. PDF/A-2b with Liberation fonts and our own checks
Date: 2026-11-06
## Context
PDF to PDF/A must embed every font (SPEC §4). Many PDFs only name Helvetica, Times or Courier and leave the reader to supply them. PDF/A checkers also verify that the widths written in the PDF match the embedded font's letters, so any substitute must have identical widths. PDFBox has no PDF/A-2 validator (its preflight module only checks PDF/A-1b), and full validators such as veraPDF are large separate programs.
## Decision
- Bundle the **Liberation fonts 2.1.5** (SIL OFL 1.1, Red Hat / Google; licence in `src/main/resources/fonts/liberation/Liberation-OFL.txt`): Sans, Serif and Mono in regular, bold, italic and bold italic. They are made to have exactly the widths of Helvetica/Arial, Times and Courier. The owner approved this on 2026-11-06.
- `FontEmbedder` turns each such font into an embedded TrueType font where it's defined, so every page using it keeps working. Only the letters its encoding can show are embedded, the widths come from Liberation itself, and the encoding becomes WinAnsi plus differences (what PDF/A allows for these fonts). A test checks every letter stays where it was.
- Fonts with no exact twin (Symbol, Zapf Dingbats, Calibri, narrow or condensed faces, other non-embedded fonts) mean the file can't comply: nothing is saved, and the fonts are named.
- The sRGB colour profile comes from Java itself. The XMP metadata is written by hand (no xmpbox dependency), mirroring the document information as PDF/A requires. Encryption, JavaScript, attachments, sound/video annotations and hidden annotations are removed.
- The final check is our own list of the rules above: fonts embedded, colour profile, PDF/A metadata, no JavaScript or attachments. It is not a full PDF/A validation.
## Consequences
- The app grows by 4.2 MB.
- Most office and university PDFs convert. Some rare PDF/A rules aren't checked (e.g. LZW-compressed streams, unusual colour spaces). A later improvement: test outputs against veraPDF during development.
