package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfToWordToolTest {

    @TempDir
    Path dir;

    private static final String PAGE_TWO = "Thank you for considering my application. I look forward to hearing from you, "
            + "and I am happy to come in for an interview at any time that suits the office.";

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Map<String, Object> options(boolean pageBreaks) {
        Map<String, Object> options = new HashMap<>();
        options.put(PdfToWordTool.PAGE_BREAKS, pageBreaks);
        options.put(PdfToWordTool.PAGES, "");
        return options;
    }

    private Path convert(Path pdf, boolean pageBreaks) throws Exception {
        FileResult result = new PdfToWordTool().run(List.of(pdf), options(pageBreaks), sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        return result.getOutput();
    }

    private static XWPFDocument open(Path docx) throws Exception {
        try (InputStream in = Files.newInputStream(docx)) {
            return new XWPFDocument(in);
        }
    }

    private static List<String> texts(XWPFDocument word) {
        return word.getParagraphs().stream().map(XWPFParagraph::getText).filter(t -> !t.isBlank()).toList();
    }

    @Test
    void wrappedLinesBecomeParagraphs() throws Exception {
        Path pdf = TestFiles.letterPdf(dir, "Letter.pdf");
        byte[] before = TestFiles.bytes(pdf);
        assertTrue(TestFiles.text(pdf).matches("(?s).*[a-z]-\\R.*"), "the test letter has a word hyphenated across lines");

        Path docx = convert(pdf, true);

        assertEquals(dir.resolve("Letter_editable.docx"), docx);
        try (XWPFDocument word = open(docx)) {
            List<String> paragraphs = texts(word);
            assertEquals(List.of(
                    "Application for a bursary",
                    "4 November 2026",
                    "The Bursary Office",
                    "University of Johannesburg",
                    "Auckland Park",
                    "I am writing to apply for the bursary for the next academic year. I am a second-year undergraduate student "
                            + "in information technology and have kept an average above seventy percent in all my modules "
                            + "while working part time at the campus documentation centre to pay for my transport and textbooks.",
                    "My results are attached to this letter, together with a letter of support from my lecturer and "
                            + "a copy of my identity document, as the application form asks.",
                    PAGE_TWO), paragraphs);
        }
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void alignmentAndLookAreKept() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            XWPFParagraph title = word.getParagraphs().get(0);
            assertEquals(ParagraphAlignment.CENTER, title.getAlignment());
            XWPFRun titleRun = title.getRuns().get(0);
            assertTrue(titleRun.isBold());
            assertEquals(16.0, titleRun.getFontSizeAsDouble());
            assertEquals("Arial", titleRun.getFontFamily(), "Helvetica is called Arial in Word");

            assertEquals(ParagraphAlignment.RIGHT, word.getParagraphs().get(1).getAlignment(), "the date");

            XWPFParagraph results = word.getParagraphs().stream()
                    .filter(p -> p.getText().startsWith("My results")).findFirst().orElseThrow();
            XWPFRun attached = results.getRuns().stream()
                    .filter(r -> r.text().contains("attached")).findFirst().orElseThrow();
            assertEquals("attached", attached.text().strip());
            assertTrue(attached.isItalic());
            assertEquals("C81E1E", attached.getColor());
            assertFalse(results.getRuns().get(0).isItalic());
        }
    }

    @Test
    void eachPdfPageStartsANewPageWhenAsked() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            XWPFParagraph pageTwo = word.getParagraphs().stream()
                    .filter(p -> p.getText().startsWith("Thank you")).findFirst().orElseThrow();
            assertTrue(pageTwo.isPageBreak());
            long breaks = word.getParagraphs().stream().filter(XWPFParagraph::isPageBreak).count();
            assertEquals(1, breaks);
        }
    }

    @Test
    void withoutPageBreaksTextFlowsOn() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), false))) {
            assertTrue(word.getParagraphs().stream().noneMatch(XWPFParagraph::isPageBreak));
        }
    }

    @Test
    void thePageSizeMatchesThePdf() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            var size = word.getDocument().getBody().getSectPr().getPgSz();
            assertEquals(595 * 20, ((Number) size.getW()).intValue(), 20);     // A4, in twentieths of a point
            assertEquals(842 * 20, ((Number) size.getH()).intValue(), 20);
            var margins = word.getDocument().getBody().getSectPr().getPgMar();
            assertEquals(72 * 20, ((Number) margins.getLeft()).intValue(), 20);
        }
    }

    @Test
    void aScanSaysItNeedsOcrFirst() throws Exception {
        Path scan = TestFiles.scannedPdf(dir, "Scan.pdf", "Hello there");

        FileResult result = new PdfToWordTool().run(List.of(scan), options(true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.PARTIAL, result.getStatus());
        assertTrue(result.getNote().contains("run OCR PDF on it first"), result.getNote());
    }

    @Test
    void onlyChosenPagesAreConverted() throws Exception {
        Path pdf = TestFiles.letterPdf(dir, "Letter.pdf");
        Map<String, Object> options = options(true);
        options.put(PdfToWordTool.PAGES, "2");

        FileResult result = new PdfToWordTool().run(List.of(pdf), options, sameFolder, new Progress()).get(0);

        try (XWPFDocument word = open(result.getOutput())) {
            assertEquals(List.of(PAGE_TWO), texts(word));
        }
    }

    @Test
    void cancellingWritesNothing() throws Exception {
        Path pdf = TestFiles.letterPdf(dir, "Letter.pdf");
        Progress progress = new Progress();
        progress.cancel();

        assertTrue(new PdfToWordTool().run(List.of(pdf), options(true), sameFolder, progress).isEmpty());
        assertFalse(Files.exists(dir.resolve("Letter_editable.docx")));
    }

    private static XWPFParagraph find(XWPFDocument word, String start) {
        return word.getParagraphs().stream().filter(p -> p.getText().startsWith(start)).findFirst()
                .orElseThrow(() -> new AssertionError("no paragraph starting \"" + start + "\" in " + texts(word)));
    }

    @Test
    void biggerTextBecomesHeadings() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.structuredPdf(dir, "Rules.pdf"), true))) {
            assertEquals("Heading1", find(word, "Club rules").getStyle());
            assertEquals("Heading2", find(word, "Membership").getStyle());
            assertEquals("Heading2", find(word, "Meetings").getStyle());
            assertNull(find(word, "Read the").getStyle(), "body text stays plain");
            assertNotNull(word.getStyles().getStyle("Heading1"), "Word needs the style defined to use it");
        }
    }

    @Test
    void bulletsAndNumbersBecomeWordLists() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.structuredPdf(dir, "Rules.pdf"), true))) {
            XWPFParagraph bullet = find(word, "Bring your identity document.");
            assertNotNull(bullet.getNumID(), "a real list item, not a typed bullet");
            assertEquals("bullet", bullet.getNumFmt());
            assertEquals(bullet.getNumID(), find(word, "Attend one meeting first.").getNumID(), "one list");

            XWPFParagraph three = find(word, "The chairperson opens the meeting.");
            assertEquals("decimal", three.getNumFmt());
            assertEquals(3, three.getNumStartOverride().intValue(), "numbering continues at 3 as in the PDF");
            XWPFParagraph sub = find(word, "Apologies are read out.");
            assertEquals(three.getNumID(), sub.getNumID());
            assertEquals(1, sub.getNumIlvl().intValue(), "a level deeper");
            assertEquals("lowerLetter", sub.getNumFmt());
            assertEquals(three.getNumID(), find(word, "The treasurer reports.").getNumID());
        }
    }

    @Test
    void underlinesAreKeptButBoxBordersAreNot() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.structuredPdf(dir, "Rules.pdf"), true))) {
            List<String> underlined = find(word, "Read the").getRuns().stream()
                    .filter(r -> r.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE)
                    .map(XWPFRun::text).toList();
            assertEquals(List.of("joining form"), underlined);
            assertTrue(find(word, "Note:").getRuns().stream()
                    .allMatch(r -> r.getUnderline() == org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE));
        }
    }

    @Test
    void picturesGoBetweenTheRightParagraphs() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.picturesPdf(dir, "Report.pdf"), true))) {
            List<String> order = word.getParagraphs().stream()
                    .map(p -> p.getRuns().stream().anyMatch(r -> !r.getEmbeddedPictures().isEmpty()) ? "[picture]" : p.getText())
                    .filter(t -> !t.isBlank()).toList();
            assertEquals(List.of("[picture]", "Site visit report", "The new classroom block was finished in October.",
                    "[picture]", "The roof was checked after the first heavy rain."), order, "the dot is left out");
        }
    }

    @Test
    void picturesKeepTheirSizeAlignmentAndQuality() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.picturesPdf(dir, "Report.pdf"), true))) {
            List<XWPFParagraph> withPictures = word.getParagraphs().stream()
                    .filter(p -> p.getRuns().stream().anyMatch(r -> !r.getEmbeddedPictures().isEmpty())).toList();
            XWPFParagraph logo = withPictures.get(0), photo = withPictures.get(1);
            assertEquals(ParagraphAlignment.RIGHT, logo.getAlignment());
            assertEquals(ParagraphAlignment.CENTER, photo.getAlignment());

            var photoData = photo.getRuns().get(0).getEmbeddedPictures().get(0).getPictureData();
            assertArrayEquals(Files.readAllBytes(dir.resolve("photo.jpg")), photoData.getData(), "the JPEG is copied, not re-compressed");
            long widthEmu = photo.getRuns().get(0).getEmbeddedPictures().get(0).getCTPicture().getSpPr().getXfrm().getExt().getCx();
            assertEquals(200, widthEmu / 12700.0, 0.5, "200 points wide, as in the PDF");

            var logoData = logo.getRuns().get(0).getEmbeddedPictures().get(0).getPictureData();
            assertEquals("png", logoData.suggestFileExtension());
            var logoImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(logoData.getData()));
            assertTrue(logoImage.getColorModel().hasAlpha(), "the logo keeps its transparent background");
            assertEquals(0, logoImage.getRGB(0, 0) >>> 24, "corner is see-through");
        }
    }

    @Test
    void picturesCanBeLeftOut() throws Exception {
        Map<String, Object> options = options(true);
        options.put(PdfToWordTool.PICTURES, false);
        FileResult result = new PdfToWordTool().run(List.of(TestFiles.picturesPdf(dir, "Report.pdf")), options, sameFolder,
                new Progress()).get(0);
        try (XWPFDocument word = open(result.getOutput())) {
            assertTrue(word.getAllPictures().isEmpty());
        }
    }

    /** The document from top to bottom: paragraph texts, and "[table]" where a table is. */
    private static List<String> bodyOrder(XWPFDocument word) {
        return word.getBodyElements().stream()
                .map(e -> e instanceof org.apache.poi.xwpf.usermodel.XWPFTable ? "[table]" : ((XWPFParagraph) e).getText())
                .filter(t -> !t.isBlank()).toList();
    }

    @Test
    void aRuledTableBecomesAWordTableInItsPlace() throws Exception {
        String[][] stock = {{"Item", "Count", "Price"}, {"Printer paper", "12", "1 250.00"}, {"Toner", "3", "800.50"}};
        try (XWPFDocument word = open(convert(TestFiles.ruledTablePdf(dir, "Stock.pdf", stock), true))) {
            assertEquals(List.of("Stock report for November", "[table]", "Counted by the night shift."), bodyOrder(word),
                    "the table's words are in the table only, not repeated as paragraphs");
            var table = word.getTables().get(0);
            assertEquals(3, table.getNumberOfRows());
            assertEquals(3, table.getRow(0).getTableCells().size());
            assertEquals("Printer paper", table.getRow(1).getCell(0).getText());
            assertEquals("1 250.00", table.getRow(1).getCell(2).getText());
            assertEquals(ParagraphAlignment.LEFT, table.getRow(1).getCell(2).getParagraphs().get(0).getAlignment(),
                    "the PDF wrote this number at the left of its cell, so Word does too");
            assertNotEquals(org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType.NONE, table.getInsideHBorderType(),
                    "the PDF drew borders, so the Word table has them");
            int widthTwips = table.getRow(0).getCell(0).getWidth();
            assertEquals(130 * 20, widthTwips, 40, "columns as wide as in the PDF");
        }
    }

    @Test
    void aSpacedTableHasNoBorders() throws Exception {
        String[][] statement = {
                {"Date", "Description", "Amount", "Balance"},
                {"03 Oct", "Shoprite Soweto", "-412.35", "4 587.65"},
                {"15 Oct", "Salary", "18 500.00", "23 087.65"},
                {"28 Oct", "Debit order", "-650.00", "22 437.65"}};
        try (XWPFDocument word = open(convert(TestFiles.spacedTablePdf(dir, "Statement.pdf", statement,
                new float[]{72, 140, 330, 430}), true))) {
            assertEquals(1, word.getTables().size());
            var table = word.getTables().get(0);
            assertEquals("Salary", table.getRow(2).getCell(1).getText());
            var border = table.getInsideHBorderType();     // no border setting at all also means no borders in Word
            assertTrue(border == null || border == org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType.NONE, "border: " + border);
            assertTrue(bodyOrder(word).get(0).startsWith("Dear customer"), "the letter above stays a paragraph");
        }
    }

    @Test
    void ordinaryTextHasNoTables() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            assertTrue(word.getTables().isEmpty());
        }
        try (XWPFDocument word = open(convert(TestFiles.structuredPdf(dir, "Rules.pdf"), true))) {
            assertTrue(word.getTables().isEmpty(), "a box around one note is not a table");
        }
    }

    @Test
    void twoColumnsAreReadLeftColumnFirst() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.twoColumnPdf(dir, "Newsletter.pdf"), true))) {
            List<String> expected = new java.util.ArrayList<>();
            expected.add("How stokvels work");
            expected.addAll(List.of(TestFiles.COLUMN_PARAGRAPHS));
            expected.add("Ndalamo Community Newsletter, November 2026, written for members of local savings groups.");
            assertEquals(expected, texts(word), "no mixing of the two columns' lines");
        }
    }

    @Test
    void aLetterIsNotMistakenForTwoColumns() throws Exception {
        // The letter has a date on the right and short address lines on the left.
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            assertEquals("Application for a bursary", texts(word).get(0));
            assertEquals("4 November 2026", texts(word).get(1));
        }
    }

    @Test
    void aFormWithLabelsAndAnswersIsNotTwoColumns() throws Exception {
        // Short labels on the left, answers on the right: must stay "label answer" per line.
        Path form = dir.resolve("Form.pdf");
        var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
        String[][] rows = {{"Name:", "Thabo Nkosi"}, {"Student number:", "220145678"}, {"Faculty:", "Science"},
                {"Year of study:", "Second"}, {"Email:", "thabo@example.org"}, {"Cell:", "082 555 0101"}};
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            try (var c = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                for (int i = 0; i < rows.length; i++) {
                    for (int col = 0; col < 2; col++) {
                        c.beginText();
                        c.setFont(font, 11);
                        c.newLineAtOffset(col == 0 ? 72 : 330, 700 - i * 20);
                        c.showText(rows[i][col]);
                        c.endText();
                    }
                }
            }
            doc.save(form.toFile());
        }
        try (XWPFDocument word = open(convert(form, true))) {
            assertEquals("Name: Thabo Nkosi", texts(word).get(0));
        }
    }

    @Test
    void pageNumbersAndRepeatedHeadersGoInWordsHeaderAndFooter() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.reportPdf(dir, "Report.pdf", 3), true))) {
            assertEquals(List.of("This is page 1 of the report.", "This is page 2 of the report.", "This is page 3 of the report."),
                    texts(word), "no page numbers or repeated title in the body");
            assertEquals("Ndalamo Annual Report 2026", word.getHeaderList().get(0).getText().strip());
            var footer = word.getFooterList().get(0);
            assertEquals(ParagraphAlignment.CENTER, footer.getParagraphs().get(0).getAlignment());
            assertEquals("PAGE", footer.getParagraphs().get(0).getCTP().getFldSimpleArray(0).getInstr(),
                    "Word's own page number, which keeps counting as the text changes");
        }
    }

    @Test
    void aOnePageLetterKeepsItsTitle() throws Exception {
        // The letter's title sits near the top of the page but isn't repeated, so it stays in the body.
        try (XWPFDocument word = open(convert(TestFiles.letterPdf(dir, "Letter.pdf"), true))) {
            assertEquals("Application for a bursary", texts(word).get(0));
            assertTrue(word.getHeaderList().isEmpty());
        }
    }

    @Test
    void aPictureInTheRightColumnComesAfterTheLeftColumn() throws Exception {
        try (XWPFDocument word = open(convert(TestFiles.twoColumnPdf(dir, "Newsletter.pdf", true), true))) {
            List<String> order = word.getParagraphs().stream()
                    .map(p -> p.getRuns().stream().anyMatch(r -> !r.getEmbeddedPictures().isEmpty()) ? "[picture]" : p.getText())
                    .filter(t -> !t.isBlank()).toList();
            assertEquals("[picture]", order.get(3), "after the title and both left-column paragraphs: " + order);
            assertEquals(TestFiles.COLUMN_PARAGRAPHS[2], order.get(4));
        }
    }

    @Test
    void listMarkersAreRecognised() {
        assertEquals("bullet", marker("•").format());
        assertEquals("bullet", marker("").format(), "Word's Symbol-font bullet");
        assertEquals(3, marker("3.").number());
        assertEquals("%1.", marker("3.").pattern());
        assertEquals("lowerLetter", marker("b)").format());
        assertEquals("(%1)", marker("(iv)").pattern());
        assertEquals(4, marker("(iv)").number());
        assertEquals("upperRoman", marker("II.").format());
        assertNull(marker("2026."), "a year is not a list number");
        assertNull(marker("3"), "a number without . or ) is just a number");
        assertNull(WordLayout.marker(List.of(word("-", 72), word("well", 76))), "a dash right before a word is not a bullet");
    }

    private static PageText.TextWord word(String text, float left) {
        return new PageText.TextWord(text, left, left + text.length() * 5, 100, 11, "Arial", false, false, 0, false);
    }

    private static WordLayout.ListMarker marker(String text) {
        return WordLayout.marker(List.of(word(text, 72), word("Item", 100)));
    }

    @Test
    void fontNamesAreTheOnesWordKnows() {
        assertEquals("Times New Roman", PageText.familyName("ABCDEF+TimesNewRomanPS-BoldMT"));
        assertEquals("Arial", PageText.familyName("Helvetica-Oblique"));
        assertEquals("Calibri", PageText.familyName("BCDFEE+Calibri,Bold"));
        assertEquals("Book Antiqua", PageText.familyName("BookAntiqua"));
    }
}
