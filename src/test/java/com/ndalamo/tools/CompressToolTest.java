package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompressToolTest {

    @TempDir
    Path dir;

    private final CompressTool compress = new CompressTool();
    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);
    private Path scan;

    @BeforeEach
    void makeScan() throws IOException {
        scan = TestFiles.photoPdf(dir, "Scan_0031.pdf", 2, "Bank statement October");
    }

    private FileResult run(Path input, Map<String, Object> options) throws IOException {
        return compress.run(List.of(input), options, sameFolder, new Progress()).get(0);
    }

    private static Map<String, Object> mode(String mode) {
        Map<String, Object> options = new HashMap<>();
        options.put(CompressTool.MODE, mode);
        options.put(CompressTool.DOWNSCALE, true);
        options.put(CompressTool.GREYSCALE, false);
        options.put(CompressTool.REMOVE_METADATA, false);
        return options;
    }

    private static Map<String, Object> target(double mb) {
        Map<String, Object> options = mode(CompressTool.TARGET);
        options.put(CompressTool.TARGET_MB, mb);
        return options;
    }

    @Test
    void recommendedMakesAPhotoHeavyFileMuchSmaller() throws IOException {
        long before = Files.size(scan);

        FileResult result = run(scan, mode(CompressTool.RECOMMENDED));

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertEquals(dir.resolve("Scan_0031_compressed.pdf"), result.getOutput());
        assertTrue(result.getSizeAfter() < before / 4, "expected well under a quarter of " + before + ", got " + result.getSizeAfter());
        assertEquals(2, TestFiles.pageCount(result.getOutput()));
    }

    @Test
    void neverChangesTheOriginal() throws IOException {
        byte[] before = TestFiles.bytes(scan);

        run(scan, mode(CompressTool.STRONG));

        assertArrayEquals(before, TestFiles.bytes(scan));
    }

    @Test
    void strongIsSmallerThanRecommended() throws IOException {
        long recommended = run(scan, mode(CompressTool.RECOMMENDED)).getSizeAfter();
        long strong = run(scan, mode(CompressTool.STRONG)).getSizeAfter();

        assertTrue(strong < recommended);
    }

    @Test
    void targetSizeGetsUnderTheLimit() throws IOException {
        long recommended = run(scan, mode(CompressTool.RECOMMENDED)).getSizeAfter();
        double targetMb = recommended * 0.6 / (1024 * 1024);    // needs more than the first step

        FileResult result = run(scan, target(targetMb));

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.getSizeAfter() <= targetMb * 1024 * 1024);
        assertTrue(result.getNote().startsWith("Under "));
    }

    @Test
    void anImpossibleTargetGivesThePartialResultAndSaysWhy() throws IOException {
        FileResult result = run(scan, target(0.001));     // 1 KB: not possible for two photo pages

        assertEquals(FileResult.Status.PARTIAL, result.getStatus());
        assertTrue(Files.exists(result.getOutput()));
        assertTrue(result.getNote().startsWith("Reached "), result.getNote());
        assertTrue(result.getNote().contains("hard to read"));
    }

    @Test
    void aFileAlreadyUnderTheTargetIsCopiedUnchanged() throws IOException {
        FileResult result = run(scan, target(500));

        assertArrayEquals(TestFiles.bytes(scan), TestFiles.bytes(result.getOutput()));
        assertTrue(result.getNote().startsWith("Already under"));
    }

    @Test
    void greyscaleTurnsPicturesGrey() throws IOException {
        Map<String, Object> options = mode(CompressTool.RECOMMENDED);
        options.put(CompressTool.GREYSCALE, true);

        FileResult result = run(scan, options);

        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            COSName name = resources.getXObjectNames().iterator().next();
            PDImageXObject image = (PDImageXObject) resources.getXObject(name);
            assertEquals("DeviceGray", image.getColorSpace().getName());
        }
    }

    @Test
    void removeMetadataClearsTheTitle() throws IOException {
        Map<String, Object> options = mode(CompressTool.RECOMMENDED);
        options.put(CompressTool.REMOVE_METADATA, true);

        FileResult result = run(scan, options);

        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertNull(doc.getDocumentInformation().getTitle());
        }
    }

    @Test
    void textOnlyFilesStillComeOutFine() throws IOException {
        Path text = TestFiles.pdf(dir, "essay.pdf", "E", 3);

        FileResult result = run(text, mode(CompressTool.RECOMMENDED));

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.getSizeAfter() <= Files.size(text));
        assertEquals(3, TestFiles.pageCount(result.getOutput()));
    }

    @Test
    void cancellingLeavesNoFileBehind() throws IOException {
        Progress progress = new Progress();
        progress.cancel();

        List<FileResult> results = compress.run(List.of(scan), mode(CompressTool.RECOMMENDED), sameFolder, progress);

        assertTrue(results.isEmpty());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(scan), files.toList());
        }
    }

    @Test
    void compressToSizeStartsWithTargetSizeChosen() {
        CompressTool toSize = new CompressTool(true);
        assertEquals("compress-to-size", toSize.id());
        assertEquals(CompressTool.TARGET, toSize.optionFields().get(0).getDefaultValue());
    }
}
