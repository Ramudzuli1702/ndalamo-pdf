package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedResultsTest {

    @TempDir Path dir;

    private Path input(String name, String content) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("docs"));
        return Files.writeString(folder.resolve(name), content);
    }

    /** What a tool does: asks the staging SaveTo for a name and writes there. */
    private FileResult stage(Path input, SaveTo staging, String content) throws Exception {
        Path out = staging.outputFor(input, "compressed", "pdf", 0);
        Files.createDirectories(out.getParent());
        Files.writeString(out, content);
        return new FileResult(input, out, 100, content.length(), FileResult.Status.SUCCESS, "");
    }

    @Test
    void saveMovesResultsNextToTheOriginalAndClearsTheStagingFolder() throws Exception {
        Path in = input("Report.pdf", "original");
        SaveTo target = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);
        Path stage = Files.createDirectories(dir.resolve("stage"));
        FileResult staged = stage(in, StagedResults.staging(target, stage), "small");

        assertFalse(Files.exists(in.resolveSibling("Report_compressed.pdf")), "nothing saved before Save");
        List<FileResult> saved = StagedResults.save(List.of(staged), stage, target, List.of(in));

        assertEquals(in.resolveSibling("Report_compressed.pdf"), saved.get(0).getOutput());
        assertEquals("small", Files.readString(saved.get(0).getOutput()));
        assertEquals("original", Files.readString(in));
        assertFalse(Files.exists(stage));
    }

    @Test
    void aTakenNameGetsANumberOrIsReplacedAsSettingsSay() throws Exception {
        Path in = input("Report.pdf", "original");
        Files.writeString(in.resolveSibling("Report_compressed.pdf"), "older");

        SaveTo numbered = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);
        Path stage1 = Files.createDirectories(dir.resolve("s1"));
        var first = StagedResults.save(List.of(stage(in, StagedResults.staging(numbered, stage1), "new")),
                stage1, numbered, List.of(in));
        assertEquals("Report_compressed (2).pdf", first.get(0).getOutput().getFileName().toString());
        assertEquals("older", Files.readString(in.resolveSibling("Report_compressed.pdf")));

        SaveTo replace = new SaveTo(null, "{name}_{tool}", Settings.IfExists.REPLACE);
        Path stage2 = Files.createDirectories(dir.resolve("s2"));
        var second = StagedResults.save(List.of(stage(in, StagedResults.staging(replace, stage2), "newest")),
                stage2, replace, List.of(in));
        assertEquals("Report_compressed.pdf", second.get(0).getOutput().getFileName().toString());
        assertEquals("newest", Files.readString(second.get(0).getOutput()));
    }

    @Test
    void neverReplacesTheOriginalEvenWhenTheNameMatches() throws Exception {
        Path in = input("Report.pdf", "original");
        byte[] before = Files.readAllBytes(in);
        SaveTo sameName = new SaveTo(null, "{name}", Settings.IfExists.REPLACE);
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Path out = stage.resolve("Report.pdf");
        Files.writeString(out, "result");

        var saved = StagedResults.save(List.of(new FileResult(in, out, 8, 6, FileResult.Status.SUCCESS, "")),
                stage, sameName, List.of(in));

        assertEquals("Report (2).pdf", saved.get(0).getOutput().getFileName().toString());
        assertArrayEquals(before, Files.readAllBytes(in));
    }

    @Test
    void subfoldersMadeByTheToolAreKept() throws Exception {
        Path in = input("Big.pdf", "original");
        SaveTo target = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);
        Path stage = Files.createDirectories(dir.resolve("stage"));
        // Split writes its parts into "<name>_split" inside the folder it's given.
        SaveTo parts = StagedResults.staging(target, stage).inFolder(stage.resolve("Big_split"));
        Path part = parts.outputFor(in, "part1", "pdf", 0);
        Files.createDirectories(part.getParent());
        Files.writeString(part, "p1");

        var saved = StagedResults.save(List.of(new FileResult(in, part, 8, 2, FileResult.Status.SUCCESS, "")),
                stage, target, List.of(in));

        assertEquals(in.resolveSibling("Big_split").resolve("Big_part1.pdf"), saved.get(0).getOutput());
    }

    @Test
    void failedFilesPassThroughAndDiscardDeletesEverything() throws Exception {
        Path in = input("Broken.pdf", "x");
        Path stage = Files.createDirectories(dir.resolve("stage"));
        Files.writeString(stage.resolve("leftover.pdf"), "y");
        FileResult failed = FileResult.failed(in, 1, "Damaged");

        StagedResults.discard(stage);
        assertFalse(Files.exists(stage));
        assertTrue(Files.exists(in));
        assertEquals(null, failed.getOutput());
    }
}
