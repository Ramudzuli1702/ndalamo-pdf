package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Office to PDF. The conversion tests need LibreOffice, so they are skipped on a PC
 * without it. Point them at a copy that isn't installed with
 * {@code mvn verify -Dlibreoffice=C:\path\to\soffice.exe}.
 */
class OfficeToPdfToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Path libreOffice() {
        String given = System.getProperty("libreoffice");
        Path program = given != null && !given.isBlank() ? LibreOffice.programIn(Path.of(given)) : LibreOffice.find();
        assumeTrue(program != null, "LibreOffice isn't installed on this PC");
        return program;
    }

    private static OfficeToPdfTool tool() {
        Path program = libreOffice();
        return new OfficeToPdfTool(() -> program);
    }

    @Test
    void findsSofficeFromTheInstallFolderOrTheProgramFolder() throws IOException {
        Path install = dir.resolve("LibreOffice");
        Path program = Files.createDirectories(install.resolve("program")).resolve("soffice.exe");
        Files.writeString(program, "");

        assertEquals(program.toAbsolutePath(), LibreOffice.programIn(install));
        assertEquals(program.toAbsolutePath(), LibreOffice.programIn(install.resolve("program")));
        assertEquals(program.toAbsolutePath(), LibreOffice.programIn(program));
        assertNull(LibreOffice.programIn(dir), "a folder without LibreOffice");
        assertNull(LibreOffice.programIn(Files.writeString(dir.resolve("notepad.exe"), "")), "some other program");
    }

    @Test
    void withoutLibreOfficeItExplainsInsteadOfFailingEachFile() throws IOException {
        Path doc = TestFiles.docx(dir, "Report.docx", "Hello");

        ToolProblem problem = assertThrows(ToolProblem.class, () ->
                new OfficeToPdfTool(() -> null).run(List.of(doc), Map.of(), sameFolder, new Progress()));
        assertTrue(problem.getMessage().contains("needs LibreOffice"), problem.getMessage());
    }

    @Test
    void aWordFileBecomesAPdf() throws Exception {
        Path doc = TestFiles.docx(dir, "Report.docx", "Quarterly report for the shop", "Second page with the totals");
        byte[] before = TestFiles.bytes(doc);

        FileResult result = tool().run(List.of(doc), Map.of(), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertEquals(dir.resolve("Report_converted.pdf"), result.getOutput());
        assertEquals(2, TestFiles.pageCount(result.getOutput()));
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Quarterly report") && text.contains("totals"), "found: " + text);
        assertEquals("2 pages", result.getNote());
        assertArrayEquals(before, TestFiles.bytes(doc));
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count(), "only the original and the PDF; LibreOffice's work files are cleaned up");
        }
    }

    @Test
    void aSpreadsheetBecomesAPdf() throws Exception {
        Path sheet = Files.writeString(dir.resolve("Stock.csv"), "Item,Count\nPaper,12\nToner,3\n", StandardCharsets.UTF_8);

        FileResult result = tool().run(List.of(sheet), Map.of(), sameFolder, new Progress()).get(0);

        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Paper") && text.contains("Toner"), "found: " + text);
    }

    @Test
    void unusualFileNamesWork() throws Exception {
        Path doc = TestFiles.docx(dir, "Mbekanyamushumo – ṱhoho (final) #2.docx", "Ndi a livhuwa");

        FileResult result = tool().run(List.of(doc), Map.of(), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertEquals(dir.resolve("Mbekanyamushumo – ṱhoho (final) #2_converted.pdf"), result.getOutput());
    }

    @Test
    void aDamagedFileFailsButTheOthersAreConverted() throws Exception {
        Path broken = Files.write(dir.resolve("Broken.docx"), new byte[]{'P', 'K', 3, 4, 0, 0, 0});
        Path good = TestFiles.docx(dir, "Good.docx", "This one is fine");

        List<FileResult> results = tool().run(List.of(broken, good), Map.of(), sameFolder, new Progress());

        assertEquals(FileResult.Status.FAILED, results.get(0).getStatus());
        assertTrue(results.get(0).getNote().contains("couldn't convert"), results.get(0).getNote());
        assertEquals(FileResult.Status.SUCCESS, results.get(1).getStatus());
        assertFalse(Files.exists(dir.resolve("Broken_converted.pdf")));
    }

    @Test
    void cancellingWritesNothing() throws Exception {
        Path doc = TestFiles.docx(dir, "Report.docx", "Hello");
        Progress progress = new Progress();
        progress.cancel();

        assertTrue(tool().run(List.of(doc), Map.of(), sameFolder, progress).isEmpty());
        assertFalse(Files.exists(dir.resolve("Report_converted.pdf")));
    }

    @Test
    void cancellingPartwayStopsLibreOffice() throws Exception {
        Path doc = TestFiles.docx(dir, "Report.docx", "Hello");
        OfficeToPdfTool tool = tool();
        java.time.Instant testStarted = java.time.Instant.now();
        Progress progress = new Progress();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);                  // LibreOffice takes a few seconds to start
            } catch (InterruptedException ignored) {
            }
            progress.cancel();
        });
        canceller.start();

        List<FileResult> results = tool.run(List.of(doc), Map.of(), sameFolder, progress);

        assertTrue(results.isEmpty());
        assertFalse(Files.exists(dir.resolve("Report_converted.pdf")));
        // No LibreOffice started by this test may still be running.
        boolean stillRunning = ProcessHandle.allProcesses().anyMatch(p ->
                p.info().command().map(c -> c.toLowerCase().matches(".*soffice\\.(bin|exe)")).orElse(false)
                        && p.info().startInstant().map(started -> started.isAfter(testStarted)).orElse(false));
        assertFalse(stillRunning, "LibreOffice was left running");
    }
}
