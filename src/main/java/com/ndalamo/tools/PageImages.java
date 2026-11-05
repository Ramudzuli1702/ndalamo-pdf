package com.ndalamo.tools;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the pictures drawn on a PDF page and where they sit, for PDF to Word
 * (SPEC §4.1 step 4). Positions are in points from the top-left of the visible page,
 * like PageText's. Photos stored as JPEG are copied byte for byte (no quality lost);
 * anything else is saved as PNG, which keeps transparency. Only reads the PDF.
 */
final class PageImages {

    /** A picture's file bytes and its box on the page. */
    record Picture(byte[] bytes, boolean jpeg, float left, float top, float width, float height) {
        float bottom() { return top + height; }
    }

    /** Smaller than this on either side and it's a bullet, a rule or a spacer, not a picture worth keeping. */
    private static final float SMALLEST = 16;
    /** A picture covering this much of the page is a scan or a background; scans are OCR's job (step 12f). */
    private static final float MOST_OF_PAGE = 0.8f;

    private PageImages() {}

    static List<Picture> pictures(PDPage page) throws IOException {
        if (page.getRotation() % 360 != 0) return List.of();     // turned pages: positions wouldn't match the text
        Finder finder = new Finder(page);
        finder.processPage(page);
        return finder.pictures;
    }

    private static final class Finder extends PDFGraphicsStreamEngine {

        private final List<Picture> pictures = new ArrayList<>();
        private final PDRectangle visible;

        Finder(PDPage page) {
            super(page);
            visible = page.getCropBox();
        }

        @Override
        public void drawImage(PDImage image) throws IOException {
            // The picture fills the unit square, stretched and moved by the current transformation.
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            Point2D.Float[] corners = {m.transformPoint(0, 0), m.transformPoint(1, 0), m.transformPoint(0, 1), m.transformPoint(1, 1)};
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Point2D.Float c : corners) {
                minX = Math.min(minX, c.x);
                maxX = Math.max(maxX, c.x);
                minY = Math.min(minY, c.y);
                maxY = Math.max(maxY, c.y);
            }
            float width = maxX - minX, height = maxY - minY;
            if (width < SMALLEST || height < SMALLEST) return;
            if (width * height > visible.getWidth() * visible.getHeight() * MOST_OF_PAGE) return;

            byte[] bytes;
            boolean jpeg;
            try {
                jpeg = image instanceof PDImageXObject xobject && "jpg".equals(xobject.getSuffix())
                        && !xobject.getColorSpace().getName().equals("DeviceCMYK") && xobject.getSoftMask() == null;
                if (jpeg) {
                    // The JPEG as stored in the PDF: no re-compression, so no quality lost.
                    try (InputStream in = ((PDImageXObject) image).getStream().createInputStream(List.of(COSName.DCT_DECODE.getName()))) {
                        bytes = in.readAllBytes();
                    }
                } else {
                    BufferedImage picture = image.getImage();          // with its transparency mask applied
                    if (picture == null) return;
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(picture, "png", png);
                    bytes = png.toByteArray();
                }
            } catch (IOException e) {
                return;      // e.g. JPEG 2000, which needs a decoder Java doesn't have: leave that picture out
            }
            pictures.add(new Picture(bytes, jpeg, minX - visible.getLowerLeftX(), visible.getUpperRightY() - maxY, width, height));
        }

        // Only pictures matter here; shapes and paths are ignored.
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }
}
