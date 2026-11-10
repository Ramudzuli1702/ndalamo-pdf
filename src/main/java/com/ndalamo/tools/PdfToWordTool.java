package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.Paragraph;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF to Word: turns a PDF into an editable .docx (SPEC §4.1). Built in steps.
 * So far: paragraphs, alignment, indents, each word's font, size, bold, italic,
 * underline and colour (12a), headings and lists (12b, see WordStyles), pictures in
 * their place between paragraphs (12c), tables as real Word tables (12d), two-column
 * pages in column order with headers, footers and page numbers moved into Word's own
 * (12e), and scanned pages read with OCR first (12f, see ScanText). The goal is a clean document to
 * edit, not an exact copy of the page. The PDF is only read.
 */
public class PdfToWordTool implements PdfTool {

    static final String PAGE_BREAKS = "pageBreaks";
    static final String PAGES = "pages";
    static final String PICTURES = "pictures";
    static final String OCR = "ocr";
    static final String LANGUAGE = "language";

    @Override public String id() { return "pdf-to-word"; }
    @Override public String name() { return "PDF to Word"; }
    @Override public String description() { return "Editable .docx"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "editable"; }
    @Override public String outputExtension() { return "docx"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(PAGE_BREAKS, "Start each PDF page on a new page", OptionField.Type.CHECKBOX, true),
                new OptionField(PICTURES, "Include pictures", OptionField.Type.CHECKBOX, true),
                new OptionField(OCR, "Read scanned pages with OCR (slower)", OptionField.Type.CHECKBOX, true),
                new OptionField(LANGUAGE, "Language of scanned pages", OptionField.Type.CHOICE,
                        List.of(OcrTool.ENGLISH, OcrTool.AFRIKAANS, OcrTool.BOTH), OcrTool.ENGLISH)
                        .describe("", "", "Slower; for documents that mix both").advanced(),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        String pages = (String) options.get(PAGES);
        return pages == null || pages.isBlank() ? null : PageRanges.check(pages);
    }

    @Override
    public List<Integer> previewPages(Map<String, Object> options, int pageCount) {
        String pages = (String) options.get(PAGES);
        if (pages == null || pages.isBlank()) return List.of();
        try {
            return PageRanges.pages(PageRanges.parse(pages, pageCount));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @Override
    public String buttonLabel(int files) {
        return "Convert " + Format.count(files, "file", "files") + " to Word";
    }

    @Override
    public String workingLabel(int files) {
        return "Converting " + Format.count(files, "file", "files") + " to Word…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to Word";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean pageBreaks = !Boolean.FALSE.equals(options.get(PAGE_BREAKS));
        String pageChoice = (String) options.get(PAGES);
        boolean pictures = !Boolean.FALSE.equals(options.get(PICTURES));
        boolean ocr = !Boolean.FALSE.equals(options.get(OCR));
        Object language = options.get(LANGUAGE);
        String languages = OcrTool.AFRIKAANS.equals(language) ? "afr" : OcrTool.BOTH.equals(language) ? "eng+afr" : "eng";
        OcrEngine[] engine = new OcrEngine[1];          // started only when a scanned page turns up, shared by all files

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));
                FileResult result = convertOne(doc, input, pages, i, inputs.size(), pageBreaks, pictures,
                        ocr ? languages : null, engine, saveTo, progress);
                if (result == null) break;                // cancelled partway: nothing written for this file
                results.add(result);
                progress.file(i, 1, "Done");
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage()));     // e.g. "page 9" in a 5-page file
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Reads the chosen pages (DocumentReader) and writes the Word file. Null if cancelled. */
    private FileResult convertOne(PDDocument doc, Path input, List<Integer> pages, int index, int fileCount,
                                  boolean pageBreaks, boolean includePictures, String ocrLanguages, OcrEngine[] engine,
                                  SaveTo saveTo, Progress progress) throws IOException {
        DocumentReader.Reading reading = DocumentReader.read(doc, input, pages,
                new DocumentReader.Settings(includePictures, ocrLanguages), engine, index, fileCount, progress);
        if (reading == null) return null;
        WordLayout.Margins margins = reading.margins();
        Map<Paragraph, Integer> headings = reading.headings();
        int scans = reading.scans(), scansRead = reading.scansRead(), tableCount = reading.tables();

        int paragraphCount = 0, pictureCount = 0;
        long sizeBefore = Files.size(input);
        Path output = saveTo.outputFor(input, outputSuffix(), outputExtension(), doc.getNumberOfPages());
        WordFonts fonts = WordFonts.of(doc, pages);
        try (XWPFDocument word = new XWPFDocument()) {
            setPage(word, reading.pageWidth(), reading.pageHeight(), margins.left(),
                    reading.pageWidth() - margins.right(), reading.top());
            addHeaderAndFooter(word, reading.furniture());
            if (!headings.isEmpty()) WordStyles.addHeadingStyles(word);
            Map<Paragraph, WordStyles.ListPlace> lists = WordStyles.lists(word, reading.paragraphs(), headings);
            float leading = typicalLeading(reading.paragraphs());
            float pageTop = clamp(reading.top());
            float wordLeft = clamp(margins.left());
            float wordRight = reading.pageWidth() - clamp(reading.pageWidth() - margins.right());
            XWPFParagraph last = null;
            Paragraph lastParagraph = null;
            for (int n = 0; n < reading.pages().size(); n++) {
                if (progress.isCancelled()) return null;
                boolean pageStarted = false;          // has anything from this page been written yet?
                float pictureBottom = -1;             // the bottom of a picture or table just written, for the space under it
                List<DocumentReader.Item> items = reading.pages().get(n);
                // The page's boxes and lines: backgrounds, quote bars, dividers, table shading (WordShapes).
                List<TableFinder.Table<TextWord>> pageTables = new ArrayList<>();
                for (DocumentReader.Item item : items) if (item.table() != null) pageTables.add(item.table());
                WordShapes shapes = n < pages.size()
                        ? WordShapes.of(PageShapes.of(doc.getPage(pages.get(n) - 1)), pageTables, margins.right() - margins.left())
                        : WordShapes.NONE;
                List<WordShapes.Divider> dividers = new ArrayList<>(shapes.dividers());
                WordShapes.Block lastBlock = null;    // the background the last paragraph sat on
                float lastBlockBelow = 0;             // the padding and border under it, which takes up space
                for (DocumentReader.Item item : items) {
                    // Dividing lines above this item come first, each as a line of its own at its height.
                    float itemTop = item.paragraph() != null ? firstBaseline(item.paragraph()) - item.paragraph().size()
                            : item.picture() != null ? item.picture().top() : item.table().top();
                    while (!dividers.isEmpty() && dividers.get(0).top() < itemTop) {
                        WordShapes.Divider d = dividers.remove(0);
                        float above = pictureBottom >= 0 ? pictureBottom
                                : lastParagraph != null && pageStarted ? lastBaseline(lastParagraph) + descent(lastParagraph.size()) + lastBlockBelow
                                : pageTop;
                        last = writeDivider(word, d, d.top() - above - 1, wordLeft, wordRight);
                        if (!pageStarted && n > 0 && pageBreaks) last.setPageBreak(true);
                        pageStarted = true;
                        pictureBottom = d.top() + d.thickness();
                        lastParagraph = null;
                        lastBlock = null;
                        lastBlockBelow = 0;
                    }
                    boolean firstOnPage = !pageStarted && n > 0;
                    if (item.paragraph() == null) {
                        if (item.picture() != null) {
                            PageImages.Picture picture = item.picture();
                            last = writePicture(word, picture, margins, pictureBottom >= 0 ? picture.top() - pictureBottom : 6);
                            if (firstOnPage && pageBreaks) last.setPageBreak(true);
                            pictureCount++;
                        } else {
                            if (firstOnPage && pageBreaks) {
                                // A table can't start a page itself: an empty paragraph does, one point high so it takes no room.
                                XWPFParagraph breakBefore = word.createParagraph();
                                breakBefore.setPageBreak(true);
                                breakBefore.setSpacingAfter(0);
                                breakBefore.setSpacingBetween(1, org.apache.poi.xwpf.usermodel.LineSpacingRule.EXACT);
                            }
                            writeTable(word, item.table(), margins, fonts, shapes, leading);
                            last = null;
                        }
                        pageStarted = true;
                        pictureBottom = item.bottom();
                        lastParagraph = null;
                        lastBlock = null;
                        lastBlockBelow = 0;
                        continue;
                    }
                    Paragraph paragraph = item.paragraph();
                    // Without page breaks, a sentence that runs over onto the next page stays one paragraph.
                    if (firstOnPage && !pageBreaks && last != null && lastParagraph != null && !lastParagraph.endsSentence()
                            && flowing(lastParagraph) && flowing(paragraph) && paragraph.marker() == null
                            && !headings.containsKey(paragraph) && !headings.containsKey(lastParagraph)) {
                        addRuns(last, paragraph.words(), true, fonts, shapes, Float.NaN);
                        lastParagraph = paragraph;
                        pageStarted = true;
                        continue;
                    }
                    // Place the first line's baseline where the PDF has it: measured from the last line above
                    // (or the picture or table above, or the top of the page), less this paragraph's line height.
                    float lineHeight = lineHeight(paragraph, leading);
                    float firstLine = lineHeight - descent(paragraph.size());       // box top to first baseline
                    float spaceBefore;
                    if (pictureBottom >= 0) spaceBefore = firstBaseline(paragraph) - pictureBottom - firstLine;
                    else if (lastParagraph != null && pageStarted) {
                        spaceBefore = firstBaseline(paragraph) - lastBaseline(lastParagraph) - descent(lastParagraph.size()) - firstLine;
                    } else if (!pageStarted) spaceBefore = firstBaseline(paragraph) - pageTop - firstLine;
                    else spaceBefore = paragraph.spaceBefore();
                    if (lastBlock != null) spaceBefore -= lastBlockBelow;       // the padding under the block above
                    // A background behind this paragraph (a code block): shading with the PDF's padding.
                    Look look = new Look();
                    float textLeft = left(paragraph), textRight = right(paragraph);
                    float textTop = firstBaseline(paragraph) - paragraph.size() * 0.8f;
                    float textBottom = lastBaseline(paragraph) + descent(paragraph.size());
                    WordShapes.Block block = shapes.blockBehind(textLeft, textTop, textRight, textBottom);
                    if (block != null) {
                        look.block = block;
                        look.blockTextLeft = blockTextLeft(block, items, shapes);
                        look.padTop = Math.max(0, Math.min(31, firstTextTop(block, items, shapes) - block.top()));
                        look.padBottom = Math.max(0, Math.min(31, block.bottom() - lastTextBottom(block, items, shapes)));
                        look.wordLeft = wordLeft;
                        look.wordRight = wordRight;
                        if (block != lastBlock) spaceBefore -= look.padTop + 0.5f;
                    }
                    WordShapes.Bar bar = shapes.barBeside(textLeft, textTop, textBottom);
                    if (bar != null) look.bar = bar;
                    look.textLeft = textLeft;
                    look.wordLeft = wordLeft;
                    last = write(word, paragraph, headings.get(paragraph), lists.get(paragraph), spaceBefore, lineHeight, fonts, shapes, look);
                    lastBlock = block;
                    lastBlockBelow = block != null ? look.padBottom + 0.5f : 0;
                    if (firstOnPage && pageBreaks) last.setPageBreak(true);
                    pageStarted = true;
                    pictureBottom = -1;
                    lastParagraph = paragraph;
                    paragraphCount++;
                }
                // Dividing lines below the last item on the page.
                for (WordShapes.Divider d : dividers) {
                    float above = pictureBottom >= 0 ? pictureBottom
                            : lastParagraph != null && pageStarted ? lastBaseline(lastParagraph) + descent(lastParagraph.size()) + lastBlockBelow
                            : pageTop;
                    last = writeDivider(word, d, d.top() - above - 1, wordLeft, wordRight);
                    if (!pageStarted && n > 0 && pageBreaks) last.setPageBreak(true);
                    pageStarted = true;
                    pictureBottom = d.top() + d.thickness();
                    lastParagraph = null;
                }
                if (!pageStarted && n > 0 && pageBreaks) {
                    last = word.createParagraph();                   // keep an empty page where the PDF had one
                    last.setPageBreak(true);
                }
            }
            if (word.getParagraphs().isEmpty()) word.createParagraph();
            // Written to memory first, so the PDF's fonts can be added to the Word file (see WordFonts).
            java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
            word.write(written);
            byte[] docx = fonts.embedInto(written.toByteArray());
            SafeSave.write(output, temp -> Files.write(temp, docx));
        }

        String note = Format.count(pages.size(), "page", "pages") + " · " + Format.count(paragraphCount, "paragraph", "paragraphs")
                + (pictureCount > 0 ? " · " + Format.count(pictureCount, "picture", "pictures") : "")
                + (tableCount > 0 ? " · " + Format.count(tableCount, "table", "tables") : "");
        if (scansRead > 0) note += " · " + Format.count(scansRead, "scanned page", "scanned pages") + " read with OCR";
        if (scans > 0) {
            note += " · " + Format.count(scans, "page looks", "pages look") + " scanned and came out empty"
                    + " — tick “Read scanned pages with OCR” to read them";
        }
        return new FileResult(input, output, sizeBefore, Files.size(output),
                scans == pages.size() ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note);
    }

    /**
     * A real Word table: one cell per PDF cell, columns as wide as in the PDF (shrunk to
     * fit between the margins), borders only if the PDF drew them. Each cell keeps its
     * words' look and sits left, centred or right in its cell as it does in the PDF.
     */
    private static void writeTable(XWPFDocument word, TableFinder.Table<TextWord> table, WordLayout.Margins margins,
                                   WordFonts fonts, WordShapes shapes, float leading) {
        int rows = table.cells().size(), columns = table.columns();
        XWPFTable t = word.createTable(rows, columns);
        WordShapes.Borders look = shapes.borders(table);
        if (!table.ruled()) {
            t.removeBorders();
        } else if (look != null) {
            // The PDF's own border colour and thickness, not Word's default black.
            String colour = String.format("%06X", look.rgb());
            int eighths = Math.max(2, Math.round(look.thickness() * 8));
            t.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
            t.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
            t.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
            t.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
            t.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
            t.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, eighths, 0, colour);
        }
        // The space between a cell's edge and its text, as in the PDF (first column's words from the table's edge).
        float padLeft = 5.4f;
        for (List<List<TextWord>> row : table.cells()) {
            for (TextWord w : row.get(0)) padLeft = Math.min(padLeft == 5.4f ? Float.MAX_VALUE : padLeft, w.left() - table.left());
        }
        if (padLeft == Float.MAX_VALUE || padLeft < 0 || padLeft > 20) padLeft = 5.4f;
        // And above the text: from the table's top edge to the top of the first row's letters.
        float padTop = 0;
        if (table.ruled()) {
            float firstTop = Float.MAX_VALUE;
            for (List<TextWord> cellWords : table.cells().get(0)) {
                for (TextWord w : cellWords) firstTop = Math.min(firstTop, w.baseline() - w.size() * 0.8f);
            }
            if (firstTop != Float.MAX_VALUE) padTop = Math.max(0, Math.min(15, firstTop - table.top()));
        }
        // And below: what's left of the first row's height in the PDF under its lines of text. Padding,
        // not a fixed row height, so Word and LibreOffice (which count row heights differently) agree.
        List<Float> rowHeights = shapes.rowHeights(table);
        float padBottom = 0;
        if (!rowHeights.isEmpty()) {
            int lines = 1;
            float size = 0;
            for (List<TextWord> cellWords : table.cells().get(0)) {
                lines = Math.max(lines, (int) cellWords.stream().map(w -> Math.round(w.baseline())).distinct().count());
                for (TextWord w : cellWords) size = Math.max(size, w.size());
            }
            if (size > 0) padBottom = Math.max(0, Math.min(15, rowHeights.get(0) - padTop - lines * size * leading));
        }
        t.setCellMargins(twips(padTop), twips(padLeft), twips(padBottom), twips(padLeft));

        // A table without borders is measured from its text to its text; Word adds about 6 points of
        // padding inside each side of a cell, so add that back or every column would wrap.
        List<Float> widths = new ArrayList<>();
        for (float w : table.columnWidths()) widths.add(table.ruled() ? w : w + 12);
        float total = 0;
        for (float w : widths) total += w;
        float scale = Math.min(1, (margins.right() - margins.left()) / total);
        t.setWidth(String.valueOf(twips(total * scale)));
        var grid = t.getCTTbl().getTblGrid() != null ? t.getCTTbl().getTblGrid() : t.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (float w : widths) grid.addNewGridCol().setW(BigInteger.valueOf(twips(w * scale)));

        for (int r = 0; r < rows; r++) {
            float cellLeft = table.left();
            for (int c = 0; c < columns; c++) {
                float cellRight = cellLeft + table.columnWidths().get(c);
                XWPFTableCell cell = t.getRow(r).getCell(c);
                cell.setWidth(String.valueOf(twips(widths.get(c) * scale)));
                XWPFParagraph p = cell.getParagraphs().get(0);
                p.setSpacingAfter(0);
                List<TextWord> words = new ArrayList<>(table.cells().get(r).get(c));
                if (!words.isEmpty()) {
                    // The cell's background (a header row's shading), found by a point inside its text.
                    TextWord first = words.get(0);
                    int behind = shapes.cellBackground((cellLeft + cellRight) / 2, first.baseline() - first.size() * 0.3f);
                    if (behind >= 0) cell.setColor(String.format("%06X", behind));
                    // Lines in a cell as far apart as the document's lines.
                    p.setSpacingBetween(first.size() * leading, org.apache.poi.xwpf.usermodel.LineSpacingRule.EXACT);
                }
                words.sort(java.util.Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
                if (!words.isEmpty() && table.ruled()) p.setAlignment(alignmentIn(words, cellLeft, cellRight));
                addRuns(p, words, false, fonts, shapes, Float.NaN);
                cellLeft = cellRight;
            }
        }
        if (!table.ruled()) {
            // Without borders the column edges are guesses, so read each column's alignment from all its cells.
            for (int c = 0; c < columns; c++) {
                if (rightAligned(c, table)) {
                    for (int r = 0; r < rows; r++) t.getRow(r).getCell(c).getParagraphs().get(0).setAlignment(ParagraphAlignment.RIGHT);
                }
            }
        }
    }

    /** Where text sits in a cell with known edges: a clearly bigger gap on one side means it's aligned to the other. */
    private static ParagraphAlignment alignmentIn(List<TextWord> words, float cellLeft, float cellRight) {
        float textLeft = Float.MAX_VALUE, textRight = -Float.MAX_VALUE;
        for (TextWord w : words) {
            textLeft = Math.min(textLeft, w.left());
            textRight = Math.max(textRight, w.right());
        }
        float gapLeft = textLeft - cellLeft, gapRight = cellRight - textRight;
        if (gapLeft > 6 && gapRight > 6 && Math.abs(gapLeft - gapRight) < 4) return ParagraphAlignment.CENTER;
        if (gapLeft > gapRight + 6) return ParagraphAlignment.RIGHT;
        return ParagraphAlignment.LEFT;
    }

    /**
     * In a table without borders: do the column's cells (below the header row, which is
     * often aligned differently) all end in the same place while starting in different
     * places? Then the column is right-aligned, like amounts on a statement.
     */
    private static boolean rightAligned(int column, TableFinder.Table<TextWord> table) {
        List<Float> rights = new ArrayList<>(), lefts = new ArrayList<>();
        for (int r = 1; r < table.cells().size(); r++) {
            List<TextWord> words = table.cells().get(r).get(column);
            if (words.isEmpty()) continue;
            rights.add(words.stream().map(TextWord::right).max(Float::compare).get());
            lefts.add(words.stream().map(TextWord::left).min(Float::compare).get());
        }
        if (rights.size() < 2) return false;
        float rightSpread = rights.stream().max(Float::compare).get() - rights.stream().min(Float::compare).get();
        float leftSpread = lefts.stream().max(Float::compare).get() - lefts.stream().min(Float::compare).get();
        return rightSpread < 2 && leftSpread > 2;
    }

    /**
     * The PDF's repeated header and footer lines as Word's header and footer, and its
     * page numbers as Word's own page number, which counts by itself as the text changes.
     */
    private static void addHeaderAndFooter(XWPFDocument word, PageFurniture.Result furniture) {
        boolean numberInHeader = furniture.pageNumbers() && furniture.pageNumbersAtTop();
        boolean numberInFooter = furniture.pageNumbers() && !furniture.pageNumbersAtTop();
        if (furniture.header() != null || numberInHeader) {
            fill(word.createHeader(HeaderFooterType.DEFAULT), furniture.header(), numberInHeader, furniture.pageNumberAlignment());
        }
        if (furniture.footer() != null || numberInFooter) {
            fill(word.createFooter(HeaderFooterType.DEFAULT), furniture.footer(), numberInFooter, furniture.pageNumberAlignment());
        }
    }

    private static void fill(org.apache.poi.xwpf.usermodel.XWPFHeaderFooter part, String text, boolean pageNumber,
                             WordLayout.Alignment numberAlignment) {
        if (text != null) {
            XWPFParagraph p = part.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.createRun().setText(text);
        }
        if (pageNumber) {
            XWPFParagraph p = part.createParagraph();
            p.setAlignment(switch (numberAlignment) {
                case LEFT -> ParagraphAlignment.LEFT;
                case RIGHT -> ParagraphAlignment.RIGHT;
                default -> ParagraphAlignment.CENTER;
            });
            var field = p.getCTP().addNewFldSimple();
            field.setInstr("PAGE");
            field.addNewR().addNewT().setStringValue("1");      // shown until Word works out the number
        }
    }

    /** The baselines of a paragraph's lines, top to bottom (words within half a letter count as one line). */
    private static List<Float> baselines(Paragraph paragraph) {
        List<Float> all = new ArrayList<>();
        for (TextWord w : paragraph.words()) all.add(w.baseline());
        all.sort(Float::compare);
        List<Float> lines = new ArrayList<>();
        for (float b : all) {
            if (lines.isEmpty() || b - lines.get(lines.size() - 1) > paragraph.size() * 0.5f) lines.add(b);
        }
        return lines;
    }

    private static float firstBaseline(Paragraph paragraph) {
        List<Float> lines = baselines(paragraph);
        return lines.isEmpty() ? paragraph.top() : lines.get(0);
    }

    private static float lastBaseline(Paragraph paragraph) {
        List<Float> lines = baselines(paragraph);
        return lines.isEmpty() ? paragraph.bottom() : lines.get(lines.size() - 1);
    }

    /**
     * The distance between a paragraph's lines in the PDF (the middle value, so one odd gap
     * doesn't count); for a single line, the document's usual line height for its text size.
     */
    private static float lineHeight(Paragraph paragraph, float leading) {
        List<Float> lines = baselines(paragraph);
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) gaps.add(lines.get(i) - lines.get(i - 1));
        if (!gaps.isEmpty()) {
            gaps.sort(Float::compare);
            float middle = gaps.get(gaps.size() / 2);
            if (middle > paragraph.size() * 0.9f && middle < paragraph.size() * 3) return middle;
        }
        return paragraph.size() * leading;
    }

    /** The document's usual line height as a multiple of the text size, from its paragraphs of several lines. */
    private static float typicalLeading(List<Paragraph> paragraphs) {
        List<Float> ratios = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            List<Float> lines = baselines(paragraph);
            for (int i = 1; i < lines.size(); i++) {
                float ratio = (lines.get(i) - lines.get(i - 1)) / Math.max(1, paragraph.size());
                if (ratio > 0.9f && ratio < 3) ratios.add(ratio);
            }
        }
        if (ratios.isEmpty()) return 1.2f;
        ratios.sort(Float::compare);
        return ratios.get(ratios.size() / 2);
    }

    /** How far letters reach below the baseline, roughly, for a text size (Word puts a line's extra space above it). */
    private static float descent(float size) {
        return size * 0.27f;
    }

    /** Left-aligned or justified body text, which can run on from one page to the next. */
    private static boolean flowing(Paragraph paragraph) {
        return paragraph.alignment() == WordLayout.Alignment.LEFT || paragraph.alignment() == WordLayout.Alignment.JUSTIFIED;
    }

    /** How a paragraph is decorated: a background block or a quote bar, and where things are on the page. */
    private static final class Look {
        WordShapes.Block block;
        WordShapes.Bar bar;
        float blockTextLeft, padTop, padBottom, wordLeft, wordRight, textLeft;
    }

    private static XWPFParagraph write(XWPFDocument word, Paragraph paragraph, Integer headingLevel,
                                       WordStyles.ListPlace listPlace, float spaceBefore, float lineHeight, WordFonts fonts,
                                       WordShapes shapes, Look look) {
        XWPFParagraph p = word.createParagraph();
        if (headingLevel != null) p.setStyle("Heading" + headingLevel);
        if (listPlace != null) {
            p.setNumID(listPlace.numId());
            p.setNumILvl(BigInteger.valueOf(listPlace.level()));
        }
        p.setAlignment(switch (paragraph.alignment()) {
            case CENTRE -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
            case JUSTIFIED -> ParagraphAlignment.BOTH;
            case LEFT -> ParagraphAlignment.LEFT;
        });
        p.setSpacingAfter(0);
        p.setSpacingBefore(twips(Math.max(0, Math.min(spaceBefore, 200))));
        // Exactly the PDF's distance between lines, so text sits as tall on the page as in the PDF.
        p.setSpacingBetween(lineHeight, org.apache.poi.xwpf.usermodel.LineSpacingRule.EXACT);
        if (look.block != null) {
            // Every line of a block gets the same indents and borders, so Word draws one box around them all;
            // a line indented further in (code) keeps its place with a first-line indent.
            WordShapes.Block b = look.block;
            p.setIndentationLeft(twips(look.blockTextLeft - look.wordLeft));
            p.setIndentationRight(twips(look.wordRight - b.right() + 4));
            float further = look.textLeft - look.blockTextLeft;
            if (further > 1) p.setIndentationFirstLine(twips(further));
            String colour = String.format("%06X", b.rgb());
            var borders = p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetPBdr() ? p.getCTP().getPPr().getPBdr()
                    : (p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr()).addNewPBdr();
            border(borders.addNewTop(), colour, 4, look.padTop);
            border(borders.addNewBottom(), colour, 4, look.padBottom);
            border(borders.addNewLeft(), colour, 4, Math.min(31, look.blockTextLeft - b.left()));
            border(borders.addNewRight(), colour, 4, 4);
            var shading = p.getCTP().getPPr().isSetShd() ? p.getCTP().getPPr().getShd() : p.getCTP().getPPr().addNewShd();
            shading.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
            shading.setColor("auto");
            shading.setFill(colour);
        } else {
            if (paragraph.indent() > 2) p.setIndentationLeft(twips(paragraph.indent()));
            if (paragraph.firstLineIndent() > 2) p.setIndentationFirstLine(twips(paragraph.firstLineIndent()));
            else if (paragraph.firstLineIndent() < -2) p.setIndentationHanging(twips(-paragraph.firstLineIndent()));
        }
        if (look.bar != null) {
            // The bar beside a quote: a left border as thick as the bar, as far from the text.
            WordShapes.Bar bar = look.bar;
            var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            var borders = ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr();
            if (!borders.isSetLeft()) {
                border(borders.addNewLeft(), String.format("%06X", bar.rgb()), Math.round((bar.right() - bar.left()) * 8),
                        Math.min(31, look.textLeft - bar.right()));
            }
        }
        List<TextWord> words = paragraph.words();
        if (paragraph.marker() != null && listPlace == null) {
            // A numbered heading ("1. Contributions"): the number stays as text in the heading.
            words = new ArrayList<>(words);
            words.add(0, paragraph.marker().word());
        }
        addRuns(p, words, false, fonts, shapes, look.wordLeft);
        return p;
    }

    /**
     * A picture in a paragraph of its own, at its size in the PDF (shrunk to fit between
     * the margins), aligned the way it sits on the page: centred, right or at its indent.
     */
    private static XWPFParagraph writePicture(XWPFDocument word, PageImages.Picture picture, WordLayout.Margins margins,
                                              float spaceBefore) throws IOException {
        XWPFParagraph p = word.createParagraph();
        p.setSpacingAfter(0);
        p.setSpacingBefore(twips(Math.max(0, Math.min(spaceBefore, 48))));
        float textWidth = margins.right() - margins.left();
        float scale = Math.min(1, textWidth / picture.width());
        float width = picture.width() * scale, height = picture.height() * scale;

        float spaceLeft = picture.left() - margins.left(), spaceRight = margins.right() - (picture.left() + picture.width());
        if (scale < 1 || Math.abs(spaceLeft - spaceRight) < 12) {
            p.setAlignment(ParagraphAlignment.CENTER);
        } else if (spaceRight < 6 && spaceLeft > 12) {
            p.setAlignment(ParagraphAlignment.RIGHT);
        } else if (spaceLeft > 2) {
            p.setIndentationLeft(twips(spaceLeft));
        }
        try (java.io.InputStream bytes = new java.io.ByteArrayInputStream(picture.bytes())) {
            p.createRun().addPicture(bytes, picture.jpeg() ? Document.PICTURE_TYPE_JPEG : Document.PICTURE_TYPE_PNG,
                    picture.jpeg() ? "picture.jpg" : "picture.png", Units.toEMU(width), Units.toEMU(height));
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IOException(e);
        }
        return p;
    }

    /**
     * Adds the words as runs: one run per stretch of words that look the same (and have the same
     * background). A space goes between words only where the PDF has a gap or a new line, so
     * "PDF" in bold followed by a plain "." stays "PDF.".
     */
    private static void addRuns(XWPFParagraph p, List<TextWord> words, boolean afterExistingText, WordFonts fonts,
                                WordShapes shapes, float tabOrigin) {
        StringBuilder text = new StringBuilder();
        TextWord look = null, previous = null;
        int background = -1;
        boolean spaceFirst = afterExistingText;
        for (TextWord w : words) {
            int behind = shapes.highlightBehind(w);
            boolean sameLine = previous != null && Math.abs(w.baseline() - previous.baseline()) <= previous.size() * 0.5f;
            boolean gap = previous != null && (!sameLine || w.left() - previous.right() > previous.size() * 0.12f);
            if (sameLine && !Float.isNaN(tabOrigin) && w.left() - previous.right() > previous.size() * 0.9f) {
                // A wide gap on one line (a form's answer, a column of a folder tree): a tab to a tab stop
                // where the PDF has the next word, so it lines up there exactly.
                if (look != null && text.length() > 0) addRun(p, text.toString(), look, fonts, background);
                text.setLength(0);
                XWPFRun tab = p.createRun();
                tab.setFontSize(w.size());
                tab.addTab();
                addTabStop(p, w.left() - tabOrigin);
                spaceFirst = false;
                text.append(w.text());
                look = w;
                background = behind;
                previous = w;
                continue;
            }
            if (look != null && (!w.looksLike(look) || behind != background)) {
                // The space between two runs goes with whichever isn't underlined or shaded, so no stray mark shows.
                boolean spaceAfter = gap && !look.underline() && background < 0;
                if (spaceAfter) text.append(' ');
                addRun(p, text.toString(), look, fonts, background);
                text.setLength(0);
                spaceFirst = gap && !spaceAfter;
            } else if (gap) {
                text.append(spacesFor(previous, w));
            }
            if (spaceFirst) text.append(' ');
            spaceFirst = false;
            text.append(w.text());
            look = w;
            background = behind;
            previous = w;
        }
        if (look != null) addRun(p, text.toString(), look, fonts, background);
    }

    /** A left tab stop at this distance (points) from the left margin, unless the paragraph has one there. */
    private static void addTabStop(XWPFParagraph p, float position) {
        if (position <= 0) return;
        var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        var tabs = ppr.isSetTabs() ? ppr.getTabs() : ppr.addNewTabs();
        BigInteger at = BigInteger.valueOf(twips(position));
        for (var existing : tabs.getTabArray()) {
            if (existing.getPos() instanceof BigInteger pos && pos.subtract(at).abs().intValue() < 40) return;
        }
        var tab = tabs.addNewTab();
        tab.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc.LEFT);
        tab.setPos(at);
    }

    /**
     * The spaces for the gap before a word on the same line: one, or for a gap far wider than a
     * space (columns lined up with spaces, as in code or a folder tree) as many as fill it.
     * Justified text never stretches a gap this far, so it isn't affected.
     */
    private static String spacesFor(TextWord previous, TextWord next) {
        if (Math.abs(next.baseline() - previous.baseline()) > previous.size() * 0.5f) return " ";
        float gap = next.left() - previous.right();
        if (gap <= previous.size() * 0.9f) return " ";
        String font = previous.font().toLowerCase(java.util.Locale.ROOT);
        float space = previous.size() * (font.contains("mono") || font.contains("courier") || font.contains("consol") ? 0.6f : 0.26f);
        return " ".repeat(Math.max(2, Math.min(80, Math.round(gap / space))));
    }

    private static void addRun(XWPFParagraph p, String text, TextWord look, WordFonts fonts, int background) {
        XWPFRun run = p.createRun();
        run.setText(text);
        // The PDF's font where possible: embedded in the Word file, installed, or a close match (WordFonts).
        run.setFontFamily(fonts.name(look.font(), look.bold(), look.italic()));
        run.setFontSize(look.size());
        if (look.bold()) run.setBold(true);
        if (look.italic()) run.setItalic(true);
        if (look.colour() != 0) run.setColor(String.format("%06X", look.colour()));
        if (look.underline()) run.setUnderline(UnderlinePatterns.SINGLE);
        if (background >= 0) {
            var rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
            var shading = rpr.addNewShd();
            shading.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
            shading.setColor("auto");
            shading.setFill(String.format("%06X", background));
        }
    }

    /** A dividing line: an empty paragraph one point high, its bottom border drawn like the PDF's line. */
    private static XWPFParagraph writeDivider(XWPFDocument word, WordShapes.Divider d, float spaceBefore,
                                              float wordLeft, float wordRight) {
        XWPFParagraph p = word.createParagraph();
        p.setSpacingAfter(0);
        p.setSpacingBefore(twips(Math.max(0, Math.min(spaceBefore, 200))));
        p.setSpacingBetween(1, org.apache.poi.xwpf.usermodel.LineSpacingRule.EXACT);
        p.setIndentationLeft(twips(d.left() - wordLeft));
        p.setIndentationRight(twips(wordRight - d.right()));
        var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        border(ppr.addNewPBdr().addNewBottom(), String.format("%06X", d.rgb()), Math.max(2, Math.round(d.thickness() * 8)), 0);
        return p;
    }

    /** Fills in one paragraph border: single line, size in eighths of a point, gap to the text in points. */
    private static void border(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder border, String colour,
                               int eighths, float space) {
        border.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(Math.max(2, Math.min(96, eighths))));
        border.setSpace(BigInteger.valueOf(Math.round(Math.max(0, Math.min(31, space)))));
        border.setColor(colour);
    }

    private static float left(Paragraph paragraph) {
        float left = Float.MAX_VALUE;
        for (TextWord w : paragraph.words()) left = Math.min(left, w.left());
        return left;
    }

    private static float right(Paragraph paragraph) {
        float right = -Float.MAX_VALUE;
        for (TextWord w : paragraph.words()) right = Math.max(right, w.right());
        return right;
    }

    /** The paragraphs on the page that sit on this background. */
    private static List<Paragraph> onBlock(WordShapes.Block block, List<DocumentReader.Item> items, WordShapes shapes) {
        List<Paragraph> on = new ArrayList<>();
        for (DocumentReader.Item item : items) {
            Paragraph q = item.paragraph();
            if (q == null) continue;
            if (shapes.blockBehind(left(q), firstBaseline(q) - q.size() * 0.8f, right(q), lastBaseline(q) + descent(q.size())) == block) on.add(q);
        }
        return on;
    }

    /** Where the block's least indented text starts: its left padding ends there. */
    private static float blockTextLeft(WordShapes.Block block, List<DocumentReader.Item> items, WordShapes shapes) {
        float left = Float.MAX_VALUE;
        for (Paragraph q : onBlock(block, items, shapes)) left = Math.min(left, left(q));
        return left == Float.MAX_VALUE ? block.left() : left;
    }

    private static float firstTextTop(WordShapes.Block block, List<DocumentReader.Item> items, WordShapes shapes) {
        List<Paragraph> on = onBlock(block, items, shapes);
        return on.isEmpty() ? block.top() : firstBaseline(on.get(0)) - on.get(0).size() * 0.8f;
    }

    private static float lastTextBottom(WordShapes.Block block, List<DocumentReader.Item> items, WordShapes shapes) {
        List<Paragraph> on = onBlock(block, items, shapes);
        if (on.isEmpty()) return block.bottom();
        Paragraph q = on.get(on.size() - 1);
        return lastBaseline(q) + descent(q.size());
    }

    /** Page size and margins like the PDF's, so lines break in about the same places. */
    private static void setPage(XWPFDocument word, float width, float height, float left, float right, float top) {
        CTSectPr section = word.getDocument().getBody().isSetSectPr()
                ? word.getDocument().getBody().getSectPr() : word.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(twips(width)));
        size.setH(BigInteger.valueOf(twips(height)));
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setLeft(BigInteger.valueOf(twips(clamp(left))));
        margins.setRight(BigInteger.valueOf(twips(clamp(right))));
        margins.setTop(BigInteger.valueOf(twips(clamp(top))));
        margins.setBottom(BigInteger.valueOf(twips(clamp(top))));
    }

    /** Margins between half an inch and two inches, whatever the PDF had. */
    private static float clamp(float points) {
        return Math.max(36, Math.min(144, points));
    }

    /** Word measures in twentieths of a point. */
    private static int twips(float points) {
        return Math.round(points * 20);
    }
}
