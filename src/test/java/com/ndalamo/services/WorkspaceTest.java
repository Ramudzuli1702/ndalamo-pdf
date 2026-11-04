package com.ndalamo.services;

import com.ndalamo.models.WorkspaceDocument;
import com.ndalamo.models.WorkspaceDocument.Change;
import com.ndalamo.models.WorkspacePage;
import com.ndalamo.models.WorkspacePage.Crop;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The page workspace: editing (WorkspaceDocument), crops, and saving (WorkspaceService). */
class WorkspaceTest {

    @TempDir
    Path dir;

    private final WorkspaceService service = new WorkspaceService();

    /** A PDF whose pages say "<label>1", "<label>2"… so the saved order can be checked. */
    private Path pdf(String name, String label, int pages) throws IOException {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream text = new PDPageContentStream(doc, page)) {
                    text.beginText();
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
                    text.newLineAtOffset(72, 700);
                    text.showText(label + i);
                    text.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static String pageText(Path pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc).replaceAll("\\s", "");     // a turned page reads letter by letter
        }
    }

    private WorkspaceDocument open(Path file) throws IOException {
        return new WorkspaceDocument(file, file.getFileName().toString(), service.open(file));
    }

    private static Set<Long> ids(WorkspaceDocument doc, int... positions) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (int p : positions) ids.add(doc.getPages().get(p - 1).id());
        return ids;
    }

    // ── Editing ──────────────────────────────────────────────────────────

    @Test
    void movesPagesAndUndoesIt() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 5));

        doc.move(ids(doc, 4, 5), doc.getPages().get(0).id());     // pages 4–5 to the front

        assertEquals(List.of(4, 5, 1, 2, 3), doc.getPages().stream().map(WorkspacePage::sourcePage).toList());
        assertTrue(doc.isChanged());
        assertTrue(doc.undo());
        assertEquals(List.of(1, 2, 3, 4, 5), doc.getPages().stream().map(WorkspacePage::sourcePage).toList());
        assertFalse(doc.isChanged());
    }

    @Test
    void droppingPagesWhereTheyAlreadyAreIsNotAChange() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 3));

        doc.move(ids(doc, 2), doc.getPages().get(2).id());       // page 2 in front of page 3: no move

        assertFalse(doc.isChanged());
    }

    @Test
    void describesTheChangesInPlainWords() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 6));

        doc.delete(ids(doc, 2, 3));
        doc.rotate(ids(doc, 1), 90);

        assertEquals("You deleted 2 pages and rotated 1.", doc.changeSummary());
    }

    @Test
    void splitsAfterAPageAndMovesBetweenFiles() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 5));

        List<WorkspacePage> after = doc.splitAfter(doc.getPages().get(1).id());
        WorkspaceDocument part = new WorkspaceDocument(null, "a (part 2)", List.of());
        part.add(after, -1, Change.SPLIT_OFF);

        assertEquals(2, doc.size());
        assertEquals(List.of(3, 4, 5), part.getPages().stream().map(WorkspacePage::sourcePage).toList());

        List<WorkspacePage> moved = part.takeOut(ids(part, 1), Change.MOVED);
        doc.add(moved, -1, Change.MOVED);
        assertEquals(List.of(1, 2, 3), doc.getPages().stream().map(WorkspacePage::sourcePage).toList());
    }

    @Test
    void turningKeepsThePageSelectedAndAddsUp() {
        WorkspacePage page = WorkspacePage.of(Path.of("x.pdf"), 1, 90);

        WorkspacePage turned = page.turned(90).turned(90).turned(-90);

        assertEquals(page.id(), turned.id());
        assertEquals(90, turned.extraRotation());
        assertEquals(180, turned.shownRotation());
    }

    @Test
    void cropDrawnOnATurnedPageMeansTheSamePartAfterwards() {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            Crop crop = Crop.fromShown(0.1, 0.2, 0.6, 0.9, rotation);
            double[] back = crop.toShown(rotation);
            assertArrayEquals(new double[] {0.1, 0.2, 0.6, 0.9}, back, 1e-9, "rotation " + rotation);
        }
        // On an unturned page, "top 20% removed" means the stored page keeps up to 80% of its height.
        Crop plain = Crop.fromShown(0, 0.2, 1, 1, 0);
        assertEquals(0.8, plain.top(), 1e-9);
        assertEquals(0, plain.bottom(), 1e-9);
    }

    // ── Saving ───────────────────────────────────────────────────────────

    @Test
    void savesTheEditedPagesAsANewFileAndLeavesTheOriginal() throws IOException {
        Path original = pdf("Report.pdf", "R", 4);
        byte[] before = Files.readAllBytes(original);
        WorkspaceDocument doc = open(original);
        doc.delete(ids(doc, 2));
        doc.move(ids(doc, 3), doc.getPages().get(0).id());       // page 4 to the front
        doc.rotate(ids(doc, 1), 90);

        Path saved = dir.resolve("Report_edited.pdf");
        service.save(doc.getPages(), saved);

        assertEquals("R4", pageText(saved, 1));
        assertEquals("R1", pageText(saved, 2));
        assertEquals("R3", pageText(saved, 3));
        try (PDDocument out = Loader.loadPDF(saved.toFile())) {
            assertEquals(3, out.getNumberOfPages());
            assertEquals(90, out.getPage(0).getRotation());
            assertEquals(0, out.getPage(1).getRotation());
        }
        assertArrayEquals(before, Files.readAllBytes(original));
    }

    @Test
    void combinesPagesFromTwoFilesWithABlankPage() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 2));
        doc.add(service.open(pdf("b.pdf", "B", 1)), -1, Change.ADDED);
        doc.insertBlank(1);

        Path saved = dir.resolve("combined.pdf");
        service.save(doc.getPages(), saved);

        assertEquals("A1", pageText(saved, 1));
        assertEquals("", pageText(saved, 2));                     // the blank page
        assertEquals("B1", pageText(saved, 4));
        try (PDDocument out = Loader.loadPDF(saved.toFile())) {
            assertEquals(PDRectangle.A4.getWidth(), out.getPage(1).getMediaBox().getWidth(), 0.5);   // same size as the page before
        }
    }

    @Test
    void theSamePageTwiceCanBeTurnedSeparately() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 1));
        WorkspacePage again = doc.getPages().get(0).copy();
        doc.add(List.of(again), -1, Change.ADDED);
        doc.rotate(Set.of(again.id()), 180);

        Path saved = dir.resolve("twice.pdf");
        service.save(doc.getPages(), saved);

        try (PDDocument out = Loader.loadPDF(saved.toFile())) {
            assertEquals(0, out.getPage(0).getRotation());
            assertEquals(180, out.getPage(1).getRotation());
        }
    }

    @Test
    void cropIsSavedAsTheVisiblePartOfThePage() throws IOException {
        WorkspaceDocument doc = open(pdf("a.pdf", "A", 1));
        doc.crop(ids(doc, 1), Crop.fromShown(0.1, 0.1, 0.9, 0.5, 0));     // keep the top half, trimmed at the sides

        Path saved = dir.resolve("cropped.pdf");
        service.save(doc.getPages(), saved);

        try (PDDocument out = Loader.loadPDF(saved.toFile())) {
            PDRectangle crop = out.getPage(0).getCropBox();
            assertEquals(PDRectangle.A4.getWidth() * 0.8, crop.getWidth(), 0.5);
            assertEquals(PDRectangle.A4.getHeight() * 0.4, crop.getHeight(), 0.5);
            assertEquals(PDRectangle.A4.getHeight() * 0.5, crop.getLowerLeftY(), 0.5);
        }
    }
}
