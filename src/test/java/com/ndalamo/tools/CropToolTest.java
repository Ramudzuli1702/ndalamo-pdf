package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CropToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** A4 pages with a 200 × 100 point dark block at (200, 400) from the bottom-left; page 2 blank; page 3 turned. */
    private Path receipt() throws Exception {
        Path file = dir.resolve("Receipt.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int n = 0; n < 3; n++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                if (n == 1) continue;                                  // blank
                if (n == 2) page.setRotation(90);
                try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                    c.addRect(200, 400, 200, 100);
                    c.fill();
                    c.beginText();
                    c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    c.newLineAtOffset(40, 40);
                    c.showText("x");                                    // a small mark near the corner: there is content to trim to
                    c.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static Map<String, Object> options(String mode, double[] area) {
        Map<String, Object> options = new HashMap<>();
        options.put(CropTool.MODE, mode);
        options.put(CropTool.PAGES, "");
        if (area != null) options.put(CropTool.AREA, area);
        return options;
    }

    @Test
    void trimmingCutsAwayTheWhiteMargins() throws Exception {
        Path file = dir.resolve("Block.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.addRect(200, 400, 200, 100);
                c.fill();
            }
            doc.save(file.toFile());
        }
        byte[] before = TestFiles.bytes(file);

        FileResult result = new CropTool().run(List.of(file), options(CropTool.TRIM, null), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            PDRectangle box = doc.getPage(0).getCropBox();
            // The block, plus a little room (1.2% of the page) and the coarse 48 dpi check.
            assertEquals(200, box.getLowerLeftX(), 16);
            assertEquals(400, box.getLowerLeftY(), 16);
            assertEquals(200, box.getWidth(), 30);
            assertEquals(100, box.getHeight(), 30);
        }
        assertArrayEquals(before, TestFiles.bytes(file));
    }

    @Test
    void aDrawnAreaIsKeptOnEveryPageAndBlankPagesToo() throws Exception {
        // The top half of the page as shown.
        FileResult result = new CropTool().run(List.of(receipt()), options(CropTool.DRAWN, new double[]{0, 0, 1, 0.5}),
                sameFolder, new Progress()).get(0);

        assertEquals("3 pages cropped", result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            PDRectangle upright = doc.getPage(0).getCropBox();
            assertEquals(0, upright.getLowerLeftX(), 0.5);
            assertEquals(421, upright.getLowerLeftY(), 0.5, "the top half of an upright A4 page");
            assertEquals(595, upright.getWidth(), 0.5);
            // Page 3 is shown turned 90°: its top half as shown is the stored page's left half.
            PDRectangle turned = doc.getPage(2).getCropBox();
            assertEquals(0, turned.getLowerLeftX(), 0.5);
            assertEquals(297.5, turned.getWidth(), 0.5);
            assertEquals(842, turned.getHeight(), 0.5);
        }
    }

    @Test
    void trimmingLeavesBlankPagesAlone() throws Exception {
        FileResult result = new CropTool().run(List.of(receipt()), options(CropTool.TRIM, null), sameFolder, new Progress()).get(0);

        assertEquals("2 pages cropped · 1 blank page left as they were", result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertEquals(PDRectangle.A4.getWidth(), doc.getPage(1).getCropBox().getWidth(), 0.5);
        }
    }

    @Test
    void cropHidesButDoesNotRemove() throws Exception {
        // What the tool screen promises: the text outside the crop is still in the file.
        Path pdf = TestFiles.pdf(dir, "Letter.pdf", "Hello", 1);
        FileResult result = new CropTool().run(List.of(pdf), options(CropTool.DRAWN, new double[]{0.5, 0.5, 1, 1}),
                sameFolder, new Progress()).get(0);
        assertTrue(TestFiles.text(result.getOutput()).contains("Hello page 1"));
    }

    @Test
    void drawingTheAreaIsRequiredFirst() {
        assertNotNull(new CropTool().checkOptions(options(CropTool.DRAWN, null)));
        assertNull(new CropTool().checkOptions(options(CropTool.DRAWN, new double[]{0, 0, 1, 1})));
        assertNull(new CropTool().checkOptions(options(CropTool.TRIM, null)));
    }
}
