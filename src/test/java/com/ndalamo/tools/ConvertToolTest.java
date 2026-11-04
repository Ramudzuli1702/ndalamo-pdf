package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.PageRef;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.utils.ExifOrientation;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Image to PDF and PDF to JPG (chunk 7). */
class ConvertToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static PDRectangle pageSize(Path pdf, int index) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getPage(index).getMediaBox();
        }
    }

    private static Map<String, Object> imageOptions(String size, String margin) {
        Map<String, Object> o = new HashMap<>();
        o.put(ImageToPdfTool.SIZE, size);
        o.put(ImageToPdfTool.MARGIN, margin);
        o.put(ImageToPdfTool.SHRINK, true);
        return o;
    }

    // ── Image to PDF ─────────────────────────────────────────────────────

    @Test
    void picturesBecomeOnePdfWithAPageEach() throws IOException {
        Path wide = TestFiles.png(dir, "whiteboard.png");                   // 300×200
        Path photo = TestFiles.jpegWithOrientation(dir, "receipt.jpg", 1);   // 300×200, no turning
        byte[] before = TestFiles.bytes(photo);

        FileResult result = new ImageToPdfTool().run(List.of(wide, photo),
                imageOptions(ImageToPdfTool.A4, ImageToPdfTool.SMALL_MARGIN), sameFolder, new Progress()).get(0);

        assertEquals(dir.resolve("whiteboard_converted.pdf"), result.getOutput());
        assertEquals(2, TestFiles.pageCount(result.getOutput()));
        PDRectangle page = pageSize(result.getOutput(), 0);
        assertEquals(PDRectangle.A4.getHeight(), page.getWidth(), 0.5);     // wide picture → landscape A4
        assertArrayEquals(before, TestFiles.bytes(photo));
    }

    @Test
    void phonePhotosComeOutTheRightWayUp() throws IOException {
        // Stored 300 wide × 200 high, with a note saying "turn 90° clockwise": really a portrait photo.
        Path upright = TestFiles.jpegWithOrientation(dir, "IMG_2041.jpg", 6);
        assertEquals(6, ExifOrientation.read(upright));

        FileResult result = new ImageToPdfTool().run(List.of(upright),
                imageOptions(ImageToPdfTool.A4, ImageToPdfTool.NO_MARGIN), sameFolder, new Progress()).get(0);

        PDRectangle page = pageSize(result.getOutput(), 0);
        assertTrue(page.getHeight() > page.getWidth(), "the turned photo should give a portrait page");
    }

    @Test
    void turningMovesTheLeftOfThePhotoToTheTop() throws IOException {
        BufferedImage stored = ImageIO.read(TestFiles.jpegWithOrientation(dir, "p.jpg", 6).toFile());

        BufferedImage turned = ExifOrientation.apply(stored, 6);

        assertEquals(200, turned.getWidth());
        assertEquals(300, turned.getHeight());
        // Orange was on the left; a quarter turn clockwise puts it at the top.
        int top = turned.getRGB(100, 20), bottom = turned.getRGB(100, 280);
        assertTrue(((top >> 16) & 0xFF) > 200 && (top & 0xFF) < 100, "top should be orange");
        assertTrue((bottom & 0xFF) > 150, "bottom should be blue");
    }

    @Test
    void sameShapeAsThePictureHasNoWhiteSpace() throws IOException {
        Path wide = TestFiles.png(dir, "banner.png");     // 3:2

        FileResult result = new ImageToPdfTool().run(List.of(wide),
                imageOptions(ImageToPdfTool.SAME, ImageToPdfTool.NO_MARGIN), sameFolder, new Progress()).get(0);

        PDRectangle page = pageSize(result.getOutput(), 0);
        assertEquals(1.5, page.getWidth() / page.getHeight(), 0.01);
    }

    @Test
    void everyPageOfATiffScanBecomesAPage() throws IOException {
        Path tiff = TestFiles.multiPageTiff(dir, "fax.tif", 3);

        FileResult result = new ImageToPdfTool().run(List.of(tiff),
                imageOptions(ImageToPdfTool.LETTER, ImageToPdfTool.SMALL_MARGIN), sameFolder, new Progress()).get(0);

        assertEquals(3, TestFiles.pageCount(result.getOutput()));
        assertEquals(PDRectangle.LETTER.getWidth(), pageSize(result.getOutput(), 0).getWidth(), 0.5);
    }

    @Test
    void followsADraggedPageOrder() throws IOException {
        Path first = TestFiles.multiPageTiff(dir, "a.tif", 2);
        Path second = TestFiles.png(dir, "b.png");
        Map<String, Object> options = imageOptions(ImageToPdfTool.A4, ImageToPdfTool.NO_MARGIN);
        options.put(PdfTool.PAGE_ORDER, List.of(new PageRef(second, 1), new PageRef(first, 2), new PageRef(first, 1)));

        FileResult result = new ImageToPdfTool().run(List.of(first, second), options, sameFolder, new Progress()).get(0);

        assertEquals(3, TestFiles.pageCount(result.getOutput()));
        assertTrue(pageSize(result.getOutput(), 0).getWidth() > pageSize(result.getOutput(), 0).getHeight(),
                "the wide PNG should now be first");
    }

    @Test
    void mergeAlsoTurnsPhonePhotosAndTakesTiffs() throws IOException {
        Path pdf = TestFiles.pdf(dir, "cover.pdf", "C", 1);
        Path photo = TestFiles.jpegWithOrientation(dir, "photo.jpg", 6);
        Path tiff = TestFiles.multiPageTiff(dir, "scan.tif", 2);

        FileResult result = new MergeTool().run(List.of(pdf, photo, tiff), Map.of(), sameFolder, new Progress()).get(0);

        assertEquals(4, TestFiles.pageCount(result.getOutput()));
        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
    }

    // ── PDF to JPG ───────────────────────────────────────────────────────

    private static Map<String, Object> jpgOptions(String what, String quality, String pages, boolean zip) {
        Map<String, Object> o = new HashMap<>();
        o.put(PdfToJpgTool.WHAT, what);
        o.put(PdfToJpgTool.QUALITY, quality);
        o.put(PdfToJpgTool.PAGES, pages);
        o.put(PdfToJpgTool.ZIP, zip);
        return o;
    }

    @Test
    void eachPageBecomesAPictureInAFolder() throws IOException {
        Path pdf = TestFiles.pdf(dir, "Report.pdf", "R", 3);      // Letter pages, 612×792 points
        byte[] before = TestFiles.bytes(pdf);

        List<FileResult> results = new PdfToJpgTool().run(List.of(pdf),
                jpgOptions(PdfToJpgTool.PAGES_AS_PICTURES, PdfToJpgTool.SCREEN, "", false), sameFolder, new Progress());

        Path folder = dir.resolve("Report_jpg");
        assertEquals(List.of(folder.resolve("Report_page 1.jpg"), folder.resolve("Report_page 2.jpg"),
                folder.resolve("Report_page 3.jpg")), results.stream().map(FileResult::getOutput).toList());
        BufferedImage page = ImageIO.read(results.get(0).getOutput().toFile());
        assertEquals(612, page.getWidth());                      // 72 dpi: one pixel per point
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void higherSharpnessMeansMorePixels() throws IOException {
        Path pdf = TestFiles.pdf(dir, "Report.pdf", "R", 1);

        Path picture = new PdfToJpgTool().run(List.of(pdf),
                jpgOptions(PdfToJpgTool.PAGES_AS_PICTURES, PdfToJpgTool.GOOD, "", false), sameFolder, new Progress())
                .get(0).getOutput();

        assertEquals(1275, ImageIO.read(picture.toFile()).getWidth());   // 8.5 in × 150 dpi
    }

    @Test
    void onlyTheChosenPages() throws IOException {
        Path pdf = TestFiles.pdf(dir, "Report.pdf", "R", 5);

        List<FileResult> results = new PdfToJpgTool().run(List.of(pdf),
                jpgOptions(PdfToJpgTool.PAGES_AS_PICTURES, PdfToJpgTool.SCREEN, "2, 4", false), sameFolder, new Progress());

        assertEquals(List.of("Report_page 2.jpg", "Report_page 4.jpg"),
                results.stream().map(r -> r.getOutput().getFileName().toString()).toList());
    }

    @Test
    void picturesInsideThePdfComeOut() throws IOException {
        Path scan = TestFiles.photoPdf(dir, "Scan.pdf", 2, null);

        List<FileResult> results = new PdfToJpgTool().run(List.of(scan),
                jpgOptions(PdfToJpgTool.PICTURES_INSIDE, PdfToJpgTool.GOOD, "", false), sameFolder, new Progress());

        assertEquals(2, results.size());
        assertEquals(1100, ImageIO.read(results.get(0).getOutput().toFile()).getWidth());   // the original pixels
    }

    @Test
    void aPdfWithoutPicturesSaysSo() throws IOException {
        Path text = TestFiles.pdf(dir, "Essay.pdf", "E", 2);

        FileResult result = new PdfToJpgTool().run(List.of(text),
                jpgOptions(PdfToJpgTool.PICTURES_INSIDE, PdfToJpgTool.GOOD, "", false), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertTrue(result.getNote().startsWith("There are no pictures inside this PDF"));
    }

    @Test
    void zipHoldsAllThePictures() throws IOException {
        Path pdf = TestFiles.pdf(dir, "Report.pdf", "R", 3);

        FileResult result = new PdfToJpgTool().run(List.of(pdf),
                jpgOptions(PdfToJpgTool.PAGES_AS_PICTURES, PdfToJpgTool.SCREEN, "", true), sameFolder, new Progress()).get(0);

        assertEquals(dir.resolve("Report_jpg.zip"), result.getOutput());
        try (ZipFile zip = new ZipFile(result.getOutput().toFile())) {
            assertEquals(3, zip.size());
            assertNotNull(zip.getEntry("Report_page 2.jpg"));
        }
    }

    @Test
    void cancellingLeavesNoPicturesBehind() throws IOException {
        Path pdf = TestFiles.pdf(dir, "Report.pdf", "R", 3);
        Progress progress = new Progress();
        progress.cancel();

        List<FileResult> results = new PdfToJpgTool().run(List.of(pdf),
                jpgOptions(PdfToJpgTool.PAGES_AS_PICTURES, PdfToJpgTool.SCREEN, "", true), sameFolder, progress);

        assertTrue(results.isEmpty());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(pdf), files.toList());
        }
    }
}
