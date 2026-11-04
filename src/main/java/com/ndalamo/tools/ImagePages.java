package com.ndalamo.tools;

import com.ndalamo.services.FileInspector;
import com.ndalamo.utils.ExifOrientation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Turns picture files into PDF pages — used by Image to PDF and Merge.
 *
 * Phone photos are turned the right way up (see ExifOrientation), multi-page TIFF
 * scans become one page per frame, and a JPEG that needs no changes is put in the
 * PDF byte for byte, so it loses no quality.
 */
final class ImagePages {

    /** Page shape for each picture. */
    enum PageSize { A4, LETTER, SAME_AS_PICTURE }

    /**
     * How pictures are laid out.
     *
     * @param margin     empty border in points (18 ≈ 6 mm)
     * @param shrinkDpi  scale photos down to about this sharpness on the page; 0 to keep every pixel
     */
    record Layout(PageSize size, float margin, int shrinkDpi) {}

    /** Merge's layout: pictures fill an A4 page, nothing shrunk. */
    static final Layout FILL_A4 = new Layout(PageSize.A4, 0, 0);

    private static final Set<String> TIFF = Set.of("tif", "tiff");

    private ImagePages() {}

    /** Adds one page per picture in the file (several for a multi-page TIFF). Returns how many. */
    static int addTo(PDDocument doc, Path file, Layout layout) throws IOException {
        String ext = FileInspector.extension(file);
        int orientation = ext.equals("jpg") || ext.equals("jpeg") ? ExifOrientation.read(file) : 1;

        if (TIFF.contains(ext)) {
            List<BufferedImage> frames = readTiffFrames(file);
            for (BufferedImage frame : frames) addPage(doc, LosslessFactory.createFromImage(doc, frame), layout);
            return frames.size();
        }

        boolean jpeg = ext.equals("jpg") || ext.equals("jpeg");
        if (jpeg && orientation == 1) {
            // Fast path: the original JPEG bytes, unless the photo is far sharper than the page needs.
            PDImageXObject original;
            try (InputStream in = Files.newInputStream(file)) {
                original = JPEGFactory.createFromStream(doc, in);
            }
            float[] box = placement(layout, original.getWidth(), original.getHeight());
            if (!tooSharp(original.getWidth(), box[2], layout)) {
                addPage(doc, original, layout);
                return 1;
            }
        }

        BufferedImage picture = ImageIO.read(file.toFile());
        if (picture == null) throw new IOException("Not a picture Java can read: " + file.getFileName());
        picture = ExifOrientation.apply(picture, orientation);
        float[] box = placement(layout, picture.getWidth(), picture.getHeight());
        if (tooSharp(picture.getWidth(), box[2], layout)) {
            double scale = layout.shrinkDpi() * box[2] / 72.0 / picture.getWidth();
            picture = scaled(picture, scale);
        }
        // Photos stay JPEG (small); screenshots, scans and drawings stay lossless (crisp).
        PDImageXObject image = jpeg ? JPEGFactory.createFromImage(doc, withoutAlpha(picture), 0.85f)
                : LosslessFactory.createFromImage(doc, picture);
        addPage(doc, image, layout);
        return 1;
    }

    /** How many pages a picture file gives (TIFFs can hold several). */
    static int pageCount(Path file) throws IOException {
        if (!TIFF.contains(FileInspector.extension(file))) return 1;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = tiffReader(in);
            try {
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void addPage(PDDocument doc, PDImageXObject image, Layout layout) throws IOException {
        float[] box = placement(layout, image.getWidth(), image.getHeight());
        PDPage page = new PDPage(new PDRectangle(box[4], box[5]));
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.drawImage(image, box[0], box[1], box[2], box[3]);
        }
    }

    /**
     * Where the picture goes: {x, y, width, height, pageWidth, pageHeight} in points.
     * The page turns landscape for wide pictures; the picture is scaled to fit inside
     * the margins and centred.
     */
    static float[] placement(Layout layout, int pixelsWide, int pixelsHigh) {
        boolean wide = pixelsWide > pixelsHigh;
        float pageW, pageH;
        if (layout.size() == PageSize.SAME_AS_PICTURE) {
            // Same shape as the picture, with the long side as long as an A4 page.
            float longSide = PDRectangle.A4.getHeight();
            float ratio = (float) pixelsWide / pixelsHigh;
            pageW = wide ? longSide : longSide * ratio;
            pageH = wide ? longSide / ratio : longSide;
            pageW += 2 * layout.margin();
            pageH += 2 * layout.margin();
        } else {
            PDRectangle paper = layout.size() == PageSize.LETTER ? PDRectangle.LETTER : PDRectangle.A4;
            pageW = wide ? paper.getHeight() : paper.getWidth();
            pageH = wide ? paper.getWidth() : paper.getHeight();
        }
        float roomW = pageW - 2 * layout.margin(), roomH = pageH - 2 * layout.margin();
        float scale = Math.min(roomW / pixelsWide, roomH / pixelsHigh);
        float w = pixelsWide * scale, h = pixelsHigh * scale;
        return new float[] {(pageW - w) / 2, (pageH - h) / 2, w, h, pageW, pageH};
    }

    /** True if the picture has many more pixels than the page shows at the chosen sharpness. */
    private static boolean tooSharp(int pixelsWide, float shownWidthPoints, Layout layout) {
        if (layout.shrinkDpi() <= 0) return false;
        double dpi = pixelsWide / (shownWidthPoints / 72.0);
        return dpi > layout.shrinkDpi() * 1.25;    // a little headroom, so near-enough pictures are left alone
    }

    private static BufferedImage scaled(BufferedImage picture, double scale) {
        int w = Math.max(1, (int) Math.round(picture.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(picture.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h,
                picture.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(picture, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** JPEG has no see-through parts; put the picture on white first. */
    private static BufferedImage withoutAlpha(BufferedImage picture) {
        if (!picture.getColorModel().hasAlpha()) return picture;
        BufferedImage out = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(picture, 0, 0, null);
        g.dispose();
        return out;
    }

    private static List<BufferedImage> readTiffFrames(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = tiffReader(in);
            try {
                List<BufferedImage> frames = new ArrayList<>();
                int count = reader.getNumImages(true);
                for (int i = 0; i < count; i++) frames.add(reader.read(i));
                return frames;
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageReader tiffReader(ImageInputStream in) throws IOException {
        if (in == null) throw new IOException("Can't read the TIFF file");
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("Not a TIFF picture Java can read");
        ImageReader reader = readers.next();
        reader.setInput(in);
        return reader;
    }
}
