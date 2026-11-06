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
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedactToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** Page 1: an application with personal details; page 2: nothing personal. Title and a bookmark name the applicant. */
    private Path application() throws Exception {
        Path file = dir.resolve("Application.pdf");
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        String[] lines = {"Applicant: Thabo Nkosi", "ID number: 800101 5009 087", "Cell: 082 555 0101",
                "Email: thabo.nkosi@example.org", "Annual income: R 120 000", "Referee: Ann Mokoena",
                "Signed by the applicant, Thabo", "Nkosi, on 3 November."};
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4), second = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            doc.addPage(second);
            try (PDPageContentStream c = new PDPageContentStream(doc, first)) {
                for (int i = 0; i < lines.length; i++) write(c, font, 72, 760 - i * 20, lines[i]);
            }
            try (PDPageContentStream c = new PDPageContentStream(doc, second)) {
                write(c, font, 72, 760, "Nothing personal on this page.");
            }
            doc.getDocumentInformation().setTitle("Thabo Nkosi application");
            doc.getDocumentInformation().setAuthor("Thabo Nkosi");
            PDDocumentOutline outline = new PDDocumentOutline();
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle("Details of Thabo Nkosi");
            item.setDestination(first);
            outline.addLast(item);
            doc.getDocumentCatalog().setDocumentOutline(outline);
            doc.save(file.toFile());
        }
        return file;
    }

    private static void write(PDPageContentStream c, PDType1Font font, float x, float y, String text) throws Exception {
        c.beginText();
        c.setFont(font, 12);
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
    }

    private static Map<String, Object> words(String words) {
        Map<String, Object> options = new HashMap<>();
        options.put(RedactTool.FIND, RedactTool.BY_TEXT);
        options.put(RedactTool.WORDS, words);
        options.put(RedactTool.PAGES, "");
        return options;
    }

    /** Everything in the file a determined person could read: its text, and its raw (uncompressed) bytes. */
    private static String everything(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            doc.setAllSecurityToBeRemoved(true);
            doc.save(raw, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
            return TestFiles.text(pdf) + "\n" + raw.toString(StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void redactedWordsAreGoneFromTheFileNotJustCovered() throws Exception {
        Path pdf = application();
        byte[] before = TestFiles.bytes(pdf);
        assertTrue(everything(pdf).contains("Thabo"), "control: the check below can see the name when it's there");

        FileResult result = new RedactTool().run(List.of(pdf), words("Thabo Nkosi"), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertTrue(result.getNote().startsWith("3 parts removed on 1 page"), result.getNote());   // one name split over two lines
        String all = everything(result.getOutput());
        assertFalse(all.contains("Thabo"), "the name must not survive anywhere in the file");
        assertFalse(all.contains("Nkosi"));
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Applicant:"), "the words around it can still be searched: " + text);
        assertTrue(text.contains("Signed by the applicant,"));
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void noPartOfARemovedWordShowsAroundItsBox() throws Exception {
        // Every pixel the name covered on the original page must be black on the redacted page: no letter
        // tops or tails peeking out of the box.
        Path pdf = application();
        FileResult result = new RedactTool().run(List.of(pdf), words("Thabo Nkosi"), sameFolder, new Progress()).get(0);
        int dpi = 144;
        java.awt.image.BufferedImage before, after;
        try (PDDocument a = Loader.loadPDF(pdf.toFile()); PDDocument b = Loader.loadPDF(result.getOutput().toFile())) {
            before = new org.apache.pdfbox.rendering.PDFRenderer(a).renderImageWithDPI(0, dpi);
            after = new org.apache.pdfbox.rendering.PDFRenderer(b).renderImageWithDPI(0, dpi);
        }
        // Line 1, "Applicant: Thabo Nkosi" in 12 pt Helvetica at x 72, baseline 760: the name starts after "Applicant: ".
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float nameLeft = 72 + font.getStringWidth("Applicant: ") / 1000 * 12;
        float scale = dpi / 72f;
        int x0 = (int) (nameLeft * scale) + 1, x1 = (int) ((72 + font.getStringWidth("Applicant: Thabo Nkosi") / 1000 * 12) * scale);
        int y0 = (int) ((842 - 760 - 12) * scale), y1 = (int) ((842 - 760 + 4) * scale);
        int letterPixels = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if ((before.getRGB(x, y) & 0xFF) < 200) {                  // part of a letter on the original
                    letterPixels++;
                    assertTrue((after.getRGB(x, y) & 0xFF) < 40, "a letter shows through at " + x + "," + y);
                }
            }
        }
        assertTrue(letterPixels > 50, "the check looked at the name's letters: " + letterPixels);
    }

    @Test
    void pagesWithNothingToRemoveAreLeftAlone() throws Exception {
        FileResult result = new RedactTool().run(List.of(application()), words("Thabo Nkosi"), sameFolder, new Progress()).get(0);
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            var untouched = doc.getPage(1).getResources();
            assertTrue(untouched.getFontNames().iterator().hasNext(), "page 2 still has its real text and font");
            assertFalse(untouched.getXObjectNames().iterator().hasNext(), "and wasn't turned into a picture");
        }
    }

    @Test
    void wholeWordsOnly() throws Exception {
        FileResult result = new RedactTool().run(List.of(application()), words("Ann"), sameFolder, new Progress()).get(0);

        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Annual income"), "\"Ann\" must not black out part of \"Annual\"");
        assertFalse(text.contains("Ann Mokoena"));
        assertTrue(text.contains("Mokoena"));
    }

    @Test
    void personalDetailsCanBeFoundByKind() throws Exception {
        Map<String, Object> options = words("");
        options.put(RedactTool.ID_NUMBERS, true);
        options.put(RedactTool.PHONE_NUMBERS, true);
        options.put(RedactTool.EMAILS, true);

        FileResult result = new RedactTool().run(List.of(application()), options, sameFolder, new Progress()).get(0);

        String all = everything(result.getOutput());
        assertFalse(all.contains("800101"), "ID number");
        assertFalse(all.contains("555 0101") || all.contains("5550101"), "phone number");
        assertFalse(all.contains("example.org"), "email address");
        assertTrue(TestFiles.text(result.getOutput()).contains("R 120 000"), "an amount isn't a phone number");
    }

    @Test
    void hiddenDetailsAndBookmarksAreCleaned() throws Exception {
        FileResult result = new RedactTool().run(List.of(application()), words("Thabo Nkosi"), sameFolder, new Progress()).get(0);
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertNull(doc.getDocumentInformation().getTitle());
            assertNull(doc.getDocumentInformation().getAuthor());
            PDOutlineItem bookmark = doc.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertEquals("Details of [redacted]", bookmark.getTitle());
            assertNotNull(bookmark.getDestination(), "the bookmark still goes to its page");
        }
    }

    @Test
    void aDrawnAreaIsRemovedOnEveryChosenPage() throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put(RedactTool.FIND, RedactTool.BY_AREA);
        options.put(RedactTool.AREA, new double[]{0, 0, 1, 0.12});      // the top 12% of each page
        options.put(RedactTool.PAGES, "");

        FileResult result = new RedactTool().run(List.of(application()), options, sameFolder, new Progress()).get(0);

        String text = TestFiles.text(result.getOutput());
        assertFalse(text.contains("Applicant"), "the top lines are gone");
        assertFalse(text.contains("Nothing personal"), "on page 2 too");
        assertTrue(text.contains("Annual income"), "lines below the area stay");
    }

    @Test
    void answersInFormFieldsAreRedactedToo() throws Exception {
        Map<String, String> answers = Map.of("name", "Thandeka Dlamini");
        Path filled = new FillFormsTool().run(List.of(TestFiles.formPdf(dir, "Form.pdf")),
                new HashMap<>(Map.of(FillFormsTool.ANSWERS, answers, FillFormsTool.FLATTEN, false)), sameFolder,
                new Progress()).get(0).getOutput();

        FileResult result = new RedactTool().run(List.of(filled), words("Dlamini"), sameFolder, new Progress()).get(0);

        assertTrue(result.getNote().contains("form answers were locked in"), result.getNote());
        assertFalse(everything(result.getOutput()).contains("Dlamini"));
    }

    @Test
    void nothingFoundSavesNothing() throws Exception {
        FileResult result = new RedactTool().run(List.of(application()), words("Zanele"), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertNull(result.getOutput());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void somethingToFindIsNeededFirst() {
        assertNotNull(new RedactTool().checkOptions(words("")));
        assertNull(new RedactTool().checkOptions(words("Thabo")));
        Map<String, Object> area = words("");
        area.put(RedactTool.FIND, RedactTool.BY_AREA);
        assertNotNull(new RedactTool().checkOptions(area));
    }
}
