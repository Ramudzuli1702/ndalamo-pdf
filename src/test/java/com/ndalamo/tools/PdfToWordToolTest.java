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

    @Test
    void fontNamesAreTheOnesWordKnows() {
        assertEquals("Times New Roman", PageText.familyName("ABCDEF+TimesNewRomanPS-BoldMT"));
        assertEquals("Arial", PageText.familyName("Helvetica-Oblique"));
        assertEquals("Calibri", PageText.familyName("BCDFEE+Calibri,Bold"));
        assertEquals("Book Antiqua", PageText.familyName("BookAntiqua"));
    }
}
