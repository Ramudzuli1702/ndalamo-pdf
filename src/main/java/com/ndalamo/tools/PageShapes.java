package com.ndalamo.tools;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The boxes and straight lines drawn on a page, with their colours: code-block backgrounds,
 * the bar beside a quote, dividing lines, table shading and borders. PDF to Word turns them
 * into Word's own shading and borders (WordShapes). Positions are in points from the top
 * left of the page as shown. Only reads the page.
 */
final class PageShapes {

    /** A filled box or a straight line (a line is a thin box as wide as the pen). {@code rgb} as 0xRRGGBB. */
    record Shape(float left, float top, float right, float bottom, int rgb, boolean line) {
        float width() { return right - left; }
        float height() { return bottom - top; }
    }

    private PageShapes() {}

    /** The page's shapes; none for a turned page (its text is measured differently). */
    static List<Shape> of(PDPage page) {
        return collect(page).shapes;
    }

    /**
     * Where the page has drawings that aren't boxes or straight lines: icons, logos, arrows,
     * simple charts. Each is an area {left, top, right, bottom} in points; PageDrawings turns
     * them into pictures.
     */
    static List<float[]> drawings(PDPage page) {
        return collect(page).drawings;
    }

    private static Collector collect(PDPage page) {
        Collector collector = new Collector(page);
        if (page.getRotation() % 360 != 0) return collector;
        try {
            collector.processPage(page);
        } catch (IOException | RuntimeException e) {
            // a page whose drawing can't be followed: no shapes, the text still comes out
        }
        return collector;
    }

    private static final class Collector extends PDFGraphicsStreamEngine {

        private final List<Shape> shapes = new ArrayList<>();
        private final List<float[]> drawings = new ArrayList<>();
        private int curves;
        private boolean diagonal;
        private final List<float[]> segments = new ArrayList<>();   // x1, y1, x2, y2 in PDF space
        private final List<float[]> boxes = new ArrayList<>();      // minX, minY, maxX, maxY: rectangles in the path
        private boolean curved;
        private final PDRectangle visible;
        private final float pageArea;
        private Point2D.Float current = new Point2D.Float();
        private Point2D.Float start = new Point2D.Float();
        private float minX, minY, maxX, maxY;

        Collector(PDPage page) {
            super(page);
            visible = page.getCropBox();
            pageArea = visible.getWidth() * visible.getHeight();
            resetBounds();
        }

        private void resetBounds() {
            minX = minY = Float.MAX_VALUE;
            maxX = maxY = -Float.MAX_VALUE;
        }

        private void include(double x, double y) {
            minX = Math.min(minX, (float) x);
            maxX = Math.max(maxX, (float) x);
            minY = Math.min(minY, (float) y);
            maxY = Math.max(maxY, (float) y);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            float x0 = (float) Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            float x1 = (float) Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            float y0 = (float) Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            float y1 = (float) Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            boxes.add(new float[] {x0, y0, x1, y1});
            Point2D[] corners = {p0, p1, p2, p3};
            for (int i = 0; i < 4; i++) {
                Point2D a = corners[i], b = corners[(i + 1) % 4];
                segments.add(new float[] {(float) a.getX(), (float) a.getY(), (float) b.getX(), (float) b.getY()});
                include(a.getX(), a.getY());
            }
            current = new Point2D.Float((float) p0.getX(), (float) p0.getY());
        }

        @Override
        public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
            start = current;
            include(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            segments.add(new float[] {current.x, current.y, x, y});
            if (Math.abs(x - current.x) > 0.5f && Math.abs(y - current.y) > 0.5f) diagonal = true;
            current = new Point2D.Float(x, y);
            include(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            curved = true;               // rounded corners, or a drawing
            curves++;
            include(x1, y1);
            include(x2, y2);
            include(x3, y3);
            current = new Point2D.Float(x3, y3);
        }

        @Override
        public void closePath() {
            segments.add(new float[] {current.x, current.y, start.x, start.y});
            current = start;
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void fillPath(int windingRule) throws IOException {
            int rgb = rgb(getGraphicsState().getNonStrokingColor());
            // A box with rounded corners has four curves joined by straight sides; anything else curved
            // or slanted is a drawing (an icon, a circle, an arrow).
            boolean roundedBox = curved && curves <= 4 && segments.size() >= 4 && !diagonal;
            if ((curved && !roundedBox) || diagonal) {
                if (minX <= maxX) addDrawing(minX, minY, maxX, maxY);
            } else if (rgb >= 0 && rgb != 0xFFFFFF) {
                if (!boxes.isEmpty() && !curved) {
                    for (float[] b : boxes) addBox(b[0], b[1], b[2], b[3], rgb);
                } else if (minX <= maxX) {
                    addBox(minX, minY, maxX, maxY, rgb);       // a box drawn with lines, or with rounded corners
                }
            }
            clear();
        }

        @Override
        public void strokePath() throws IOException {
            int rgb = rgb(getGraphicsState().getStrokingColor());
            float pen = (float) Math.max(0.25, getGraphicsState().getLineWidth()
                    * Math.abs(getGraphicsState().getCurrentTransformationMatrix().getScalingFactorX()));
            if ((curved || diagonal) && minX <= maxX) {
                addDrawing(minX - pen / 2, minY - pen / 2, maxX + pen / 2, maxY + pen / 2);
            } else if (rgb >= 0 && rgb != 0xFFFFFF) {
                for (float[] s : segments) {
                    if (Math.abs(s[1] - s[3]) < 0.5f && Math.abs(s[0] - s[2]) >= 2) {
                        addLine(Math.min(s[0], s[2]), s[1] - pen / 2, Math.max(s[0], s[2]), s[1] + pen / 2, rgb);
                    } else if (Math.abs(s[0] - s[2]) < 0.5f && Math.abs(s[1] - s[3]) >= 2) {
                        addLine(s[0] - pen / 2, Math.min(s[1], s[3]), s[0] + pen / 2, Math.max(s[1], s[3]), rgb);
                    }
                }
            }
            clear();
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException {
            List<float[]> keepSegments = new ArrayList<>(segments);
            List<float[]> keepBoxes = new ArrayList<>(boxes);
            boolean keepCurved = curved, keepDiagonal = diagonal;
            int keepCurves = curves;
            float[] keepBounds = {minX, minY, maxX, maxY};
            fillPath(windingRule);
            segments.addAll(keepSegments);
            boxes.addAll(keepBoxes);
            curved = keepCurved;
            curves = keepCurves;
            diagonal = keepDiagonal;
            minX = keepBounds[0];
            minY = keepBounds[1];
            maxX = keepBounds[2];
            maxY = keepBounds[3];
            strokePath();
        }

        @Override
        public void endPath() {
            clear();
        }

        @Override public void clip(int windingRule) {}
        @Override public void drawImage(PDImage pdImage) {}
        @Override public void shadingFill(COSName shadingName) {}

        private void clear() {
            segments.clear();
            boxes.clear();
            curved = false;
            curves = 0;
            diagonal = false;
            resetBounds();
        }

        /** A filled box, unless it covers most of the page (a page background isn't shading). */
        private void addBox(float x0, float y0, float x1, float y1, int rgb) {
            if ((x1 - x0) * (y1 - y0) > pageArea * 0.6f) return;
            shapes.add(new Shape(x0 - visible.getLowerLeftX(), visible.getUpperRightY() - y1,
                    x1 - visible.getLowerLeftX(), visible.getUpperRightY() - y0, rgb, false));
        }

        private void addDrawing(float x0, float y0, float x1, float y1) {
            if ((x1 - x0) * (y1 - y0) > pageArea * 0.6f) return;
            drawings.add(new float[] {x0 - visible.getLowerLeftX(), visible.getUpperRightY() - y1,
                    x1 - visible.getLowerLeftX(), visible.getUpperRightY() - y0});
        }

        private void addLine(float x0, float y0, float x1, float y1, int rgb) {
            shapes.add(new Shape(x0 - visible.getLowerLeftX(), visible.getUpperRightY() - y1,
                    x1 - visible.getLowerLeftX(), visible.getUpperRightY() - y0, rgb, true));
        }

        /** A colour as 0xRRGGBB, or -1 if it can't be turned into one (patterns and such). */
        private static int rgb(PDColor colour) {
            try {
                return colour == null ? -1 : colour.toRGB() & 0xFFFFFF;
            } catch (IOException | RuntimeException e) {
                return -1;
            }
        }
    }
}
