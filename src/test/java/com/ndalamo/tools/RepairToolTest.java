package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepairToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** A healthy 3-page PDF saved the classic way (plain objects and an xref table), like most older PDFs. */
    private Path healthy(String name) throws Exception {
        Path made = TestFiles.pdf(dir, "made-" + name, "Report", 3);
        Path file = dir.resolve(name);
        try (PDDocument doc = Loader.loadPDF(made.toFile())) {
            doc.save(file.toFile(), CompressParameters.NO_COMPRESSION);
        }
        Files.delete(made);
        return file;
    }

    private FileResult repair(Path file) throws Exception {
        return new RepairTool().run(List.of(file), Map.of(), sameFolder, new Progress()).get(0);
    }

    @Test
    void aWrongTableOfContentsIsRebuilt() throws Exception {
        Path file = healthy("Broken.pdf");
        String pdf = Files.readString(file, StandardCharsets.ISO_8859_1);
        // Point "startxref" somewhere wrong, as a badly written or edited file does.
        String damaged = pdf.replaceFirst("startxref\\s+\\d+", "startxref\n123");
        Files.writeString(file, damaged, StandardCharsets.ISO_8859_1);
        byte[] before = TestFiles.bytes(file);

        FileResult result = repair(file);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertTrue(result.getNote().contains("table of contents pointed to the wrong place"), result.getNote());
        assertTrue(result.getNote().contains("3 pages recovered"), result.getNote());
        assertEquals(3, TestFiles.pageCount(result.getOutput()));
        assertTrue(RepairTool.check(result.getOutput()).isEmpty(), "the repaired copy is healthy");
        assertArrayEquals(before, TestFiles.bytes(file));
    }

    @Test
    void aFileCutOffAtTheEndIsRecovered() throws Exception {
        Path file = healthy("Download.pdf");
        String pdf = Files.readString(file, StandardCharsets.ISO_8859_1);
        // Cut it off just before its table of contents, as an interrupted download leaves it.
        Files.writeString(file, pdf.substring(0, pdf.lastIndexOf("xref")), StandardCharsets.ISO_8859_1);

        FileResult result = repair(file);

        assertTrue(result.getNote().contains("cut off before its end"), result.getNote());
        assertEquals(3, TestFiles.pageCount(result.getOutput()));
        assertTrue(TestFiles.text(result.getOutput()).contains("Report page 3"), "the text is still there");
    }

    @Test
    void aHealthyFileIsSavedAsACleanCopy() throws Exception {
        FileResult result = repair(healthy("Fine.pdf"));

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.getNote().startsWith("No damage found"), result.getNote());
    }

    @Test
    void somethingThatIsntAPdfFailsPlainly() throws Exception {
        Path file = Files.writeString(dir.resolve("Holiday.pdf"), "This is a text file someone renamed to .pdf");

        FileResult result = repair(file);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().contains("isn't a PDF"), result.getNote());
    }
}
