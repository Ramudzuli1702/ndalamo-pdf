package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** OCR with the real bundled Tesseract and language files — no network, like the app. */
class OcrToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Map<String, Object> options(String language, boolean skipTextPages) {
        return Map.of(OcrTool.LANGUAGE, language, OcrTool.SKIP_TEXT_PAGES, skipTextPages);
    }

    @Test
    void aScanBecomesSearchable() throws IOException {
        Path scan = TestFiles.scannedPdf(dir, "Scan_0031.pdf",
                "Invoice number 4821", "Total due: R 1250.00", "Thank you for your business");
        assertEquals("", TestFiles.text(scan).strip(), "the scan has no text before OCR");
        byte[] before = TestFiles.bytes(scan);

        FileResult result = new OcrTool().run(List.of(scan), options(OcrTool.ENGLISH, true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertEquals(dir.resolve("Scan_0031_searchable.pdf"), result.getOutput());
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Invoice"), "found: " + text);
        assertTrue(text.contains("4821"), "found: " + text);
        assertTrue(text.contains("business"), "found: " + text);
        assertTrue(result.getNote().startsWith("1 page read"), result.getNote());
        assertArrayEquals(before, TestFiles.bytes(scan));
    }

    @Test
    void theAddedTextIsInvisible() throws IOException {
        Path scan = TestFiles.scannedPdf(dir, "scan.pdf", "Hello world");

        Path output = new OcrTool().run(List.of(scan), options(OcrTool.ENGLISH, true), sameFolder, new Progress())
                .get(0).getOutput();

        // The page must look exactly as it did: compare the two pages drawn as pictures.
        try (PDDocument a = Loader.loadPDF(scan.toFile()); PDDocument b = Loader.loadPDF(output.toFile())) {
            BufferedImage before = new PDFRenderer(a).renderImageWithDPI(0, 40);
            BufferedImage after = new PDFRenderer(b).renderImageWithDPI(0, 40);
            for (int y = 0; y < before.getHeight(); y += 3) {
                for (int x = 0; x < before.getWidth(); x += 3) {
                    assertEquals(before.getRGB(x, y), after.getRGB(x, y), "pixel " + x + "," + y + " changed");
                }
            }
        }
    }

    @Test
    void readsAfrikaans() throws IOException {
        Path scan = TestFiles.scannedPdf(dir, "verslag.pdf", "Die verslag is gereed vir die vergadering");

        FileResult result = new OcrTool().run(List.of(scan), options(OcrTool.AFRIKAANS, true), sameFolder, new Progress()).get(0);

        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("verslag") && text.contains("vergadering"), "found: " + text);
    }

    @Test
    void pagesThatAlreadyHaveTextAreSkipped() throws IOException {
        Path digital = TestFiles.pdf(dir, "typed.pdf", "This page was typed, not scanned, so it has text already", 2);

        FileResult result = new OcrTool().run(List.of(digital), options(OcrTool.ENGLISH, true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.getNote().startsWith("Every page already had text"), result.getNote());
    }

    @Test
    void aBlankScanSaysNothingCouldBeRead() throws IOException {
        Path blank = TestFiles.scannedPdf(dir, "blank.pdf");

        FileResult result = new OcrTool().run(List.of(blank), options(OcrTool.ENGLISH, true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.PARTIAL, result.getStatus());
        assertTrue(result.getNote().startsWith("No text could be read"));
    }

    @Test
    void cancellingWritesNothing() throws IOException {
        Path scan = TestFiles.scannedPdf(dir, "scan.pdf", "Hello");
        Progress progress = new Progress();
        progress.cancel();

        assertTrue(new OcrTool().run(List.of(scan), options(OcrTool.ENGLISH, true), sameFolder, progress).isEmpty());
        assertFalse(java.nio.file.Files.exists(dir.resolve("scan_searchable.pdf")));
    }
}
