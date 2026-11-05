package com.ndalamo.tools;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what table finding needs from one PDF page: every word with its box, and
 * every straight line drawn on it (table borders). All positions are in points,
 * measured from the top-left corner of the visible page, so words and lines can
 * be compared directly. Only reads; the document is not changed.
 */
final class PageContent {

    /** A word and the box around it. */
    record Box(String text, float left, float top, float right, float bottom) {
        float centreX() { return (left + right) / 2; }
        float centreY() { return (top + bottom) / 2; }
        float height() { return bottom - top; }
    }

    /**
     * A straight border line. Horizontal: {@code position} is its y, and it runs from x
     * {@code from} to {@code to}. Vertical: {@code position} is its x, running from y to y.
     */
    record Rule(boolean horizontal, float position, float from, float to) {}

    /** Shorter lines than this are text decoration (underlines of single letters, bullets), not borders. */
    private static final float SHORTEST_RULE = 4;
    /** A filled rectangle thinner than this is drawn as a line; many PDFs draw borders that way. */
    private static final float THINNEST_FILL = 3;

    private PageContent() {}

    /** The words on a page (0-based index), in reading order. */
    static List<Box> words(PDDocument doc, int pageIndex) throws IOException {
        List<Box> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> letters) {
                if (text.isBlank() || letters.isEmpty()) return;
                float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
                for (TextPosition letter : letters) {
                    left = Math.min(left, letter.getXDirAdj());
                    right = Math.max(right, letter.getXDirAdj() + letter.getWidthDirAdj());
                    top = Math.min(top, letter.getYDirAdj() - letter.getHeightDir());   // y is the baseline
                    bottom = Math.max(bottom, letter.getYDirAdj());
                }
                words.add(new Box(text.strip(), left, top, right, bottom));
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return words;
    }

    /**
     * The straight horizontal and vertical lines drawn on a page: stroked lines,
     * the edges of stroked rectangles, and thin filled rectangles. Curves and
     * slanted lines are ignored. Empty for a turned page (its words are measured in
     * the turned direction, its lines aren't, so they wouldn't match up).
     */
    static List<Rule> rules(PDPage page) throws IOException {
        if (page.getRotation() % 360 != 0) return List.of();
        RuleCollector collector = new RuleCollector(page);
        collector.processPage(page);
        return collector.rules;
    }

    /** Walks the page's drawing instructions and keeps the straight lines. */
    private static final class RuleCollector extends PDFGraphicsStreamEngine {

        private final List<Rule> rules = new ArrayList<>();
        private final List<float[]> segments = new ArrayList<>();      // the path being built: x1, y1, x2, y2
        private final List<float[]> rectangles = new ArrayList<>();    // x, y, width, height
        private final PDRectangle visible;
        private Point2D.Float current = new Point2D.Float();
        private Point2D.Float subpathStart = new Point2D.Float();

        RuleCollector(PDPage page) {
            super(page);
            visible = page.getCropBox();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            Point2D[] corners = {p0, p1, p2, p3};
            for (int i = 0; i < 4; i++) {
                Point2D a = corners[i], b = corners[(i + 1) % 4];
                segments.add(new float[]{(float) a.getX(), (float) a.getY(), (float) b.getX(), (float) b.getY()});
            }
            float minX = (float) Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            float maxX = (float) Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            float minY = (float) Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            float maxY = (float) Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            rectangles.add(new float[]{minX, minY, maxX - minX, maxY - minY});
            current = new Point2D.Float((float) p0.getX(), (float) p0.getY());
        }

        @Override
        public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
            subpathStart = current;
        }

        @Override
        public void lineTo(float x, float y) {
            segments.add(new float[]{current.x, current.y, x, y});
            current = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);                     // curves are never table borders
        }

        @Override
        public void closePath() {
            segments.add(new float[]{current.x, current.y, subpathStart.x, subpathStart.y});
            current = subpathStart;
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void strokePath() {
            for (float[] s : segments) keepIfStraight(s[0], s[1], s[2], s[3]);
            clearPath();
        }

        @Override
        public void fillPath(int windingRule) {
            // A filled rectangle counts only when it's thin enough to be a line; big fills are shading.
            for (float[] r : rectangles) {
                if (r[3] < THINNEST_FILL && r[2] >= SHORTEST_RULE) {
                    keepIfStraight(r[0], r[1] + r[3] / 2, r[0] + r[2], r[1] + r[3] / 2);
                } else if (r[2] < THINNEST_FILL && r[3] >= SHORTEST_RULE) {
                    keepIfStraight(r[0] + r[2] / 2, r[1], r[0] + r[2] / 2, r[1] + r[3]);
                }
            }
            clearPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            strokePath();
        }

        @Override
        public void endPath() {
            clearPath();
        }

        @Override public void clip(int windingRule) {}
        @Override public void drawImage(PDImage pdImage) {}
        @Override public void shadingFill(COSName shadingName) {}

        private void clearPath() {
            segments.clear();
            rectangles.clear();
        }

        /** Keeps a segment if it's horizontal or vertical and long enough, turned into top-left page measurements. */
        private void keepIfStraight(float x1, float y1, float x2, float y2) {
            float left = x1 - visible.getLowerLeftX(), right = x2 - visible.getLowerLeftX();
            float top = visible.getUpperRightY() - y1, bottom = visible.getUpperRightY() - y2;
            if (Math.abs(top - bottom) < 1 && Math.abs(right - left) >= SHORTEST_RULE) {
                rules.add(new Rule(true, (top + bottom) / 2, Math.min(left, right), Math.max(left, right)));
            } else if (Math.abs(right - left) < 1 && Math.abs(bottom - top) >= SHORTEST_RULE) {
                rules.add(new Rule(false, (left + right) / 2, Math.min(top, bottom), Math.max(top, bottom)));
            }
        }
    }
}
