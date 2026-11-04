package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RotateAndRemoveToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static List<Integer> rotations(Path pdf) throws IOException {
        List<Integer> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            doc.getPages().forEach(p -> result.add(p.getRotation()));
        }
        return result;
    }

    // ── Rotate ───────────────────────────────────────────────────────────

    @Test
    void rotatesAllPages() throws IOException {
        Path input = TestFiles.pdf(dir, "scan.pdf", "S", 3);

        FileResult result = new RotateTool().run(List.of(input),
                Map.of(RotateTool.TURN, RotateTool.RIGHT, RotateTool.WHICH, RotateTool.ALL), sameFolder, new Progress()).get(0);

        assertEquals(dir.resolve("scan_rotated.pdf"), result.getOutput());
        assertEquals(List.of(90, 90, 90), rotations(result.getOutput()));
        assertEquals(List.of(0, 0, 0), rotations(input));          // original untouched
    }

    @Test
    void rotatesOnlyThePagesListed() throws IOException {
        Path input = TestFiles.pdf(dir, "scan.pdf", "S", 4);

        FileResult result = new RotateTool().run(List.of(input),
                Map.of(RotateTool.TURN, RotateTool.LEFT, RotateTool.WHICH, RotateTool.SOME, RotateTool.PAGES, "2-3"),
                sameFolder, new Progress()).get(0);

        assertEquals(List.of(0, 270, 270, 0), rotations(result.getOutput()));
        assertEquals("2 pages turned", result.getNote());
    }

    @Test
    void rotatingAddsToAnEarlierTurn() throws IOException {
        Path input = TestFiles.pdf(dir, "scan.pdf", "S", 1);
        RotateTool rotate = new RotateTool();
        Map<String, Object> right = Map.of(RotateTool.TURN, RotateTool.RIGHT, RotateTool.WHICH, RotateTool.ALL);

        Path once = rotate.run(List.of(input), right, sameFolder, new Progress()).get(0).getOutput();
        Path twice = rotate.run(List.of(once), right, sameFolder, new Progress()).get(0).getOutput();

        assertEquals(List.of(180), rotations(twice));
    }

    // ── Remove pages ─────────────────────────────────────────────────────

    @Test
    void removesThePagesListed() throws IOException {
        Path input = TestFiles.pdf(dir, "report.pdf", "R", 6);
        byte[] before = TestFiles.bytes(input);

        FileResult result = new RemovePagesTool().run(List.of(input),
                Map.of(RemovePagesTool.PAGES, "2, 5-6"), sameFolder, new Progress()).get(0);

        assertEquals(dir.resolve("report_pages removed.pdf"), result.getOutput());
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("R page 1") && text.contains("R page 3") && text.contains("R page 4"));
        assertFalse(text.contains("R page 2") || text.contains("R page 5") || text.contains("R page 6"));
        assertEquals("3 pages removed · 3 pages left", result.getNote());
        assertArrayEquals(before, TestFiles.bytes(input));
    }

    @Test
    void refusesToRemoveEveryPage() throws IOException {
        Path input = TestFiles.pdf(dir, "report.pdf", "R", 3);

        FileResult result = new RemovePagesTool().run(List.of(input),
                Map.of(RemovePagesTool.PAGES, "1-3"), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertNull(result.getOutput());
    }

    @Test
    void oneBadFileDoesNotStopTheOthers() throws IOException {
        Path good = TestFiles.pdf(dir, "good.pdf", "G", 3);
        Path locked = TestFiles.lockedPdf(dir, "locked.pdf");

        List<FileResult> results = new RemovePagesTool().run(List.of(locked, good),
                Map.of(RemovePagesTool.PAGES, "1"), sameFolder, new Progress());

        assertEquals(FileResult.Status.FAILED, results.get(0).getStatus());
        assertTrue(results.get(0).getNote().contains("needs a password"));
        assertEquals(FileResult.Status.SUCCESS, results.get(1).getStatus());
    }
}
