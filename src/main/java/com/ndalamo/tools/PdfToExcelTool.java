package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PageContent.Box;
import com.ndalamo.tools.TableFinder.Table;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PDF to Excel: finds the tables in a PDF (TableFinder) and writes them to an
 * .xlsx file, one per PDF (SPEC §4). Each table gets its own sheet, or each page's
 * tables share one sheet. Numbers become real Excel numbers so they can be added
 * up. Scanned pages are read with OCR first. The PDF is only read.
 */
public class PdfToExcelTool implements PdfTool {

    static final String LAYOUT = "layout";
    static final String SHEET_PER_TABLE = "Each table on its own sheet";
    static final String SHEET_PER_PAGE = "One sheet per page";
    static final String OCR = "ocr";
    static final String PAGES = "pages";

    private static final int OCR_DPI = 300;
    /** Same rule as OCR PDF: fewer letters than this and the page is treated as a scan. */
    private static final int HAS_TEXT = 25;

    @Override public String id() { return "pdf-to-excel"; }
    @Override public String name() { return "PDF to Excel"; }
    @Override public String description() { return "Tables to .xlsx"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "tables"; }
    @Override public String outputExtension() { return "xlsx"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(LAYOUT, "Put the tables", OptionField.Type.CHOICE,
                        List.of(SHEET_PER_TABLE, SHEET_PER_PAGE), SHEET_PER_TABLE)
                        .describe("Sheets named “Page 2 table 1”", "Tables on the same page, one under the other"),
                new OptionField(OCR, "Read scanned pages with OCR (slower)", OptionField.Type.CHECKBOX, true),
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
        return "Find tables in " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Finding tables in " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to Excel";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean sheetPerPage = SHEET_PER_PAGE.equals(options.get(LAYOUT));
        boolean useOcr = !Boolean.FALSE.equals(options.get(OCR));
        String pageChoice = (String) options.get(PAGES);

        OcrEngine[] ocr = new OcrEngine[1];             // started only when a scanned page turns up
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));
                FileResult result = convertOne(doc, input, pages, i, inputs.size(), useOcr, ocr, sheetPerPage, saveTo, progress);
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

    /** Finds the tables on the chosen pages and saves the workbook. Null if cancelled. */
    private FileResult convertOne(PDDocument doc, Path input, List<Integer> pages, int index, int fileCount,
                                  boolean useOcr, OcrEngine[] ocr, boolean sheetPerPage, SaveTo saveTo, Progress progress)
            throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<PageTables> found = new ArrayList<>();
        int tableCount = 0, scannedRead = 0, scannedSkipped = 0;

        for (int n = 0; n < pages.size(); n++) {
            if (progress.isCancelled()) return null;
            int page = pages.get(n);
            String status = "Page " + page + " of " + doc.getNumberOfPages();
            progress.file(index, (n + 0.1) / pages.size() * 0.9, status);
            progress.update((index + (double) n / pages.size()) / fileCount, "Reading " + input.getFileName() + " · " + status);

            List<Box> words = PageContent.words(doc, page - 1);
            List<PageContent.Rule> rules = PageContent.rules(doc.getPage(page - 1));
            if (letters(words) < HAS_TEXT) {
                if (!useOcr) {
                    scannedSkipped++;
                    continue;
                }
                words = readScan(renderer, page - 1, ocr);
                scannedRead++;
            }
            List<Table<Box>> tables = TableFinder.find(words, rules);
            if (!tables.isEmpty()) {
                found.add(new PageTables(page, tables));
                tableCount += tables.size();
            }
        }

        long sizeBefore = Files.size(input);
        if (tableCount == 0) {
            String why = scannedSkipped > 0
                    ? "No tables were found. Some pages are scans; tick “Read scanned pages with OCR” to read them."
                    : "No tables were found on " + (pages.size() == 1 ? "this page" : "these pages") + ", so nothing was saved.";
            return new FileResult(input, null, sizeBefore, 0, FileResult.Status.SKIPPED, why);
        }

        Path output = saveTo.outputFor(input, outputSuffix(), outputExtension(), doc.getNumberOfPages());
        SafeSave.write(output, temp -> {
            try (XSSFWorkbook workbook = workbook(found, sheetPerPage); OutputStream out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
        });

        String note = Format.count(tableCount, "table", "tables") + " from "
                + Format.count(found.size(), "page", "pages")
                + (scannedRead > 0 ? " · " + Format.count(scannedRead, "scanned page", "scanned pages") + " read with OCR" : "");
        return new FileResult(input, output, sizeBefore, Files.size(output), FileResult.Status.SUCCESS, note);
    }

    /** The tables found on one page. */
    private record PageTables(int page, List<Table<Box>> tables) {}

    private static int letters(List<Box> words) {
        int letters = 0;
        for (Box word : words) letters += word.text().length();
        return letters;
    }

    /** Draws a scanned page at 300 dpi and lets Tesseract find the words, measured in points like PDF text. */
    private static List<Box> readScan(PDFRenderer renderer, int pageIndex, OcrEngine[] ocr) throws IOException {
        try {
            if (ocr[0] == null) ocr[0] = new OcrEngine("eng");
            List<Box> words = new ArrayList<>();
            float toPoints = 72f / OCR_DPI;
            for (Word word : ocr[0].words(renderer.renderImageWithDPI(pageIndex, OCR_DPI, ImageType.GRAY))) {
                String text = word.getText().strip();
                if (text.isEmpty()) continue;
                Rectangle box = word.getBoundingBox();
                words.add(new Box(text, box.x * toPoints, box.y * toPoints,
                        (box.x + box.width) * toPoints, (box.y + box.height) * toPoints));
            }
            return words;
        } catch (Error e) {
            throw OcrEngine.cantStart(e);
        }
    }

    private static XSSFWorkbook workbook(List<PageTables> found, boolean sheetPerPage) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        for (PageTables page : found) {
            if (sheetPerPage) {
                Sheet sheet = workbook.createSheet("Page " + page.page());
                int row = 0;
                for (Table<Box> table : page.tables()) {
                    row = write(sheet, table, row) + 1;       // an empty row between tables
                }
                fitColumns(sheet);
            } else {
                for (int t = 0; t < page.tables().size(); t++) {
                    String name = "Page " + page.page() + (page.tables().size() > 1 ? " table " + (t + 1) : "");
                    Sheet sheet = workbook.createSheet(name);
                    write(sheet, page.tables().get(t), 0);
                    fitColumns(sheet);
                }
            }
        }
        return workbook;
    }

    /** Writes a table starting at {@code firstRow}; returns the row after it. */
    private static int write(Sheet sheet, Table<Box> table, int firstRow) {
        int r = firstRow;
        for (List<String> cells : table.rows()) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cells.size(); c++) {
                String text = cells.get(c);
                if (text.isEmpty()) continue;
                Cell cell = row.createCell(c);
                Double number = number(text);
                if (number != null) cell.setCellValue(number);
                else cell.setCellValue(text);
            }
        }
        return r;
    }

    /** Columns as wide as their longest text, within reason. */
    private static void fitColumns(Sheet sheet) {
        int columns = 0;
        for (Row row : sheet) columns = Math.max(columns, row.getLastCellNum());
        for (int c = 0; c < columns; c++) {
            int longest = 6;
            for (Row row : sheet) {
                Cell cell = row.getCell(c);
                if (cell != null) longest = Math.max(longest, cell.toString().length());
            }
            sheet.setColumnWidth(c, Math.min(longest + 2, 60) * 256);    // width is in 1/256ths of a character
        }
    }

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[-+]?\\d+([.,]\\d+)?");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("[-+]?\\d{1,3}([ , ]\\d{3})+([.,]\\d+)?");

    /**
     * The cell as a number, or null if it should stay text. Handles "1250", "1 250.00",
     * "1,250.00", "1250,5" (South African decimal comma) and "(1,250.00)" (accountants'
     * negative). Kept as text on purpose: anything with letters or currency signs,
     * numbers with leading zeros (account numbers, "007") and digit strings of 11 or
     * more (ID and phone numbers, which Excel would turn into 9.01E+12).
     */
    static Double number(String text) {
        String s = text.strip();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        if (!PLAIN_NUMBER.matcher(s).matches() && !GROUPED_NUMBER.matcher(s).matches()) return null;
        String digits = s.replaceAll("[^0-9]", "");
        String unsigned = s.replaceFirst("^[-+]", "");
        if (unsigned.length() > 1 && unsigned.startsWith("0") && !unsigned.matches("0[.,]\\d+")) return null;
        if (PLAIN_NUMBER.matcher(s).matches() && digits.length() >= 11) return null;

        // The last "." or "," is the decimal mark, unless it's followed by exactly three digits
        // and is the only mark ("1,250" = one thousand two hundred and fifty).
        int lastDot = s.lastIndexOf('.'), lastComma = s.lastIndexOf(',');
        int decimalMark = Math.max(lastDot, lastComma);
        boolean bothMarks = lastDot >= 0 && lastComma >= 0;
        if (decimalMark >= 0 && !bothMarks && s.length() - decimalMark - 1 == 3
                && s.indexOf(s.charAt(decimalMark)) == decimalMark && GROUPED_NUMBER.matcher(s).matches()) {
            decimalMark = -1;                                  // "1,250": a thousands separator
        }
        String whole = (decimalMark >= 0 ? s.substring(0, decimalMark) : s).replaceAll("[^0-9-+]", "");
        String fraction = decimalMark >= 0 ? s.substring(decimalMark + 1) : "";
        double value = Double.parseDouble(whole + (fraction.isEmpty() ? "" : "." + fraction));
        return negative ? -value : value;
    }
}
