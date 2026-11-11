package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.PdfEdits;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    @TempDir Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** A page with lines of text, each drawn with one Tj in Helvetica 12. */
    private Path linesPdf(String name, int rotation, String... lines) throws Exception {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(rotation);
            doc.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                float y = 760;
                for (String line : lines) {
                    c.beginText();
                    c.setFont(font, 12);
                    c.newLineAtOffset(60, y);
                    c.showText(line);
                    c.endText();
                    y -= 24;
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static String text(Path file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** The area around the letters of {@code word} (its first appearance) on page 1, a little padded. */
    private static PdfEdits.Removal areaOf(Path file, String word) throws Exception {
        List<EditTool.Letter> letters = EditTool.letters(file, 1);
        StringBuilder all = new StringBuilder();
        for (EditTool.Letter l : letters) all.append(l.text());
        int at = all.indexOf(word);
        assertTrue(at >= 0, "found " + word);
        double left = 1, top = 1, right = 0, bottom = 0;
        for (int i = at; i < at + word.length(); i++) {
            EditTool.Letter l = letters.get(i);
            left = Math.min(left, l.left());
            top = Math.min(top, l.top());
            right = Math.max(right, l.right());
            bottom = Math.max(bottom, l.bottom());
        }
        return new PdfEdits.Removal(1, left + 0.0005, top, right - 0.0005, bottom);
    }

    /** Where the first letter of {@code word} sits (left edge, as a page fraction). */
    private static double leftOf(Path file, String word) throws Exception {
        List<EditTool.Letter> letters = EditTool.letters(file, 1);
        StringBuilder all = new StringBuilder();
        for (EditTool.Letter l : letters) all.append(l.text());
        return letters.get(all.indexOf(word)).left();
    }

    private List<FileResult> run(Path file, PdfEdits edits) throws Exception {
        return new EditTool().run(List.of(file), Map.of(EditTool.EDITS, edits), sameFolder, new Progress());
    }

    @Test
    void removedWordsAreGoneAndTheRestOfTheLineStaysPut() throws Exception {
        Path file = linesPdf("pay.pdf", 0, "Pay Thabo Nkosi R500 today", "Second line stays");
        byte[] before = Files.readAllBytes(file);
        double r500 = leftOf(file, "R500");

        var results = run(file, new PdfEdits(List.of(areaOf(file, "Thabo Nkosi")), List.of()));

        Path out = results.get(0).getOutput();
        String after = text(out);
        assertFalse(after.contains("Thabo"), after);
        assertFalse(after.contains("Nkosi"), after);
        assertTrue(after.contains("Pay"), after);
        assertTrue(after.contains("R500 today"), after);
        assertTrue(after.contains("Second line stays"), after);
        assertEquals(r500, leftOf(out, "R500"), 1e-4, "following text doesn't move");
        assertEquals(FileResult.Status.SUCCESS, results.get(0).getStatus());
        assertTrue(results.get(0).getNote().startsWith("Removed 11 letters"), results.get(0).getNote());
        assertArrayEquals(before, Files.readAllBytes(file), "the original is untouched");
    }

    @Test
    void embeddedTwoByteFontsAndKerningWork() throws Exception {
        Path file = dir.resolve("plex.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont plex = PageCanvas.load(doc, "/fonts/IBMPlexSans-Regular.ttf");
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.beginText();
                c.setFont(plex, 14);
                c.newLineAtOffset(72, 700);
                c.showTextWithPositioning(new Object[]{"Account ", -40f, "number 1234567 ", "is private"});
                c.endText();
            }
            doc.save(file.toFile());
        }
        double privateAt = leftOf(file, "private");

        Path out = run(file, new PdfEdits(List.of(areaOf(file, "1234567")), List.of())).get(0).getOutput();

        String after = text(out);
        assertFalse(after.contains("1234567"), after);
        assertTrue(after.contains("Account"), after);
        assertTrue(after.contains("private"), after);
        assertEquals(privateAt, leftOf(out, "private"), 1e-4);
    }

    @Test
    void olderTextInstructionsAreHandledToo() throws Exception {
        // ' (next line, then show) and " (set spacing, next line, then show), written by hand.
        Path file = dir.resolve("quotes.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            PDStream stream = new PDStream(doc);
            try (OutputStream o = stream.createOutputStream()) {
                o.write(("BT /F1 12 Tf 14 TL 60 760 Td (First line) Tj (Secret code here) ' "
                        + "0 0 (Another secret line) \" ET").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(file.toFile());
        }
        double hereAt = leftOf(file, "here");

        Path out = run(file, new PdfEdits(List.of(areaOf(file, "Secret"), areaOf(file, "secret")), List.of()))
                .get(0).getOutput();

        String after = text(out);
        assertFalse(after.toLowerCase().contains("secret"), after);
        assertTrue(after.contains("First line"), after);
        assertTrue(after.contains("code here"), after);
        assertTrue(after.contains("Another"), after);
        assertTrue(after.contains("line"), after);
        assertEquals(hereAt, leftOf(out, "here"), 1e-4, "' still moved to the next line");
    }

    @Test
    void highlightedTextIsFoundAndRemovedWithItsMark() throws Exception {
        Path file = linesPdf("marked.pdf", 0, "Keep this but remove this part", "Untouched");
        // Mark "remove this part" with a highlighter, as a PDF reader would.
        PdfEdits.Removal word = areaOf(file, "remove this part");
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDPage page = doc.getPage(0);
            float w = page.getCropBox().getWidth(), h = page.getCropBox().getHeight();
            float x1 = (float) (word.left() * w), x2 = (float) (word.right() * w);
            float y1 = (float) ((1 - word.bottom()) * h), y2 = (float) ((1 - word.top()) * h);
            var mark = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight();
            mark.setRectangle(new PDRectangle(x1, y1, x2 - x1, y2 - y1));
            mark.setQuadPoints(new float[]{x1, y2, x2, y2, x1, y1, x2, y1});
            page.getAnnotations().add(mark);
            doc.save(file.toFile());
        }

        List<double[]> marks = EditTool.highlights(file, 1);
        assertEquals(1, marks.size());
        double[] m = marks.get(0);
        Path out = run(file, new PdfEdits(List.of(new PdfEdits.Removal(1, m[0], m[1], m[2], m[3])), List.of()))
                .get(0).getOutput();

        String after = text(out);
        assertTrue(after.contains("Keep this but"), after);
        assertFalse(after.contains("remove"), after);
        assertTrue(after.contains("Untouched"), after);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(doc.getPage(0).getAnnotations().isEmpty(), "the highlighter mark went with the text");
        }
    }

    @Test
    void turnedPagesUseThePageAsShown() throws Exception {
        Path file = linesPdf("turned.pdf", 90, "Sideways page with a name Lerato in it");
        PdfEdits.Removal area = areaOf(file, "Lerato");
        // Written across the stored page, the line runs down the page as people see it turned.
        assertTrue(area.bottom() - area.top() > area.right() - area.left(), "tall as shown");

        Path out = run(file, new PdfEdits(List.of(area),
                List.of(new PdfEdits.Addition(1, 0.1, 0.1, "Added upright", 12, false, "#1F2430", "Sans"))))
                .get(0).getOutput();

        // PDFBox's text reader splits sideways lines up, so read the letters in drawing order instead.
        StringBuilder after = new StringBuilder();
        List<EditTool.Letter> letters = EditTool.letters(out, 1);
        for (EditTool.Letter l : letters) after.append(l.text());
        assertFalse(after.toString().contains("Lerato"), after.toString());
        assertTrue(after.toString().contains("Sideways page with a name"), after.toString());
        assertTrue(after.toString().contains("Added upright"), after.toString());
        // The added text reads left to right as shown: wide, near the top left.
        EditTool.Letter a = letters.get(after.indexOf("Added"));
        EditTool.Letter t = letters.get(after.indexOf("upright") + 6);
        assertTrue(t.left() > a.left() && Math.abs(t.top() - a.top()) < 0.01, "upright as shown");
        assertEquals(0.1, a.left(), 0.01);
    }

    @Test
    void addedTextIsWrittenInTheChosenFontIncludingTshivendaLetters() throws Exception {
        Path file = linesPdf("blank.pdf", 0, "Existing text");
        var additions = List.of(
                new PdfEdits.Addition(1, 0.1, 0.5, "Approved by the committee\nSecond line", 11, true, "#C0392B", "Serif"),
                new PdfEdits.Addition(1, 0.1, 0.7, "Ṱhalitha Ḓivhani", 12, false, "#1F2430", "Handwriting"));

        var result = run(file, new PdfEdits(List.of(), additions)).get(0);

        String after = text(result.getOutput());
        assertTrue(after.contains("Approved by the committee"), after);
        assertTrue(after.contains("Second line"), after);
        assertTrue(after.contains("Ṱhalitha"), after);
        assertTrue(after.contains("Existing text"), after);
        assertEquals("Added 2 texts", result.getNote());
        // The new text lands where it was placed: the first line's top a tenth of the way down... here half way.
        double top = EditTool.letters(result.getOutput(), 1).stream()
                .filter(l -> l.text().equals("A")).mapToDouble(EditTool.Letter::top).min().orElse(-1);
        assertEquals(0.5, top, 0.01);
    }

    @Test
    void scannedPagesHaveNoLettersToRemoveAndSaySo() throws Exception {
        Path scan = TestFiles.scannedPdf(dir, "scan.pdf", "A scanned line of text");
        assertTrue(EditTool.letters(scan, 1).isEmpty());

        var result = run(scan, new PdfEdits(List.of(new PdfEdits.Removal(1, 0, 0, 1, 1)), List.of())).get(0);

        assertEquals(FileResult.Status.PARTIAL, result.getStatus());
        assertTrue(result.getNote().contains("Redact"), result.getNote());
    }

    @Test
    void needsSomeChangesBeforeItRuns() {
        EditTool tool = new EditTool();
        assertFalse(tool.checkOptions(Map.of()) == null);
        assertFalse(tool.checkOptions(Map.of(EditTool.EDITS, new PdfEdits(List.of(), List.of()))) == null);
        assertEquals(null, tool.checkOptions(Map.of(EditTool.EDITS, new PdfEdits(
                List.of(new PdfEdits.Removal(1, 0, 0, 1, 1)), List.of()))));
    }
}
