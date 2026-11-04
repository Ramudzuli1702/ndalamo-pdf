package com.ndalamo.utils;

import com.ndalamo.models.Settings.IfExists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OutputNamesTest {

    @TempDir
    Path dir;

    private Path original;

    @BeforeEach
    void createOriginal() throws IOException {
        original = Files.writeString(dir.resolve("Assignment2.pdf"), "original");
    }

    @Test
    void defaultPatternAddsToolName() {
        Path out = OutputNames.pick(original, dir, "{name}_{tool}", "compressed", "pdf", 0, IfExists.ADD_NUMBER);
        assertEquals(dir.resolve("Assignment2_compressed.pdf"), out);
    }

    @Test
    void fillsDateAndPages() {
        String name = OutputNames.fillPattern("{date} {name} ({pages} pages)", original, "merged", 42,
                LocalDate.of(2026, 11, 4));
        assertEquals("2026-11-04 Assignment2 (42 pages)", name);
    }

    @Test
    void acceptsExtensionWithOrWithoutDot() {
        Path out = OutputNames.pick(original, dir, "{name}_{tool}", "converted", ".docx", 0, IfExists.ADD_NUMBER);
        assertEquals(dir.resolve("Assignment2_converted.docx"), out);
    }

    @Test
    void replacesCharactersWindowsDoesNotAllow() {
        String name = OutputNames.fillPattern("{name}: {tool}?", original, "a/b", 0, LocalDate.now());
        assertEquals("Assignment2_ a_b_", name);
    }

    @Test
    void avoidsReservedAndEmptyNames() {
        assertEquals("con_", OutputNames.fillPattern("con", original, "x", 0, LocalDate.now()));
        assertEquals("output", OutputNames.fillPattern("...", original, "x", 0, LocalDate.now()));
    }

    @Test
    void addsNumberWhenNameIsTaken() throws IOException {
        Files.writeString(dir.resolve("Assignment2_compressed.pdf"), "earlier result");
        Files.writeString(dir.resolve("Assignment2_compressed (2).pdf"), "earlier result");

        Path out = OutputNames.pick(original, dir, "{name}_{tool}", "compressed", "pdf", 0, IfExists.ADD_NUMBER);

        assertEquals(dir.resolve("Assignment2_compressed (3).pdf"), out);
    }

    @Test
    void replaceReusesAnEarlierResult() throws IOException {
        Path earlier = Files.writeString(dir.resolve("Assignment2_compressed.pdf"), "earlier result");

        Path out = OutputNames.pick(original, dir, "{name}_{tool}", "compressed", "pdf", 0, IfExists.REPLACE);

        assertEquals(earlier, out);
    }

    @Test
    void neverPicksTheOriginalFile() throws IOException {
        // A pattern of just {name} in the same folder would point straight at the original.
        Path withReplace = OutputNames.pick(original, dir, "{name}", "compressed", "pdf", 0, IfExists.REPLACE);
        Path withNumber = OutputNames.pick(original, dir, "{name}", "compressed", "pdf", 0, IfExists.ADD_NUMBER);

        assertEquals(dir.resolve("Assignment2 (2).pdf"), withReplace);
        assertEquals(dir.resolve("Assignment2 (2).pdf"), withNumber);
        assertEquals("original", Files.readString(original));
    }

    @Test
    void askMustBeAnsweredByTheScreenFirst() {
        assertThrows(IllegalArgumentException.class, () ->
                OutputNames.pick(original, dir, "{name}_{tool}", "compressed", "pdf", 0, IfExists.ASK));
    }
}
