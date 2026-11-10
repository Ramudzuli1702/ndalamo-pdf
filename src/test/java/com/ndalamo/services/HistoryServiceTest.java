package com.ndalamo.services;

import com.ndalamo.models.HistoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** History and the recent files and tools on Home. */
class HistoryServiceTest {

    @TempDir
    Path dir;

    private static HistoryEntry run(long time, String toolId, String name, String... inputs) {
        return new HistoryEntry(time, toolId, name, List.of(inputs), List.of(inputs[0] + ".out.pdf"));
    }

    @Test
    void recentFilesAndToolsComeNewestFirstEachOnce() {
        HistoryService history = new HistoryService(dir);
        history.add(run(1, "merge", "Merge PDF", "C:\\a.pdf", "C:\\b.pdf"));
        history.add(run(2, "compress", "Compress PDF", "C:\\b.pdf"));
        history.add(run(3, "pipeline", "Client documents", "C:\\c.pdf"));
        history.add(run(4, "compress", "Compress PDF", "C:\\A.pdf"));     // the same file, written differently

        List<HistoryService.RecentFile> files = history.recentFiles(6);
        assertEquals(List.of("C:\\A.pdf", "C:\\c.pdf", "C:\\b.pdf"), files.stream().map(HistoryService.RecentFile::path).toList());
        assertEquals("Compress PDF", files.get(2).lastTool(), "b.pdf was last compressed, not merged");
        assertEquals(List.of("compress", "merge"), history.recentTools(6), "pipeline runs aren't tools");
        assertEquals(2, history.recentFiles(2).size());
    }

    @Test
    void theHistoryIsKeptBetweenRunsAndCanBeCleared() throws Exception {
        HistoryService history = new HistoryService(dir);
        history.add(run(1, "split", "Split PDF", "C:\\report.pdf"));
        assertTrue(Files.readString(dir.resolve("history.json")).contains("report.pdf"));

        HistoryService again = new HistoryService(dir);
        assertEquals(1, again.entries().size());
        assertEquals("Split PDF", again.entries().get(0).toolName());

        again.clear();
        assertTrue(new HistoryService(dir).entries().isEmpty());
        assertFalse(Files.readString(dir.resolve("history.json")).contains("report.pdf"));
    }

    @Test
    void onlyTheLatest300RunsAreKept() {
        HistoryService history = new HistoryService(dir);
        for (int i = 0; i < 310; i++) history.add(run(i, "rotate", "Rotate pages", "C:\\file" + i + ".pdf"));
        assertEquals(300, history.entries().size());
        assertEquals(309, history.entries().get(0).time());
    }
}
