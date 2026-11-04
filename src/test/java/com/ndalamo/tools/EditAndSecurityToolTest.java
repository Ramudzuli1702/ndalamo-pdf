package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.PageStamp;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.services.FileInspector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Page numbers, Watermark, Protect, Unlock and the password check (chunk 6). */
class EditAndSecurityToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static String pageText(Path pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc);
        }
    }

    // ── Page numbers ─────────────────────────────────────────────────────

    private static Map<String, Object> numbers(String style, double start, boolean skipFirst) {
        Map<String, Object> o = new HashMap<>();
        o.put(PageNumbersTool.POSITION, PageNumbersTool.BOTTOM_CENTRE);
        o.put(PageNumbersTool.STYLE, style);
        o.put(PageNumbersTool.START, start);
        o.put(PageNumbersTool.SKIP_FIRST, skipFirst);
        o.put(PageNumbersTool.SIZE, 11.0);
        return o;
    }

    @Test
    void numbersEveryPage() throws IOException {
        Path input = TestFiles.pdf(dir, "report.pdf", "R", 3);
        byte[] before = TestFiles.bytes(input);

        FileResult result = new PageNumbersTool().run(List.of(input),
                numbers(PageNumbersTool.PAGE_OF, 1, false), sameFolder, new Progress()).get(0);

        assertTrue(pageText(result.getOutput(), 1).contains("Page 1 of 3"));
        assertTrue(pageText(result.getOutput(), 3).contains("Page 3 of 3"));
        assertArrayEquals(before, TestFiles.bytes(input));
    }

    @Test
    void skippingTheCoverStillCountsIt() throws IOException {
        Path input = TestFiles.pdf(dir, "report.pdf", "R", 3);

        FileResult result = new PageNumbersTool().run(List.of(input),
                numbers(PageNumbersTool.DASHES, 1, true), sameFolder, new Progress()).get(0);

        assertFalse(pageText(result.getOutput(), 1).contains("- 1 -"));
        assertTrue(pageText(result.getOutput(), 2).contains("- 2 -"));
    }

    @Test
    void numbersStartWhereAsked() {
        assertEquals("Page 5 of 7", PageNumbersTool.label(numbers(PageNumbersTool.PAGE_OF, 5, false), 1, 3));
        assertEquals("7", PageNumbersTool.label(numbers(PageNumbersTool.PLAIN, 5, false), 3, 3));
    }

    @Test
    void previewShowsTheNumberWhereItWillGo() {
        PageStamp stamp = new PageNumbersTool().previewStamp(numbers(PageNumbersTool.PLAIN, 1, false), 2, 4);
        assertEquals("2", stamp.text());
        assertEquals("bottom", stamp.vertical());
        assertEquals("centre", stamp.horizontal());
        assertNull(new PageNumbersTool().previewStamp(numbers(PageNumbersTool.PLAIN, 1, true), 1, 4));
    }

    // ── Watermark ────────────────────────────────────────────────────────

    private static Map<String, Object> watermark(String words, String place, String pages) {
        Map<String, Object> o = new HashMap<>();
        o.put(WatermarkTool.KIND, WatermarkTool.TEXT);
        o.put(WatermarkTool.WORDS, words);
        o.put(WatermarkTool.PLACE, place);
        o.put(WatermarkTool.OPACITY, 20.0);
        o.put(WatermarkTool.COLOUR, WatermarkTool.GREY);
        o.put(WatermarkTool.PAGES, pages);
        return o;
    }

    @Test
    void watermarksEveryPage() throws IOException {
        Path input = TestFiles.pdf(dir, "contract.pdf", "C", 2);
        byte[] before = TestFiles.bytes(input);

        // Centred, because text extraction can't read slanted text back as one word.
        FileResult result = new WatermarkTool().run(List.of(input),
                watermark("CONFIDENTIAL", WatermarkTool.CENTRE, ""), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(pageText(result.getOutput(), 1).contains("CONFIDENTIAL"));
        assertTrue(pageText(result.getOutput(), 2).contains("CONFIDENTIAL"));
        assertArrayEquals(before, TestFiles.bytes(input));
    }

    @Test
    void diagonalWatermarkIsSeeThroughAndUsesTheBundledFont() throws IOException {
        Path input = TestFiles.pdf(dir, "contract.pdf", "C", 1);

        FileResult result = new WatermarkTool().run(List.of(input),
                watermark("CONFIDENTIAL", WatermarkTool.DIAGONAL, ""), sameFolder, new Progress()).get(0);

        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            var resources = doc.getPage(0).getResources();
            boolean seeThrough = false;
            for (var name : resources.getExtGStateNames()) {
                Float alpha = resources.getExtGState(name).getNonStrokingAlphaConstant();
                if (alpha != null && Math.abs(alpha - 0.2f) < 0.001) seeThrough = true;
            }
            assertTrue(seeThrough, "expected a 20% opacity setting on the page");
            boolean plex = false;
            for (var name : resources.getFontNames()) {
                if (resources.getFont(name).getName().contains("IBMPlexSans-Bold")) plex = true;
            }
            assertTrue(plex, "expected the embedded IBM Plex Sans Bold");
        }
    }

    @Test
    void watermarkOnlyOnTheListedPagesAndInAnyLanguage() throws IOException {
        Path input = TestFiles.pdf(dir, "contract.pdf", "C", 3);

        FileResult result = new WatermarkTool().run(List.of(input),
                watermark("Ṱhalutshedzo", WatermarkTool.CENTRE, "2"), sameFolder, new Progress()).get(0);

        assertFalse(pageText(result.getOutput(), 1).contains("halutshedzo"));
        assertTrue(pageText(result.getOutput(), 2).contains("Ṱhalutshedzo"));
        assertEquals("Watermark on 1 page", result.getNote());
    }

    @Test
    void watermarkAsksForTextOrAPicture() {
        assertNotNull(new WatermarkTool().checkOptions(watermark("  ", WatermarkTool.DIAGONAL, "")));
        Map<String, Object> picture = watermark("x", WatermarkTool.DIAGONAL, "");
        picture.put(WatermarkTool.KIND, WatermarkTool.IMAGE);
        assertEquals("Choose a picture for the watermark.", new WatermarkTool().checkOptions(picture));
    }

    @Test
    void pictureWatermarkWorks() throws IOException {
        Path input = TestFiles.pdf(dir, "contract.pdf", "C", 1);
        Map<String, Object> options = watermark("", WatermarkTool.TILED, "");
        options.put(WatermarkTool.KIND, WatermarkTool.IMAGE);
        options.put(WatermarkTool.PICTURE, TestFiles.png(dir, "logo.png"));

        FileResult result = new WatermarkTool().run(List.of(input), options, sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
    }

    // ── Protect and Unlock ───────────────────────────────────────────────

    private static Map<String, Object> protect(String password, String confirm) {
        Map<String, Object> o = new HashMap<>();
        o.put(ProtectTool.PASSWORD, password);
        o.put(ProtectTool.CONFIRM, confirm);
        o.put(ProtectTool.ALLOW_PRINTING, true);
        o.put(ProtectTool.ALLOW_COPYING, false);
        return o;
    }

    @Test
    void protectNeedsThePasswordToOpen() throws IOException {
        Path input = TestFiles.pdf(dir, "payslip.pdf", "P", 1);
        byte[] before = TestFiles.bytes(input);

        Path output = new ProtectTool().run(List.of(input), protect("kiwi-1234", "kiwi-1234"), sameFolder,
                new Progress()).get(0).getOutput();

        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(output.toFile()).close());
        try (PDDocument doc = Loader.loadPDF(output.toFile(), "kiwi-1234")) {
            assertTrue(doc.getCurrentAccessPermission().canPrint());
            assertFalse(doc.getCurrentAccessPermission().canExtractContent());
        }
        assertArrayEquals(before, TestFiles.bytes(input));
    }

    @Test
    void protectChecksThePasswordsMatch() {
        ProtectTool tool = new ProtectTool();
        assertEquals("The two passwords don't match. Type them again.", tool.checkOptions(protect("abcd", "abce")));
        assertNotNull(tool.checkOptions(protect("abc", "abc")));
        assertNull(tool.checkOptions(protect("abcd", "abcd")));
    }

    @Test
    void unlockWithTheRightPassword() throws IOException {
        Path locked = TestFiles.lockedPdf(dir, "bank.pdf");

        FileResult result = new UnlockTool().run(List.of(locked),
                Map.of(PdfTool.PASSWORDS, Map.of(locked, "user-secret")), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {   // no password needed now
            assertFalse(doc.isEncrypted());
        }
    }

    @Test
    void unlockWithTheWrongPasswordSaysSo() throws IOException {
        Path locked = TestFiles.lockedPdf(dir, "bank.pdf");

        FileResult result = new UnlockTool().run(List.of(locked),
                Map.of(PdfTool.PASSWORDS, Map.of(locked, "guess")), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().startsWith("That password didn't work"));
    }

    @Test
    void unlockSkipsFilesWithoutAPassword() throws IOException {
        Path plain = TestFiles.pdf(dir, "plain.pdf", "X", 1);

        FileResult result = new UnlockTool().run(List.of(plain), Map.of(), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertNull(result.getOutput());
    }

    @Test
    void restrictionsOnlyComeOffWithTheFullPassword() throws IOException {
        Path restricted = TestFiles.restrictedPdf(dir, "statement.pdf");
        FileInspector inspector = new FileInspector();

        assertTrue(inspector.inspect(restricted).isRestricted());
        assertFalse(inspector.passwordWorks(restricted, ""));
        assertFalse(inspector.passwordWorks(restricted, "guess"));
        assertTrue(inspector.passwordWorks(restricted, "owner-secret"));

        FileResult refused = new UnlockTool().run(List.of(restricted), Map.of(), sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.FAILED, refused.getStatus());

        FileResult unlocked = new UnlockTool().run(List.of(restricted),
                Map.of(PdfTool.PASSWORDS, Map.of(restricted, "owner-secret")), sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.SUCCESS, unlocked.getStatus());
    }

    @Test
    void passwordCheckForLockedFiles() throws IOException {
        Path locked = TestFiles.lockedPdf(dir, "bank.pdf");
        FileInspector inspector = new FileInspector();

        assertTrue(inspector.inspect(locked).isPasswordProtected());
        assertTrue(inspector.passwordWorks(locked, "user-secret"));
        assertFalse(inspector.passwordWorks(locked, "nope"));
    }
}
