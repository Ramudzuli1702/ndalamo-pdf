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

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** A PDF with one page per array, each line of text written down the page; optionally a box drawn on a page. */
    private Path pdf(String name, int boxOnPage, String[]... pages) throws Exception {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 0; p < pages.length; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 780;
                    for (String line : pages[p]) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(72, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 20;
                    }
                    if (p == boxOnPage) {
                        cs.setNonStrokingColor(new Color(40, 90, 200));
                        cs.addRect(300, 200, 160, 120);
                        cs.fill();
                    }
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static final String[] COVER = {"Service agreement", "Between the client and the supplier", "Signed in Johannesburg"};
    private static final String[] TERMS = {"The supplier delivers the goods within ten days", "Payment is due within thirty days",
            "Late payment carries interest of two percent"};
    private static final String[] END = {"Either party may end this agreement", "with one month of written notice", "Thank you"};

    private CompareTool.Comparison compare(Path a, Path b) throws Exception {
        try (PDDocument da = Loader.loadPDF(a.toFile()); PDDocument db = Loader.loadPDF(b.toFile())) {
            return CompareTool.compare(CompareTool.read(da), CompareTool.read(db));
        }
    }

    private static Set<String> texts(Set<CompareTool.Word> words) {
        return words.stream().map(CompareTool.Word::text).collect(Collectors.toSet());
    }

    private List<FileResult> run(Map<String, Object> options, Path... files) throws Exception {
        return new CompareTool().run(List.of(files), options, sameFolder, new Progress());
    }

    @Test
    void identicalFilesHaveNoDifferences() throws Exception {
        Path a = pdf("a.pdf", -1, COVER, TERMS, END), b = pdf("b.pdf", -1, COVER, TERMS, END);
        byte[] before = TestFiles.bytes(a);

        FileResult result = run(Map.of(), a, b).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertEquals("No differences found", result.getNote());
        assertEquals(1, TestFiles.pageCount(result.getOutput()), "just the summary page");
        assertTrue(TestFiles.text(result.getOutput()).contains("No differences found."));
        assertEquals("a_compared.pdf", result.getOutput().getFileName().toString());
        assertArrayEquals(before, TestFiles.bytes(a));
    }

    @Test
    void aChangedWordIsMarkedOnBothSides() throws Exception {
        String[] newTerms = TERMS.clone();
        newTerms[1] = "Payment is due within fourteen days";
        Path a = pdf("a.pdf", -1, COVER, TERMS, END), b = pdf("b.pdf", -1, COVER, newTerms, END);

        CompareTool.Comparison found = compare(a, b);
        assertEquals(Set.of("thirty"), texts(found.removed()));
        assertEquals(Set.of("fourteen"), texts(found.added()));
        assertEquals(List.of(new CompareTool.Pair(0, 0), new CompareTool.Pair(1, 1), new CompareTool.Pair(2, 2)), found.pairs());

        FileResult result = run(Map.of(), a, b).get(0);
        assertEquals("Page 2 changed · 1 word removed, 1 added", result.getNote());
        assertEquals(2, TestFiles.pageCount(result.getOutput()), "summary + the one changed page");
        String report = TestFiles.text(result.getOutput());
        assertTrue(report.contains("Page 2: 1 word removed, 1 word added"), report);
    }

    @Test
    void anInsertedPageDoesNotMakeLaterPagesDifferent() throws Exception {
        String[] extra = {"Schedule of prices", "Item one costs one hundred rand"};
        Path a = pdf("a.pdf", -1, COVER, TERMS, END), b = pdf("b.pdf", -1, COVER, extra, TERMS, END);

        CompareTool.Comparison found = compare(a, b);
        assertEquals(List.of(new CompareTool.Pair(0, 0), new CompareTool.Pair(-1, 1), new CompareTool.Pair(1, 2),
                new CompareTool.Pair(2, 3)), found.pairs());
        assertTrue(found.removed().isEmpty());

        FileResult result = run(Map.of(), a, b).get(0);
        assertEquals(2, TestFiles.pageCount(result.getOutput()), "summary + the added page only");
        assertTrue(TestFiles.text(result.getOutput()).contains("New page 2: added"));
    }

    @Test
    void aRemovedPageIsListed() throws Exception {
        Path a = pdf("a.pdf", -1, COVER, TERMS, END), b = pdf("b.pdf", -1, COVER, END);

        CompareTool.Comparison found = compare(a, b);
        assertEquals(List.of(new CompareTool.Pair(0, 0), new CompareTool.Pair(1, -1), new CompareTool.Pair(2, 1)), found.pairs());
        FileResult result = run(Map.of(), a, b).get(0);
        assertTrue(result.getNote().contains("1 page removed"), result.getNote());
        assertTrue(TestFiles.text(result.getOutput()).contains("Original page 2: removed"));
    }

    @Test
    void aChangedPictureIsFoundOnlyWhenAskedFor() throws Exception {
        Path a = pdf("a.pdf", -1, COVER, TERMS), b = pdf("b.pdf", 1, COVER, TERMS);

        FileResult withPictures = run(Map.of(), a, b).get(0);
        assertTrue(withPictures.getNote().contains("Page 2 changed") && withPictures.getNote().contains("1 picture change"),
                withPictures.getNote());

        FileResult textOnly = run(Map.of(CompareTool.VISUAL, false), a, b).get(0);
        assertEquals("No differences found", textOnly.getNote());
    }

    @Test
    void eachFurtherFileGetsItsOwnReport() throws Exception {
        String[] newEnd = {"Either party may end this agreement", "with two months of written notice", "Thank you"};
        Path a = pdf("original.pdf", -1, COVER, END), b = pdf("draft2.pdf", -1, COVER, newEnd), c = pdf("draft3.pdf", -1, COVER, END);

        List<FileResult> results = run(Map.of(), a, b, c);

        assertEquals(2, results.size());
        assertEquals("draft2_compared.pdf", results.get(0).getOutput().getFileName().toString());
        assertTrue(results.get(0).getNote().contains("2 words removed, 2 added"), results.get(0).getNote());
        assertEquals("draft3_compared.pdf", results.get(1).getOutput().getFileName().toString());
        assertEquals("No differences found", results.get(1).getNote());
    }

    @Test
    void aLockedOriginalIsExplained() throws Exception {
        Path locked = TestFiles.lockedPdf(dir, "locked.pdf"), b = pdf("b.pdf", -1, COVER);

        FileResult result = run(Map.of(), locked, b).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().contains("needs a password"), result.getNote());
    }
}
