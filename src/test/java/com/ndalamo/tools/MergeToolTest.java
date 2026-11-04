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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MergeToolTest {

    @TempDir
    Path dir;

    private final MergeTool merge = new MergeTool();
    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private List<FileResult> run(List<Path> inputs, Progress progress) throws IOException {
        return merge.run(inputs, Map.of(), sameFolder, progress);
    }

    @Test
    void joinsFilesInTheOrderGiven() throws IOException {
        Path a = TestFiles.pdf(dir, "Chapter1.pdf", "A", 2);
        Path b = TestFiles.pdf(dir, "Chapter2.pdf", "B", 3);

        FileResult result = run(List.of(b, a), new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertEquals(dir.resolve("Chapter2_merged.pdf"), result.getOutput());
        assertEquals(5, TestFiles.pageCount(result.getOutput()));
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.indexOf("B page 3") < text.indexOf("A page 1"), "B's pages should come first");
        assertEquals("5 pages", result.getNote());
    }

    @Test
    void neverChangesTheOriginals() throws IOException {
        Path a = TestFiles.pdf(dir, "a.pdf", "A", 1);
        Path b = TestFiles.pdf(dir, "b.pdf", "B", 1);
        byte[] aBefore = TestFiles.bytes(a);
        byte[] bBefore = TestFiles.bytes(b);

        run(List.of(a, b), new Progress());

        assertArrayEquals(aBefore, TestFiles.bytes(a));
        assertArrayEquals(bBefore, TestFiles.bytes(b));
    }

    @Test
    void addsImagesAsPages() throws IOException {
        Path a = TestFiles.pdf(dir, "report.pdf", "A", 2);
        Path photo = TestFiles.png(dir, "photo.png");

        FileResult result = run(List.of(a, photo), new Progress()).get(0);

        assertEquals(3, TestFiles.pageCount(result.getOutput()));
    }

    @Test
    void skipsAPasswordProtectedFileButMergesTheRest() throws IOException {
        Path a = TestFiles.pdf(dir, "a.pdf", "A", 1);
        Path locked = TestFiles.lockedPdf(dir, "bank.pdf");
        Path b = TestFiles.pdf(dir, "b.pdf", "B", 1);

        FileResult result = run(List.of(a, locked, b), new Progress()).get(0);

        assertEquals(FileResult.Status.PARTIAL, result.getStatus());
        assertEquals(2, TestFiles.pageCount(result.getOutput()));
        assertTrue(result.getNote().contains("bank.pdf (needs a password)"));
    }

    @Test
    void failsCleanlyWhenNothingCanBeRead() throws IOException {
        Path broken1 = Files.writeString(dir.resolve("broken1.pdf"), "not really a pdf");
        Path broken2 = Files.writeString(dir.resolve("broken2.pdf"), "also not a pdf");

        FileResult result = run(List.of(broken1, broken2), new Progress()).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertNull(result.getOutput());
        assertEquals(List.of(broken1, broken2), filesIn(dir));    // nothing new was written
    }

    @Test
    void cancellingLeavesNoFilesBehind() throws IOException {
        Path a = TestFiles.pdf(dir, "a.pdf", "A", 1);
        Path b = TestFiles.pdf(dir, "b.pdf", "B", 1);
        Progress progress = new Progress();
        progress.cancel();

        List<FileResult> results = run(List.of(a, b), progress);

        assertTrue(results.isEmpty());
        assertEquals(List.of(a, b), filesIn(dir));                // no output, no leftover temp file
    }

    @Test
    void addsANumberInsteadOfOverwritingAnEarlierMerge() throws IOException {
        Path a = TestFiles.pdf(dir, "a.pdf", "A", 1);
        Path b = TestFiles.pdf(dir, "b.pdf", "B", 1);
        Path earlier = run(List.of(a, b), new Progress()).get(0).getOutput();

        Path second = run(List.of(a, b), new Progress()).get(0).getOutput();

        assertEquals(dir.resolve("a_merged.pdf"), earlier);
        assertEquals(dir.resolve("a_merged (2).pdf"), second);
    }

    private static List<Path> filesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.sorted().toList();
        }
    }
}
