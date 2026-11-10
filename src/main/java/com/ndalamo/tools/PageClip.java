package com.ndalamo.tools;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;

import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * Teaches a text reader the page's clipping. PDFBox's text stripper skips path drawing, so its
 * clipping area always stays the whole page, and text a page hides with a clip reads as if it
 * were shown. These operators build each path (in page space) and, on W or W*, narrow the clip
 * when the path ends. Nothing is drawn and the document isn't changed.
 */
final class PageClip {

    private PageClip() {}

    /** Adds the path and clipping operators to a text reader. */
    static void track(PDFStreamEngine engine) {
        Path path = new Path(engine);
        for (String name : List.of("m", "l", "c", "v", "y", "h", "re", "W", "W*",
                "n", "f", "F", "f*", "S", "s", "B", "B*", "b", "b*")) {
            engine.addOperator(path.operator(name));
        }
    }

    /** The path being built, and a clip waiting for the path to end. */
    private static final class Path {
        private final PDFStreamEngine engine;
        private GeneralPath path = new GeneralPath();
        private Integer pendingClip;                      // the winding rule, once W or W* is seen

        Path(PDFStreamEngine engine) {
            this.engine = engine;
        }

        OperatorProcessor operator(String name) {
            return new OperatorProcessor(engine) {
                @Override public String getName() { return name; }
                @Override public void process(Operator operator, List<COSBase> operands) {
                    run(name, operands);
                }
            };
        }

        private void run(String name, List<COSBase> operands) {
            float[] n = new float[operands.size()];
            for (int i = 0; i < n.length; i++) {
                if (!(operands.get(i) instanceof COSNumber number)) return;   // broken operator: ignored
                n[i] = number.floatValue();
            }
            try {
                switch (name) {
                    case "m" -> { Point2D.Float p = page(n[0], n[1]); path.moveTo(p.x, p.y); }
                    case "l" -> { Point2D.Float p = page(n[0], n[1]); lineTo(p); }
                    case "c" -> curve(page(n[0], n[1]), page(n[2], n[3]), page(n[4], n[5]));
                    case "v" -> curve((Point2D.Float) current(), page(n[0], n[1]), page(n[2], n[3]));
                    case "y" -> { Point2D.Float end = page(n[2], n[3]); curve(page(n[0], n[1]), end, end); }
                    case "h" -> { if (path.getCurrentPoint() != null) path.closePath(); }
                    case "re" -> {
                        Point2D.Float a = page(n[0], n[1]), b = page(n[0] + n[2], n[1]),
                                c = page(n[0] + n[2], n[1] + n[3]), d = page(n[0], n[1] + n[3]);
                        path.moveTo(a.x, a.y);
                        path.lineTo(b.x, b.y);
                        path.lineTo(c.x, c.y);
                        path.lineTo(d.x, d.y);
                        path.closePath();
                    }
                    case "W" -> pendingClip = Path2D.WIND_NON_ZERO;
                    case "W*" -> pendingClip = Path2D.WIND_EVEN_ODD;
                    default -> end();                    // n and every painting operator end the path
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // Too few numbers for the operator: skipped, like a viewer would.
            }
        }

        private void end() {
            if (pendingClip != null) {
                path.setWindingRule(pendingClip);
                engine.getGraphicsState().intersectClippingPath(path);
                pendingClip = null;
            }
            path = new GeneralPath();
        }

        private Point2D.Float page(float x, float y) {
            return engine.getGraphicsState().getCurrentTransformationMatrix().transformPoint(x, y);
        }

        private Point2D current() {
            Point2D p = path.getCurrentPoint();
            return p == null ? new Point2D.Float() : new Point2D.Float((float) p.getX(), (float) p.getY());
        }

        private void lineTo(Point2D.Float p) {
            if (path.getCurrentPoint() == null) path.moveTo(p.x, p.y); else path.lineTo(p.x, p.y);
        }

        private void curve(Point2D.Float a, Point2D.Float b, Point2D.Float end) {
            if (path.getCurrentPoint() == null) path.moveTo(end.x, end.y);
            else path.curveTo(a.x, a.y, b.x, b.y, end.x, end.y);
        }
    }
}
