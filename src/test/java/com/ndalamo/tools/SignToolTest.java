package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.models.Signature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static BufferedImage picture(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int alpha(BufferedImage picture, int x, int y) {
        return picture.getRGB(x, y) >>> 24;
    }

    @Test
    void aTypedNameBecomesASeeThroughPicture() throws Exception {
        BufferedImage signature = picture(SignatureMaker.typed("Thandeka Dlamini", Color.BLACK));

        assertTrue(signature.getWidth() > signature.getHeight() * 3, "a name is wide");
        assertEquals(0, alpha(signature, 0, 0), "see-through around the ink");
        boolean ink = false;
        for (int x = 0; x < signature.getWidth() && !ink; x++) {
            for (int y = 0; y < signature.getHeight() && !ink; y++) ink = alpha(signature, x, y) > 200;
        }
        assertTrue(ink);
        assertNull(SignatureMaker.typed("   ", Color.BLACK), "no name, no signature");
    }

    @Test
    void namesWithTshivendaLettersStillWork() throws Exception {
        // Dancing Script lacks ṱ and ḓ; Noto Sans steps in rather than drawing empty boxes.
        assertNotNull(SignatureMaker.typed("Ṱhalitha Ḓivhani", Color.BLACK));
    }

    @Test
    void drawnStrokesBecomeASignature() throws Exception {
        List<List<double[]>> strokes = List.of(List.of(new double[]{20, 100}, new double[]{120, 40}, new double[]{220, 110}));
        BufferedImage signature = picture(SignatureMaker.drawn(strokes, 520, 170, Color.BLUE));

        assertTrue(signature.getWidth() < 520 * 4, "cropped close to the ink");
        assertNull(SignatureMaker.drawn(List.of(), 520, 170, Color.BLUE), "an empty pad gives nothing");
    }

    @Test
    void aPhotosPaperIsMadeSeeThrough() throws Exception {
        BufferedImage photo = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        var g = photo.createGraphics();
        g.setColor(new Color(245, 243, 238));                  // off-white paper
        g.fillRect(0, 0, 400, 200);
        g.setColor(new Color(20, 30, 90));                     // blue ink
        g.setStroke(new java.awt.BasicStroke(6));
        g.drawLine(60, 140, 340, 60);
        g.dispose();

        BufferedImage signature = picture(SignatureMaker.fromPhoto(photo));

        assertEquals(0, alpha(signature, 0, 0), "paper gone");
        assertEquals(255, alpha(signature, signature.getWidth() / 2, signature.getHeight() / 2), "ink kept");
        BufferedImage blank = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var b = blank.createGraphics();
        b.setColor(Color.WHITE);
        b.fillRect(0, 0, 100, 100);
        b.dispose();
        assertNull(SignatureMaker.fromPhoto(blank), "blank paper has no signature");
    }

    /** A solid black signature picture, 300 × 100. */
    private static byte[] blackBox() throws Exception {
        BufferedImage box = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        var g = box.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 300, 100);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(box, "png", out);
        return out.toByteArray();
    }

    private static Map<String, Object> options(Signature signature, boolean date) {
        Map<String, Object> options = new HashMap<>();
        options.put(SignTool.SIGNATURE, signature);
        options.put(SignTool.DATE, date);
        return options;
    }

    /** Is the page dark at this point (fractions of the page as shown)? */
    private static boolean darkAt(Path pdf, int page, double fx, double fy) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage shown = new PDFRenderer(doc).renderImageWithDPI(page - 1, 36);
            return (shown.getRGB((int) (fx * shown.getWidth()), (int) (fy * shown.getHeight())) & 0xFF) < 80;
        }
    }

    @Test
    void theSignatureGoesWhereItWasPlaced() throws Exception {
        Path pdf = TestFiles.pdf(dir, "Contract.pdf", "Contract", 2);
        byte[] before = TestFiles.bytes(pdf);
        // On page 2, from 60% across and 80% down, 30% of the page wide.
        Signature placed = new Signature(blackBox(), 2, 0.6, 0.8, 0.3);

        FileResult result = new SignTool().run(List.of(pdf), options(placed, false), sameFolder, new Progress()).get(0);

        assertEquals("Signed on page 2", result.getNote());
        assertTrue(darkAt(result.getOutput(), 2, 0.75, 0.82), "inside where it was placed");
        assertFalse(darkAt(result.getOutput(), 2, 0.3, 0.82), "not to the left of it");
        assertFalse(darkAt(result.getOutput(), 1, 0.75, 0.82), "not on page 1");
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void placementFollowsThePageAsShownEvenWhenTurned() throws Exception {
        Path pdf = dir.resolve("Turned.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(90);                                  // shown landscape
            doc.addPage(page);
            doc.save(pdf.toFile());
        }
        Signature placed = new Signature(blackBox(), 1, 0.7, 0.75, 0.25);

        Path output = new SignTool().run(List.of(pdf), options(placed, false), sameFolder, new Progress()).get(0).getOutput();

        assertTrue(darkAt(output, 1, 0.8, 0.78), "bottom right as people see the page");
        assertFalse(darkAt(output, 1, 0.2, 0.2));
    }

    @Test
    void theDateCanBeWrittenUnderIt() throws Exception {
        Path pdf = TestFiles.pdf(dir, "Contract.pdf", "Contract", 1);
        FileResult result = new SignTool().run(List.of(pdf), options(new Signature(blackBox(), 1, 0.1, 0.5, 0.2), true),
                sameFolder, new Progress()).get(0);

        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH));
        assertTrue(TestFiles.text(result.getOutput()).contains(today));
        assertTrue(result.getNote().contains("dated " + today));
    }

    @Test
    void aShorterFileIsSignedOnItsLastPage() throws Exception {
        Path pdf = TestFiles.pdf(dir, "Short.pdf", "Short", 1);
        FileResult result = new SignTool().run(List.of(pdf), options(new Signature(blackBox(), 3, 0.6, 0.8, 0.3), false),
                sameFolder, new Progress()).get(0);
        assertEquals("Signed on page 1 (its last page)", result.getNote());
    }

    @Test
    void aSignatureIsNeededFirst() throws Exception {
        assertNotNull(new SignTool().checkOptions(options(null, false)));
        assertNull(new SignTool().checkOptions(options(new Signature(blackBox(), 1, 0, 0, 0.2), false)));
    }
}
