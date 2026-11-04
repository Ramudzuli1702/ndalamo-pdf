package com.ndalamo.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SafeSaveTest {

    @TempDir
    Path dir;

    @Test
    void writesTheFileAndLeavesNoTempFile() throws IOException {
        Path target = dir.resolve("out.pdf");

        SafeSave.write(target, temp -> Files.writeString(temp, "new content"));

        assertEquals("new content", Files.readString(target));
        assertEquals(1, count(dir));
    }

    @Test
    void aFailedWriteLeavesNothingBehind() throws IOException {
        Path target = dir.resolve("out.pdf");

        assertThrows(IOException.class, () -> SafeSave.write(target, temp -> {
            Files.writeString(temp, "half a file");
            throw new IOException("disk full");
        }));

        assertFalse(Files.exists(target));
        assertEquals(0, count(dir));
    }

    @Test
    void aFailedWriteKeepsTheEarlierFileIntact() throws IOException {
        Path target = Files.writeString(dir.resolve("out.pdf"), "earlier result");

        assertThrows(IOException.class, () -> SafeSave.write(target, temp -> {
            throw new IOException("disk full");
        }));

        assertEquals("earlier result", Files.readString(target));
    }

    @Test
    void formatsSizesLikeTheMockups() {
        assertEquals("4.1 MB", Format.size(4_300_000));
        assertEquals("820 KB", Format.size(839_680));
        assertEquals("4.1 → 1.8 MB", Format.sizeChange(4_300_000, 1_887_437));
        assertEquals("56% smaller", Format.saving(100, 44));
        assertEquals("", Format.saving(100, 120));
    }

    private static long count(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }
}
