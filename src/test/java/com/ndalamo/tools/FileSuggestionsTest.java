package com.ndalamo.tools;

import com.ndalamo.models.FileInfo;
import com.ndalamo.services.FileInspector;
import com.ndalamo.services.FileInspector.Look;
import com.ndalamo.services.FileInspector.Suggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which tools are suggested for dropped files (SPEC §4.2), and in what order. */
class FileSuggestionsTest {

    @TempDir
    Path dir;

    private static final long MB = 1024 * 1024;

    private static Look pdf(String name, long size, int pages, boolean scanned) {
        return new Look(Path.of(name), "pdf", new FileInfo(size, pages, false, false), scanned);
    }

    private static List<String> ids(List<Look> looks) {
        return FileInspector.suggest(looks).stream().map(Suggestion::toolId).toList();
    }

    @Test
    void aScanIsOfferedOcrFirst() {
        List<String> ids = ids(List.of(pdf("Scan_0031.pdf", 14 * MB, 8, true)));
        assertEquals("ocr", ids.get(0));
        assertEquals(List.of("ocr", "compress", "pdf-to-word", "workspace"), ids.subList(0, 4));
        assertTrue(FileInspector.suggest(List.of(pdf("Scan.pdf", 14 * MB, 8, true))).get(1).reason().contains("large"),
                "a large scan says why compressing helps");
    }

    @Test
    void aLargeTextPdfIsOfferedCompressFirst() {
        assertEquals("compress", ids(List.of(pdf("Report.pdf", 9 * MB, 40, false))).get(0));
    }

    @Test
    void anOrdinaryPdfIsOfferedWordFirst() {
        List<String> ids = ids(List.of(pdf("Letter.pdf", 80_000, 2, false)));
        assertEquals("pdf-to-word", ids.get(0));
        assertTrue(ids.contains("workspace") && ids.contains("split"));
        assertTrue(ids.size() <= 5);
    }

    @Test
    void severalPdfsAreOfferedMergeFirstAndTwoCanBeCompared() {
        List<String> two = ids(List.of(pdf("a.pdf", 1000, 1, false), pdf("b.pdf", 1000, 1, false)));
        assertEquals("merge", two.get(0));
        assertEquals("compare", two.get(1));
        List<String> three = ids(List.of(pdf("a.pdf", 1000, 1, false), pdf("b.pdf", 1000, 1, false), pdf("c.pdf", 1000, 1, false)));
        assertEquals("merge", three.get(0));
        assertFalse(three.contains("compare"));
    }

    @Test
    void lockedAndDamagedFilesComeFirst() {
        Look locked = new Look(Path.of("x.pdf"), "pdf", new FileInfo(1000, 0, true, false), false);
        assertEquals(List.of("unlock", "protect"), ids(List.of(locked)));
        Look damaged = new Look(Path.of("y.pdf"), "pdf", new FileInfo(1000, 0, false, true), false);
        assertEquals("repair", ids(List.of(damaged)).get(0));
        Look restricted = new Look(Path.of("z.pdf"), "pdf", new FileInfo(1000, 1, false, false, true), false);
        assertTrue(ids(List.of(restricted)).contains("unlock"));
    }

    @Test
    void picturesOfficeAndMarkdownAreTurnedIntoPdf() {
        Look photo = new Look(Path.of("p.jpg"), "image", new FileInfo(1000, 1, false, false), false);
        assertEquals(List.of("image-to-pdf"), ids(List.of(photo, photo)));
        Look doc = new Look(Path.of("d.docx"), "office", new FileInfo(1000, 0, false, false), false);
        assertEquals(List.of("office-to-pdf"), ids(List.of(doc)));
        Look md = new Look(Path.of("n.md"), "markdown", new FileInfo(100, 0, false, false), false);
        assertEquals(List.of("markdown-to-pdf"), ids(List.of(md)));
        Look other = new Look(Path.of("song.mp3"), "other", new FileInfo(100, 0, false, false), false);
        assertTrue(ids(List.of(other)).isEmpty());
        // A PDF with pictures: the PDF comes first, the pictures are still offered.
        List<String> mixed = ids(List.of(pdf("a.pdf", 1000, 1, false), photo));
        assertEquals("pdf-to-word", mixed.get(0));
        assertTrue(mixed.contains("image-to-pdf"));
    }

    @Test
    void everySuggestedToolExists() {
        List<List<Look>> cases = List.of(List.of(pdf("s.pdf", 14 * MB, 8, true)), List.of(pdf("l.pdf", 1000, 3, false)),
                List.of(pdf("a.pdf", 1000, 1, false), pdf("b.pdf", 1000, 1, false)),
                List.of(new Look(Path.of("x.pdf"), "pdf", new FileInfo(1000, 0, true, false), false)));
        for (List<Look> looks : cases) {
            for (String id : ids(looks)) assertTrue(Tools.find(id).isPresent(), id);
        }
    }

    @Test
    void realFilesAreLookedAtCorrectly() throws Exception {
        Path scan = TestFiles.scannedPdf(dir, "Scan.pdf", "Dear customer", "Your account is overdue");
        Path text = TestFiles.pdf(dir, "Text.pdf", "Report", 3);
        Path locked = TestFiles.lockedPdf(dir, "Locked.pdf");
        byte[] before = TestFiles.bytes(scan);
        FileInspector inspector = new FileInspector();

        Look scanLook = inspector.look(scan);
        assertTrue(scanLook.scanned());
        assertEquals("1 page · " + com.ndalamo.utils.Format.size(scanLook.info().getSize()) + " · scanned pages, no selectable text",
                FileInspector.describe(List.of(scanLook)));
        assertFalse(inspector.look(text).scanned());
        Look lockedLook = inspector.look(locked);
        assertTrue(lockedLook.info().isPasswordProtected() && !lockedLook.scanned());
        assertTrue(FileInspector.describe(List.of(lockedLook)).contains("locked with a password"));
        assertEquals("image", inspector.look(TestFiles.png(dir, "p.png")).kind());
        assertArrayEquals(before, TestFiles.bytes(scan));
    }
}
