# 010. Our own table finder instead of Tabula
Date: 2026-11-05
## Context
SPEC §2 names Tabula-java for finding tables (PDF to Excel now, PDF to Word later). Its last release, 1.0.5 (August 2021), is built on PDFBox 2.0. PDFBox 2 and 3 use the same package names, so both can't be in the app. Tabula also calls PDFBox 2 methods that PDFBox 3 removed (e.g. `PDDocument.load`). It also brings slf4j-simple, which clashes with Logback. Using it would mean renaming one PDFBox apart in the build (two copies of PDFBox) or porting about 10,000 lines of Tabula to PDFBox 3.
## Decision
- Write a small table finder on PDFBox 3 (`tools/PageContent.java` reads words and lines, `tools/TableFinder.java` finds the tables), using Tabula's two ideas:
  - **Ruled tables:** horizontal and vertical lines that cross make a grid; each word goes in the cell its middle falls in. Borders drawn as many short pieces are joined, and double borders count once. A box around a single paragraph is not a table.
  - **Spaced tables** (no lines), only when a page has no ruled table: lines whose words have wide gaps, with gaps that line up from line to line. One line in five may run across a gap (a long description) without merging two columns.
- Scanned pages are read with the bundled OCR first and go through the same finder (spacing only, since a scan has no drawn lines).
- Numbers become real Excel numbers ("1 250,50", "1,250.50", "(40)" = −40). Anything with letters or currency signs, numbers with leading zeros, and digit strings of 11 or more (ID and phone numbers) stay as text.
- Apache POI 5.5.1 writes the .xlsx (6.0.0 was under two weeks old). POI logs through Log4j, so the app points Log4j at its built-in simple logger (`NdalamoApp.main`) instead of adding a bridge library.
## Consequences
- No extra libraries for table finding, and PDF to Word can reuse it (chunk 12d).
- Simpler than Tabula. Cells that wrap onto two lines in a table without lines become two rows. Merged cells in ruled tables come out in the first cell they cover. Tables on turned pages are found by spacing only.
- Checked on PDFs made by LibreOffice (a bank-style statement exported from a spreadsheet, and a Word table with borders): every cell came out right.
- If a Tabula release built on PDFBox 3 appears, it can be compared against `TableFinderTest` and `PdfToExcelToolTest`.
