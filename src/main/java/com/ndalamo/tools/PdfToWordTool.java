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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.poi.util.Units;
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
 * their place between paragraphs (12c), and tables as real Word tables (12d). Columns
 * and scans come in later steps. The goal is a clean document to
 * edit, not an exact copy of the page. The PDF is only read.
 */
public class PdfToWordTool implements PdfTool {

    static final String PAGE_BREAKS = "pageBreaks";
    static final String PAGES = "pages";
    static final String PICTURES = "pictures";

    /** Fewer letters than this and the page is probably a scan; OCR for PDF to Word comes in step 12f. */
    private static final int HAS_TEXT = 25;

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

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));
                FileResult result = convertOne(doc, input, pages, i, inputs.size(), pageBreaks, pictures, saveTo, progress);
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

    /** Reads the chosen pages and writes the Word file. Null if cancelled. */
    private FileResult convertOne(PDDocument doc, Path input, List<Integer> pages, int index, int fileCount,
                                  boolean pageBreaks, boolean includePictures, SaveTo saveTo, Progress progress)
            throws IOException {
        // Read every page first: the margins are worked out from all of them together.
        List<List<TextWord>> pageWords = new ArrayList<>();
        List<List<PageImages.Picture>> pagePictures = new ArrayList<>();
        List<List<PageContent.Rule>> pageRules = new ArrayList<>();
        for (int n = 0; n < pages.size(); n++) {
            if (progress.isCancelled()) return null;
            int page = pages.get(n);
            String status = "Page " + page + " of " + doc.getNumberOfPages();
            progress.file(index, (n + 0.1) / pages.size() * 0.8, status);
            progress.update((index + (double) n / pages.size()) / fileCount, "Reading " + input.getFileName() + " · " + status);
            List<TextWord> words = PageText.words(doc, page - 1);
            List<PageContent.Rule> rules = PageContent.rules(doc.getPage(page - 1));
            pageRules.add(rules);
            pageWords.add(PageText.markUnderlines(words, rules));
            pagePictures.add(includePictures ? PageImages.pictures(doc.getPage(page - 1)) : List.of());
        }

        float left = Float.MAX_VALUE, right = 0, top = Float.MAX_VALUE;
        for (List<TextWord> words : pageWords) {
            for (TextWord w : words) {
                left = Math.min(left, w.left());
                right = Math.max(right, w.right());
                top = Math.min(top, w.baseline() - w.size());
            }
        }
        PDRectangle size = doc.getPage(pages.get(0) - 1).getCropBox();
        boolean turned = doc.getPage(pages.get(0) - 1).getRotation() % 180 != 0;
        float pageWidth = turned ? size.getHeight() : size.getWidth(), pageHeight = turned ? size.getWidth() : size.getHeight();
        if (left == Float.MAX_VALUE) {                       // no text at all
            left = 72;
            right = pageWidth - 72;
            top = 72;
        }
        for (List<PageImages.Picture> pictures : pagePictures) {
            for (PageImages.Picture picture : pictures) {
                right = Math.max(right, picture.left() + picture.width());
                top = Math.min(top, picture.top());                  // a logo above the first line
            }
        }
        // If no line gets near the right side (a page of short lines), assume the right margin matches the left,
        // as it does on nearly every page; otherwise every line would look like it reaches the edge.
        if (right < pageWidth - left - 36) right = pageWidth - left;
        WordLayout.Margins margins = new WordLayout.Margins(left, right);

        int paragraphCount = 0, pictureCount = 0, scans = 0;
        long sizeBefore = Files.size(input);
        Path output = saveTo.outputFor(input, outputSuffix(), outputExtension(), doc.getNumberOfPages());
        List<List<Paragraph>> pageParagraphs = new ArrayList<>();
        List<Paragraph> allParagraphs = new ArrayList<>();
        List<List<TableFinder.Table<TextWord>>> pageTables = new ArrayList<>();
        int tableCount = 0;
        for (int n = 0; n < pageWords.size(); n++) {
            List<TableFinder.Table<TextWord>> tables = tables(pageWords.get(n), pageRules.get(n));
            pageTables.add(tables);
            tableCount += tables.size();
            // Words in a table are written in the table, not as paragraphs.
            java.util.Set<TextWord> inTables = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (TableFinder.Table<TextWord> table : tables) table.cells().forEach(row -> row.forEach(inTables::addAll));
            List<TextWord> words = pageWords.get(n).stream().filter(w -> !inTables.contains(w)).toList();
            List<Paragraph> paragraphs = WordLayout.paragraphs(words, margins);
            pageParagraphs.add(paragraphs);
            allParagraphs.addAll(paragraphs);
        }
        Map<Paragraph, Integer> headings = WordStyles.headings(allParagraphs, WordStyles.bodySize(pageWords));

        try (XWPFDocument word = new XWPFDocument()) {
            setPage(word, pageWidth, pageHeight, left, pageWidth - right, top);
            if (!headings.isEmpty()) WordStyles.addHeadingStyles(word);
            Map<Paragraph, WordStyles.ListPlace> lists = WordStyles.lists(word, allParagraphs, headings);
            XWPFParagraph last = null;
            Paragraph lastParagraph = null;
            for (int n = 0; n < pageWords.size(); n++) {
                if (progress.isCancelled()) return null;
                List<TextWord> words = pageWords.get(n);
                int letters = 0;
                for (TextWord w : words) letters += w.text().length();
                if (letters < HAS_TEXT) scans++;

                List<Paragraph> paragraphs = pageParagraphs.get(n);
                List<Block> waiting = new ArrayList<>();
                for (PageImages.Picture picture : pagePictures.get(n)) waiting.add(new Block(picture.top(), picture.bottom(), picture, null));
                for (TableFinder.Table<TextWord> table : pageTables.get(n)) waiting.add(new Block(table.top(), table.bottom(), null, table));
                waiting.sort(java.util.Comparator.comparingDouble(Block::top));
                boolean pageStarted = false;          // has anything from this page been written yet?
                float pictureBottom = -1;             // the bottom of a picture or table just written, for the space under it
                for (int p = 0; p <= paragraphs.size(); p++) {
                    Paragraph paragraph = p < paragraphs.size() ? paragraphs.get(p) : null;
                    // Pictures and tables that start above this paragraph go first, in the order they appear.
                    while (!waiting.isEmpty() && (paragraph == null || waiting.get(0).top() < paragraph.top())) {
                        Block block = waiting.remove(0);
                        boolean breakFirst = !pageStarted && n > 0 && pageBreaks;
                        if (block.picture() != null) {
                            PageImages.Picture picture = block.picture();
                            last = writePicture(word, picture, margins, pictureBottom >= 0 ? picture.top() - pictureBottom : 6);
                            if (breakFirst) last.setPageBreak(true);
                            pictureCount++;
                        } else {
                            if (breakFirst) word.createParagraph().setPageBreak(true);   // a table can't start a page itself
                            writeTable(word, block.table(), margins);
                            last = null;
                        }
                        pageStarted = true;
                        pictureBottom = block.bottom();
                        lastParagraph = null;
                    }
                    if (paragraph == null) break;
                    boolean firstOnPage = !pageStarted && n > 0;
                    // Without page breaks, a sentence that runs over onto the next page stays one paragraph.
                    if (firstOnPage && !pageBreaks && last != null && lastParagraph != null && !lastParagraph.endsSentence()
                            && flowing(lastParagraph) && flowing(paragraph) && paragraph.marker() == null
                            && !headings.containsKey(paragraph) && !headings.containsKey(lastParagraph)) {
                        addRuns(last, paragraph.words(), true);
                        lastParagraph = paragraph;
                        pageStarted = true;
                        continue;
                    }
                    // Under a picture, measure the space from the picture, not from the text above it.
                    float spaceBefore = pictureBottom >= 0 ? Math.max(0, paragraph.top() - pictureBottom) : paragraph.spaceBefore();
                    last = write(word, paragraph, headings.get(paragraph), lists.get(paragraph), spaceBefore);
                    if (firstOnPage && pageBreaks) last.setPageBreak(true);
                    pageStarted = true;
                    pictureBottom = -1;
                    lastParagraph = paragraph;
                    paragraphCount++;
                }
                if (!pageStarted && n > 0 && pageBreaks) {
                    last = word.createParagraph();                   // keep an empty page where the PDF had one
                    last.setPageBreak(true);
                }
            }
            if (word.getParagraphs().isEmpty()) word.createParagraph();
            SafeSave.write(output, temp -> {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    word.write(out);
                }
            });
        }

        String note = Format.count(pages.size(), "page", "pages") + " · " + Format.count(paragraphCount, "paragraph", "paragraphs")
                + (pictureCount > 0 ? " · " + Format.count(pictureCount, "picture", "pictures") : "")
                + (tableCount > 0 ? " · " + Format.count(tableCount, "table", "tables") : "");
        if (scans > 0) {
            note += " · " + Format.count(scans, "page looks", "pages look") + " scanned and came out empty"
                    + " — run OCR PDF on it first";
        }
        return new FileResult(input, output, sizeBefore, Files.size(output),
                scans == pages.size() ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note);
    }

    /** A picture or a table, waiting for its place between the paragraphs of its page. */
    private record Block(float top, float bottom, PageImages.Picture picture, TableFinder.Table<TextWord> table) {}

    /**
     * The tables on a page. Tables with drawn borders always count. Tables made of
     * spacing alone need 3 rows and 3 columns: two columns of text is a two-column
     * layout (step 12e), not a table, and a few aligned lines are usually a form or address.
     */
    private static List<TableFinder.Table<TextWord>> tables(List<TextWord> words, List<PageContent.Rule> rules) {
        return TableFinder.find(words, rules).stream()
                .filter(t -> t.ruled() || (t.cells().size() >= 3 && t.columns() >= 3))
                .toList();
    }

    /**
     * A real Word table: one cell per PDF cell, columns as wide as in the PDF (shrunk to
     * fit between the margins), borders only if the PDF drew them. Each cell keeps its
     * words' look and sits left, centred or right in its cell as it does in the PDF.
     */
    private static void writeTable(XWPFDocument word, TableFinder.Table<TextWord> table, WordLayout.Margins margins) {
        int rows = table.cells().size(), columns = table.columns();
        XWPFTable t = word.createTable(rows, columns);
        if (!table.ruled()) t.removeBorders();

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
                words.sort(java.util.Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
                if (!words.isEmpty() && table.ruled()) p.setAlignment(alignmentIn(words, cellLeft, cellRight));
                addRuns(p, words, false);
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

    /** Left-aligned or justified body text, which can run on from one page to the next. */
    private static boolean flowing(Paragraph paragraph) {
        return paragraph.alignment() == WordLayout.Alignment.LEFT || paragraph.alignment() == WordLayout.Alignment.JUSTIFIED;
    }

    private static XWPFParagraph write(XWPFDocument word, Paragraph paragraph, Integer headingLevel,
                                       WordStyles.ListPlace listPlace, float spaceBefore) {
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
        p.setSpacingBefore(twips(Math.min(spaceBefore, 48)));
        if (paragraph.indent() > 2) p.setIndentationLeft(twips(paragraph.indent()));
        if (paragraph.firstLineIndent() > 2) p.setIndentationFirstLine(twips(paragraph.firstLineIndent()));
        else if (paragraph.firstLineIndent() < -2) p.setIndentationHanging(twips(-paragraph.firstLineIndent()));
        List<TextWord> words = paragraph.words();
        if (paragraph.marker() != null && listPlace == null) {
            // A numbered heading ("1. Contributions"): the number stays as text in the heading.
            words = new ArrayList<>(words);
            words.add(0, paragraph.marker().word());
        }
        addRuns(p, words, false);
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

    /** Adds the words as runs: one run per stretch of words that look the same. */
    private static void addRuns(XWPFParagraph p, List<TextWord> words, boolean afterExistingText) {
        StringBuilder text = new StringBuilder();
        TextWord look = null;
        boolean spaceFirst = afterExistingText;
        for (TextWord w : words) {
            if (look != null && !w.looksLike(look)) {
                // The space between two runs goes with whichever isn't underlined, so no underlined gap shows.
                if (!look.underline()) text.append(' ');
                addRun(p, text.toString(), look);
                text.setLength(0);
                spaceFirst = look.underline();
            }
            if (text.length() > 0 || spaceFirst) text.append(' ');
            spaceFirst = false;
            text.append(w.text());
            look = w;
        }
        if (look != null) addRun(p, text.toString(), look);
    }

    private static void addRun(XWPFParagraph p, String text, TextWord look) {
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontFamily(look.font());
        run.setFontSize(look.size());
        if (look.bold()) run.setBold(true);
        if (look.italic()) run.setItalic(true);
        if (look.colour() != 0) run.setColor(String.format("%06X", look.colour()));
        if (look.underline()) run.setUnderline(UnderlinePatterns.SINGLE);
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
