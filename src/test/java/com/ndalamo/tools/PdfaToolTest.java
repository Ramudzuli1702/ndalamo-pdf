package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfaToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** A page using the four common non-embedded fonts: Helvetica, Helvetica Bold, Times Italic and Courier. */
    private Path letter(Standard14Fonts.FontName... fonts) throws Exception {
        Path file = dir.resolve("Letter.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < fonts.length; i++) {
                    c.beginText();
                    c.setFont(new PDType1Font(fonts[i]), 12);
                    c.newLineAtOffset(72, 760 - i * 24);
                    c.showText("Annual report 2026, page " + (i + 1) + " — “quoted”");
                    c.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private FileResult convert(Path pdf) throws Exception {
        return new PdfaTool().run(List.of(pdf), Map.of(), sameFolder, new Progress()).get(0);
    }

    /** Where each letter of the page starts, to check nothing moved. */
    private static List<Float> letterPositions(Path pdf) throws Exception {
        List<Float> xs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> letters) {
                    for (TextPosition letter : letters) xs.add(letter.getXDirAdj());
                }
            };
            stripper.getText(doc);
        }
        return xs;
    }

    @Test
    void fontsAreEmbeddedAndNothingMoves() throws Exception {
        Path pdf = letter(Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.HELVETICA_BOLD,
                Standard14Fonts.FontName.TIMES_ITALIC, Standard14Fonts.FontName.COURIER);
        byte[] before = TestFiles.bytes(pdf);

        FileResult result = convert(pdf);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertTrue(result.getNote().startsWith("PDF/A-2b · 4 fonts embedded"), result.getNote());
        List<String> names = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            var resources = doc.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                assertTrue(font.isEmbedded(), font.getName() + " embedded");
                names.add(font.getName().substring(7));                  // without the "ABCDEF+" subset tag
            }
        }
        assertTrue(names.containsAll(List.of("LiberationSans-Regular", "LiberationSans-Bold", "LiberationSerif-Italic",
                "LiberationMono-Regular")), names.toString());
        assertEquals(TestFiles.text(pdf), TestFiles.text(result.getOutput()), "the same text, quotes and dashes included");
        List<Float> was = letterPositions(pdf), now = letterPositions(result.getOutput());
        assertEquals(was.size(), now.size());
        for (int i = 0; i < was.size(); i++) assertEquals(was.get(i), now.get(i), 0.05, "letter " + i + " moved");
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void theArchiveDetailsAreThere() throws Exception {
        FileResult result = convert(letter(Standard14Fonts.FontName.HELVETICA));

        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertEquals(1.7f, doc.getVersion(), 0.001);
            assertFalse(doc.isEncrypted());
            assertEquals(1, doc.getDocumentCatalog().getOutputIntents().size(), "sRGB colour profile");
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>") && xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"));
            assertTrue(xmp.contains("<pdf:Producer>NdalamoPDF</pdf:Producer>"));
            assertEquals("NdalamoPDF", doc.getDocumentInformation().getProducer(), "the same in both places, as PDF/A checks");
            assertTrue(PdfaTool.check(doc).isEmpty(), PdfaTool.check(doc).toString());
        }
    }

    @Test
    void aFontWithNoStandInStopsItPlainly() throws Exception {
        Path pdf = dir.resolve("Maths.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.SYMBOL), 12);
                c.newLineAtOffset(72, 760);
                c.showText("αβ");                                  // α β, in the Symbol font
                c.endText();
            }
            doc.save(pdf.toFile());
        }

        FileResult result = convert(pdf);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().contains("Symbol") && result.getNote().contains("nothing was saved"), result.getNote());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no half-PDF/A file left behind");
        }
    }

    @Test
    void javaScriptAndAttachmentsAreRemoved() throws Exception {
        Path pdf = letter(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(doc.getDocumentCatalog());
            var js = new org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode();
            js.setNames(Map.of("hello", new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('hi')")));
            names.setJavascript(js);
            var files = new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
            var spec = new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
            spec.setFile("notes.txt");
            files.setNames(Map.of("notes.txt", spec));
            names.setEmbeddedFiles(files);
            doc.getDocumentCatalog().setNames(names);
            doc.save(pdf.toFile());
        }

        FileResult result = convert(pdf);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertTrue(result.getNote().contains("removed: JavaScript, attachments"), result.getNote());
    }

    @Test
    void namesWithTheSameWidthsHaveStandIns() {
        assertEquals("LiberationSans-Bold", FontEmbedder.twin("Arial,Bold"));
        assertEquals("LiberationSerif-BoldItalic", FontEmbedder.twin("ABCDEF+TimesNewRomanPS-BoldItalicMT"));
        assertEquals("LiberationMono-Regular", FontEmbedder.twin("CourierNewPSMT"));
        assertNull(FontEmbedder.twin("ArialNarrow"), "narrow letters have different widths");
        assertNull(FontEmbedder.twin("Symbol"));
        assertNull(FontEmbedder.twin("Calibri"), "no metric-compatible twin bundled");
    }
}
