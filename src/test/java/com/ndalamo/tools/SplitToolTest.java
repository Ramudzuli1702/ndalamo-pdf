package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplitToolTest {

    @TempDir
    Path dir;

    private final SplitTool split = new SplitTool();
    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private List<FileResult> run(Path input, Map<String, Object> options) throws IOException {
        return split.run(List.of(input), options, sameFolder, new Progress());
    }

    private List<Path> outputs(List<FileResult> results) {
        return results.stream().map(FileResult::getOutput).toList();
    }

    @Test
    void byRangesMakesOneFilePerRangeInAFolder() throws IOException {
        Path input = TestFiles.pdf(dir, "Assignment2.pdf", "A", 10);

        List<FileResult> results = run(input, Map.of(SplitTool.MODE, SplitTool.BY_RANGES, SplitTool.RANGES, "1-3, 5, 8-"));

        Path folder = dir.resolve("Assignment2_split");
        assertEquals(List.of(folder.resolve("Assignment2_pages 1-3.pdf"), folder.resolve("Assignment2_page 5.pdf"),
                folder.resolve("Assignment2_pages 8-10.pdf")), outputs(results));
        assertEquals(3, TestFiles.pageCount(results.get(0).getOutput()));
        assertTrue(TestFiles.text(results.get(1).getOutput()).contains("A page 5"));
        assertEquals(3, TestFiles.pageCount(results.get(2).getOutput()));
    }

    @Test
    void everyNPagesKeepsTheRemainderTogether() throws IOException {
        Path input = TestFiles.pdf(dir, "notes.pdf", "N", 7);

        List<FileResult> results = run(input, Map.of(SplitTool.MODE, SplitTool.EVERY_N, SplitTool.PAGES_PER_FILE, 3.0));

        assertEquals(3, results.size());
        assertEquals(1, TestFiles.pageCount(results.get(2).getOutput()));   // pages 7 on its own
    }

    @Test
    void everyPageMakesOneFilePerPage() throws IOException {
        Path input = TestFiles.pdf(dir, "notes.pdf", "N", 4);

        assertEquals(4, run(input, Map.of(SplitTool.MODE, SplitTool.EVERY_PAGE)).size());
    }

    @Test
    void bySizeKeepsEveryPartUnderTheLimit() throws IOException {
        Path input = TestFiles.pdf(dir, "big.pdf", "B", 12);
        long limit = Files.size(input) / 3;     // forces several parts

        List<FileResult> results = run(input, Map.of(SplitTool.MODE, SplitTool.BY_SIZE, SplitTool.MAX_MB, limit / (1024.0 * 1024)));

        assertTrue(results.size() > 1);
        int pages = 0;
        for (FileResult r : results) {
            assertTrue(Files.size(r.getOutput()) <= limit, r.getOutput() + " is over the limit");
            pages += TestFiles.pageCount(r.getOutput());
        }
        assertEquals(12, pages);
    }

    @Test
    void neverChangesTheOriginal() throws IOException {
        Path input = TestFiles.pdf(dir, "a.pdf", "A", 5);
        byte[] before = TestFiles.bytes(input);

        run(input, Map.of(SplitTool.MODE, SplitTool.EVERY_PAGE));

        assertArrayEquals(before, TestFiles.bytes(input));
    }

    @Test
    void aRangeOutsideTheFileFailsWithAPlainNote() throws IOException {
        Path input = TestFiles.pdf(dir, "short.pdf", "S", 5);

        FileResult result = run(input, Map.of(SplitTool.MODE, SplitTool.BY_RANGES, SplitTool.RANGES, "4-8")).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertEquals("This file has only 5 pages, so “4-8” doesn't fit. The original wasn't changed.", result.getNote());
        assertFalse(Files.exists(dir.resolve("short_split")));
    }

    @Test
    void checksOptionsBeforeRunning() {
        assertNotNull(split.checkOptions(Map.of(SplitTool.MODE, SplitTool.BY_RANGES, SplitTool.RANGES, "1-x")));
        assertNotNull(split.checkOptions(Map.of(SplitTool.MODE, SplitTool.EVERY_N, SplitTool.PAGES_PER_FILE, 2.5)));
        assertNull(split.checkOptions(Map.of(SplitTool.MODE, SplitTool.EVERY_PAGE)));
    }
}
