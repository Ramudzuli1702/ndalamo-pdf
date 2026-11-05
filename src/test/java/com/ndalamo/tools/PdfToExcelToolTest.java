package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfToExcelToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static final String[][] STOCK = {
            {"Item", "Count", "Price"},
            {"Printer paper", "12", "1 250.00"},
            {"Toner", "3", "800.50"},
            {"Account 0042", "007", "(15.00)"}};

    private static Map<String, Object> options(String layout, boolean ocr) {
        Map<String, Object> options = new HashMap<>();
        options.put(PdfToExcelTool.LAYOUT, layout);
        options.put(PdfToExcelTool.OCR, ocr);
        options.put(PdfToExcelTool.PAGES, "");
        return options;
    }

    private static String text(Sheet sheet, int row, int column) {
        Cell cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(column);
        if (cell == null) return "";
        return cell.getCellType() == CellType.NUMERIC ? String.valueOf(cell.getNumericCellValue()) : cell.getStringCellValue();
    }

    @Test
    void aRuledTableBecomesASheetWithRealNumbers() throws Exception {
        Path pdf = TestFiles.ruledTablePdf(dir, "Stock.pdf", STOCK);
        byte[] before = TestFiles.bytes(pdf);

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, true),
                sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertEquals(dir.resolve("Stock_tables.xlsx"), result.getOutput());
        assertEquals("1 table from 1 page", result.getNote());
        try (XSSFWorkbook workbook = new XSSFWorkbook(result.getOutput().toFile())) {
            assertEquals(1, workbook.getNumberOfSheets());
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Page 1", sheet.getSheetName());
            assertEquals("Item", text(sheet, 0, 0));
            assertEquals("Printer paper", text(sheet, 1, 0));
            assertEquals(12.0, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(1250.0, sheet.getRow(1).getCell(2).getNumericCellValue());
            assertEquals(800.5, sheet.getRow(2).getCell(2).getNumericCellValue());
            assertEquals(-15.0, sheet.getRow(3).getCell(2).getNumericCellValue(), "accountants' brackets mean minus");
            assertEquals(CellType.STRING, sheet.getRow(3).getCell(1).getCellType(), "007 keeps its zeros");
            assertEquals(4, sheet.getLastRowNum() + 1, "the title and closing sentence are not in the table");
        }
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void aTableWithoutLinesIsFoundFromTheSpacing() throws Exception {
        String[][] statement = {
                {"Date", "Description", "Amount", "Balance"},
                {"01 Oct", "Opening balance", "", "5 000.00"},
                {"03 Oct", "Shoprite Soweto", "-412.35", "4 587.65"},
                {"15 Oct", "Salary", "18 500.00", "23 087.65"},
                {"28 Oct", "Debit order: insurance", "-650.00", "22 437.65"}};
        Path pdf = TestFiles.spacedTablePdf(dir, "Statement.pdf", statement, new float[]{72, 140, 330, 430});

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, true),
                sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        try (XSSFWorkbook workbook = new XSSFWorkbook(result.getOutput().toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(5, sheet.getLastRowNum() + 1, "the letter above the table is left out");
            assertEquals("Debit order: insurance", text(sheet, 4, 1));
            assertEquals(-412.35, sheet.getRow(2).getCell(2).getNumericCellValue(), 0.001);
            assertEquals(22437.65, sheet.getRow(4).getCell(3).getNumericCellValue(), 0.001);
            assertEquals("", text(sheet, 1, 2), "an empty cell stays empty");
        }
    }

    @Test
    void oneSheetPerPagePutsEachPagesTablesTogether() throws Exception {
        Path pdf = TestFiles.ruledTablePdf(dir, "Stock.pdf", STOCK);

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_PAGE, true),
                sameFolder, new Progress()).get(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(result.getOutput().toFile())) {
            assertEquals("Page 1", workbook.getSheetAt(0).getSheetName());
            assertEquals("Toner", text(workbook.getSheetAt(0), 2, 0));
        }
    }

    @Test
    void aScannedTableIsReadWithOcr() throws Exception {
        String[][] rows = {{"Item", "Count"}, {"Paper", "12"}, {"Toner", "3"}};
        Path pdf = TestFiles.scannedTablePdf(dir, "Scan.pdf", rows);

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, true),
                sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertTrue(result.getNote().contains("1 scanned page read with OCR"), result.getNote());
        try (XSSFWorkbook workbook = new XSSFWorkbook(result.getOutput().toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Paper", text(sheet, 1, 0));
            assertEquals(12.0, sheet.getRow(1).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void withoutOcrAScanExplainsHowToReadIt() throws Exception {
        Path pdf = TestFiles.scannedTablePdf(dir, "Scan.pdf", new String[][]{{"Item", "Count"}, {"Paper", "12"}});

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, false),
                sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertTrue(result.getNote().contains("Read scanned pages with OCR"), result.getNote());
        assertNull(result.getOutput());
    }

    @Test
    void aPdfWithoutTablesSavesNothing() throws Exception {
        Path pdf = TestFiles.pdf(dir, "Letter.pdf", "Just a letter", 2);

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, true),
                sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertEquals("No tables were found on these pages, so nothing was saved.", result.getNote());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void onlyTheChosenPagesAreRead() throws Exception {
        Path pdf = TestFiles.ruledTablePdf(dir, "Stock.pdf", STOCK);
        Map<String, Object> options = options(PdfToExcelTool.SHEET_PER_TABLE, true);
        options.put(PdfToExcelTool.PAGES, "2");

        FileResult result = new PdfToExcelTool().run(List.of(pdf), options, sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus(), "the file has only one page");
    }

    @Test
    void cancellingWritesNothing() throws Exception {
        Path pdf = TestFiles.ruledTablePdf(dir, "Stock.pdf", STOCK);
        Progress progress = new Progress();
        progress.cancel();

        assertTrue(new PdfToExcelTool().run(List.of(pdf), options(PdfToExcelTool.SHEET_PER_TABLE, true),
                sameFolder, progress).isEmpty());
        assertFalse(Files.exists(dir.resolve("Stock_tables.xlsx")));
    }

    @Test
    void numbersAreReadTheWaySouthAfricansWriteThem() {
        assertEquals(1250.0, PdfToExcelTool.number("1 250"));
        assertEquals(1250.0, PdfToExcelTool.number("1,250"));
        assertEquals(1250.5, PdfToExcelTool.number("1 250,50"));
        assertEquals(1250.5, PdfToExcelTool.number("1,250.50"));
        assertEquals(12.5, PdfToExcelTool.number("12,5"));
        assertEquals(-40.0, PdfToExcelTool.number("(40)"));
        assertEquals(-3.25, PdfToExcelTool.number("-3.25"));
        assertEquals(0.75, PdfToExcelTool.number("0.75"));
        assertNull(PdfToExcelTool.number("R 1 250.00"), "currency stays text");
        assertNull(PdfToExcelTool.number("007"), "leading zeros stay");
        assertNull(PdfToExcelTool.number("8001015009087"), "an ID number must not become 8.00E+12");
        assertNull(PdfToExcelTool.number("12 Oct"));
        assertNull(PdfToExcelTool.number("1.2.3"));
    }
}
