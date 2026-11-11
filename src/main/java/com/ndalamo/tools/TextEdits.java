package com.ndalamo.tools;

import com.ndalamo.models.PdfEdits;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.contentstream.operator.text.BeginText;
import org.apache.pdfbox.contentstream.operator.text.EndText;
import org.apache.pdfbox.contentstream.operator.text.MoveText;
import org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading;
import org.apache.pdfbox.contentstream.operator.text.NextLine;
import org.apache.pdfbox.contentstream.operator.text.SetCharSpacing;
import org.apache.pdfbox.contentstream.operator.text.SetFontAndSize;
import org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling;
import org.apache.pdfbox.contentstream.operator.text.SetTextLeading;
import org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode;
import org.apache.pdfbox.contentstream.operator.text.SetTextRise;
import org.apache.pdfbox.contentstream.operator.text.SetWordSpacing;
import org.apache.pdfbox.contentstream.operator.text.ShowText;
import org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLine;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The page work behind Edit PDF: finding each letter on a page, taking chosen letters
 * out of the page for good, finding highlighter marks, and writing new text on.
 *
 * Removing is real: the letters are cut out of the page's text instructions, so they are
 * gone from the file (not covered up), while everything else stays sharp and selectable,
 * unlike Redact, which turns the page into a picture. Each cut is replaced by a spacing
 * number of exactly the removed width, so the rest of the line doesn't move. Only text
 * drawn by the page itself is touched; text inside embedded drawings (form XObjects),
 * Type 3 and vertical fonts, and scanned pages (pictures of text) are left as they are.
 *
 * Positions given out and taken in are fractions of the page as shown (0,0 top left),
 * allowing for the crop box and the page's rotation, like the rest of the app.
 */
final class TextEdits {

    /** One letter on a page, in page fractions as shown. */
    record Letter(String text, double left, double top, double right, double bottom) {
        double midX() { return (left + right) / 2; }
        double midY() { return (top + bottom) / 2; }
    }

    private TextEdits() {}

    // ── Reading ──────────────────────────────────────────────────────────

    /** The letters that can be removed from a page (0-based index), in drawing order. */
    static List<Letter> letters(PDDocument doc, int pageIndex) throws IOException {
        PDPage page = doc.getPage(pageIndex);
        Finder finder = new Finder();
        finder.processPage(page);
        Shown shown = new Shown(page);
        List<Letter> letters = new ArrayList<>();
        for (Glyph g : finder.glyphs) {
            if (g.removable) letters.add(shown.letter(g));
        }
        return letters;
    }

    /** Areas marked with a highlighter (text-markup highlight annotations), in page fractions. */
    static List<double[]> highlights(PDPage page) throws IOException {
        Shown shown = new Shown(page);
        List<double[]> areas = new ArrayList<>();
        for (PDAnnotation a : page.getAnnotations()) {
            if (!(a instanceof PDAnnotationTextMarkup mark) || !"Highlight".equals(a.getSubtype())) continue;
            float[] q = mark.getQuadPoints();
            if (q == null || q.length < 8) {
                PDRectangle r = a.getRectangle();
                if (r != null) areas.add(shown.box(r.getLowerLeftX(), r.getLowerLeftY(), r.getUpperRightX(), r.getUpperRightY()));
                continue;
            }
            // One quadrilateral per highlighted line: four corners, eight numbers.
            for (int i = 0; i + 7 < q.length; i += 8) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int k = 0; k < 8; k += 2) {
                    minX = Math.min(minX, q[i + k]);
                    maxX = Math.max(maxX, q[i + k]);
                    minY = Math.min(minY, q[i + k + 1]);
                    maxY = Math.max(maxY, q[i + k + 1]);
                }
                areas.add(shown.box(minX, minY, maxX, maxY));
            }
        }
        return areas;
    }

    // ── Removing ─────────────────────────────────────────────────────────

    /**
     * Takes out every letter whose middle is inside one of the areas, and the highlighter
     * marks over those areas. Returns how many letters were removed; 0 leaves the page as it was.
     */
    static int remove(PDDocument doc, PDPage page, List<PdfEdits.Removal> areas) throws IOException {
        if (areas.isEmpty()) return 0;
        Finder finder = new Finder();
        finder.processPage(page);
        Shown shown = new Shown(page);

        // Which codes to drop, per text operator and string within it.
        Map<Long, Set<Integer>> drop = new HashMap<>();
        int removed = 0;
        for (Glyph g : finder.glyphs) {
            if (!g.removable) continue;
            Letter l = shown.letter(g);
            if (areas.stream().anyMatch(a -> a.contains(l.midX(), l.midY()))) {
                drop.computeIfAbsent(key(g.op, g.string), k -> new HashSet<>()).add(g.code);
                removed++;
            }
        }
        removeHighlightMarks(page, shown, areas);
        if (removed == 0) return 0;

        List<Object> tokens = new ArrayList<>();
        for (int i = 0; i < finder.ops.size(); i++) {
            Recorded op = finder.ops.get(i);
            String name = op.operator.getName();
            boolean touched = drop.keySet().stream().anyMatch(k -> (int) (k >> 20) == op.index);
            if (!touched) {
                tokens.addAll(op.operands);
                tokens.add(op.operator);
                continue;
            }
            switch (name) {
                case "Tj" -> addShow(tokens, finder, op, List.of(op.operands.get(0)), drop);
                case "TJ" -> {
                    List<COSBase> parts = new ArrayList<>();
                    for (COSBase part : (COSArray) op.operands.get(0)) parts.add(part);
                    addShow(tokens, finder, op, parts, drop);
                }
                case "'" -> {
                    tokens.add(Operator.getOperator("T*"));
                    addShow(tokens, finder, op, List.of(op.operands.get(0)), drop);
                }
                case "\"" -> {
                    tokens.add(op.operands.get(0));
                    tokens.add(Operator.getOperator("Tw"));
                    tokens.add(op.operands.get(1));
                    tokens.add(Operator.getOperator("Tc"));
                    tokens.add(Operator.getOperator("T*"));
                    addShow(tokens, finder, op, List.of(op.operands.get(2)), drop);
                }
                default -> {
                    tokens.addAll(op.operands);
                    tokens.add(op.operator);
                }
            }
        }
        PDStream contents = new PDStream(doc);
        try (OutputStream out = contents.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
        page.setContents(contents);
        return removed;
    }

    /**
     * Writes one text-showing operator again as TJ: kept letters as strings, each run of
     * removed letters as a spacing number of the same width, so what follows stays put.
     */
    private static void addShow(List<Object> tokens, Finder finder, Recorded op, List<COSBase> elements,
                                Map<Long, Set<Integer>> drop) {
        COSArray array = new COSArray();
        int ordinal = 0;
        for (COSBase element : elements) {
            if (!(element instanceof COSString string)) {
                array.add(element);
                continue;
            }
            int strIndex = ordinal++;
            Set<Integer> gone = drop.getOrDefault(key(op.index, strIndex), Set.of());
            List<Glyph> glyphs = finder.byString.getOrDefault(key(op.index, strIndex), List.of());
            if (gone.isEmpty() || glyphs.isEmpty()) {
                array.add(string);
                continue;
            }
            byte[] bytes = string.getBytes();
            int keptFrom = -1;
            float skipped = 0;
            for (Glyph g : glyphs) {
                if (gone.contains(g.code)) {
                    if (keptFrom >= 0) {
                        array.add(new COSString(Arrays.copyOfRange(bytes, keptFrom, g.byteStart)));
                        keptFrom = -1;
                    }
                    skipped += g.advance;
                } else {
                    if (skipped != 0) {
                        array.add(new COSFloat(-1000 * skipped / g.fontSize));
                        skipped = 0;
                    }
                    if (keptFrom < 0) keptFrom = g.byteStart;
                }
            }
            if (keptFrom >= 0) array.add(new COSString(Arrays.copyOfRange(bytes, keptFrom, bytes.length)));
            if (skipped != 0) array.add(new COSFloat(-1000 * skipped / glyphs.get(0).fontSize));
        }
        tokens.add(array);
        tokens.add(Operator.getOperator("TJ"));
    }

    /** Highlighter marks whose middle lies in a removed area go too: there's nothing left to highlight. */
    private static void removeHighlightMarks(PDPage page, Shown shown, List<PdfEdits.Removal> areas) throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        boolean changed = annotations.removeIf(a -> {
            if (!(a instanceof PDAnnotationTextMarkup) || !"Highlight".equals(a.getSubtype())) return false;
            PDRectangle r = a.getRectangle();
            if (r == null) return false;
            double[] box = shown.box(r.getLowerLeftX(), r.getLowerLeftY(), r.getUpperRightX(), r.getUpperRightY());
            double mx = (box[0] + box[2]) / 2, my = (box[1] + box[3]) / 2;
            return areas.stream().anyMatch(area -> area.contains(mx, my));
        });
        if (changed) page.setAnnotations(annotations);
    }

    private static long key(int op, int string) {
        return ((long) op << 20) | string;
    }

    // ── Adding ───────────────────────────────────────────────────────────

    /** Writes the additions for this page (1-based) on top of it, in the page's visible orientation. */
    static void add(PDDocument doc, PDPage page, List<PdfEdits.Addition> additions) throws IOException {
        if (additions.isEmpty()) return;
        List<PDFont> fonts = new ArrayList<>();
        for (PdfEdits.Addition a : additions) fonts.add(font(doc, a.font(), a.bold(), a.text()));
        PageCanvas.drawOn(doc, page, (content, width, height) -> {
            for (int i = 0; i < additions.size(); i++) {
                PdfEdits.Addition a = additions.get(i);
                PDFont font = fonts.get(i);
                float ascent = font.getFontDescriptor() != null && font.getFontDescriptor().getAscent() > 0
                        ? font.getFontDescriptor().getAscent() / 1000 * a.size() : a.size() * 0.8f;
                float x = (float) (a.left() * width);
                float y = (float) (height - a.top() * height) - ascent;
                java.awt.Color color = java.awt.Color.decode(a.color());
                content.beginText();
                content.setFont(font, a.size());
                content.setNonStrokingColor(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f);
                content.setLeading(a.size() * 1.2f);
                content.newLineAtOffset(x, y);
                String[] lines = a.text().replace("\t", "    ").split("\r?\n", -1);
                for (int l = 0; l < lines.length; l++) {
                    if (l > 0) content.newLine();
                    if (!lines[l].isEmpty()) content.showText(lines[l]);
                }
                content.endText();
            }
        });
    }

    /** The bundled font for a family; Noto Sans when it lacks a letter (e.g. Tshivenda's ṱ). */
    private static PDFont font(PDDocument doc, String family, boolean bold, String text) throws IOException {
        String file = switch (family == null ? "Sans" : family) {
            case "Serif" -> bold ? "/fonts/liberation/LiberationSerif-Bold.ttf" : "/fonts/liberation/LiberationSerif-Regular.ttf";
            case "Mono" -> bold ? "/fonts/liberation/LiberationMono-Bold.ttf" : "/fonts/liberation/LiberationMono-Regular.ttf";
            case "Handwriting" -> "/fonts/DancingScript-Regular.ttf";
            default -> bold ? "/fonts/liberation/LiberationSans-Bold.ttf" : "/fonts/liberation/LiberationSans-Regular.ttf";
        };
        PDFont font = PageCanvas.load(doc, file);
        try {
            font.encode(text.replace("\n", "").replace("\r", "").replace("\t", "    "));
            return font;
        } catch (IOException | IllegalArgumentException e) {
            return PageCanvas.load(doc, bold ? "/fonts/NotoSans-Bold.ttf" : "/fonts/NotoSans-Regular.ttf");
        }
    }

    // ── The reader ───────────────────────────────────────────────────────

    /** One drawn code: which operator and string it came from, its bytes, width and box (page space). */
    private record Glyph(int op, int string, int code, int byteStart, float advance, float fontSize,
                         float minX, float minY, float maxX, float maxY, String text, boolean removable) {}

    private record Recorded(int index, Operator operator, List<COSBase> operands) {}

    /**
     * Follows the page's own drawing instructions, keeping each one (to write the page
     * again) and where each letter lands. Embedded drawings ("Do") are deliberately not
     * followed, so every recorded instruction belongs to the page itself.
     */
    private static final class Finder extends PDFStreamEngine {
        final List<Recorded> ops = new ArrayList<>();
        final List<Glyph> glyphs = new ArrayList<>();
        final Map<Long, List<Glyph>> byString = new HashMap<>();
        private int currentOp = -1;
        private int stringOrdinal;
        private List<int[]> codes = List.of();      // byte start and length of each code in the current string
        private int codeIndex;

        Finder() {
            addOperator(new BeginText(this));
            addOperator(new EndText(this));
            addOperator(new SetFontAndSize(this));
            addOperator(new MoveText(this));
            addOperator(new MoveTextSetLeading(this));
            addOperator(new SetMatrix(this));
            addOperator(new NextLine(this));
            addOperator(new SetCharSpacing(this));
            addOperator(new SetWordSpacing(this));
            addOperator(new SetTextHorizontalScaling(this));
            addOperator(new SetTextLeading(this));
            addOperator(new SetTextRise(this));
            addOperator(new SetTextRenderingMode(this));
            addOperator(new ShowText(this));
            addOperator(new ShowTextAdjusted(this));
            addOperator(new ShowTextLine(this));
            addOperator(new ShowTextLineAndSpace(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new Concatenate(this));
            addOperator(new SetGraphicsStateParameters(this));
        }

        private int depth;                          // > 0 while PDFBox runs an instruction's own steps

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            // ' and " are carried out by PDFBox as T* then Tj (and Tw, Tc): those inner steps
            // aren't in the file, so only the outermost instruction is recorded.
            if (depth == 0) {
                currentOp = ops.size();
                stringOrdinal = 0;
                ops.add(new Recorded(currentOp, operator, operands == null ? List.of() : new ArrayList<>(operands)));
            }
            depth++;
            try {
                super.processOperator(operator, operands);
            } finally {
                depth--;
            }
        }

        @Override
        protected void showText(byte[] string) throws IOException {
            PDFont font = getGraphicsState().getTextState().getFont();
            List<int[]> found = new ArrayList<>();
            if (font != null) {
                ByteArrayInputStream in = new ByteArrayInputStream(string);
                int pos = 0;
                while (in.available() > 0) {
                    int before = in.available();
                    font.readCode(in);
                    int length = before - in.available();
                    found.add(new int[]{pos, length});
                    pos += length;
                }
            }
            codes = found;
            codeIndex = 0;
            super.showText(string);
            stringOrdinal++;
        }

        @Override
        protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement) {
            int index = codeIndex++;
            if (index >= codes.size()) return;
            PDTextState state = getGraphicsState().getTextState();
            float size = state.getFontSize();
            boolean space = code == 32 && codes.get(index)[1] == 1;
            float advance = displacement.getX() * size + state.getCharacterSpacing() + (space ? state.getWordSpacing() : 0);
            boolean removable = !(font instanceof PDType3Font) && !font.isVertical() && size != 0;

            // The letter's box: its width, from a little below the baseline to the top of capitals.
            float w = displacement.getX();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] c : new float[][]{{0, -0.2f}, {w, -0.2f}, {0, 0.8f}, {w, 0.8f}}) {
                Point2D.Float p = trm.transformPoint(c[0], c[1]);
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            String text = font.toUnicode(code);
            Glyph g = new Glyph(currentOp, stringOrdinal, index, codes.get(index)[0], advance, size,
                    minX, minY, maxX, maxY, text == null ? "" : text, removable);
            glyphs.add(g);
            byString.computeIfAbsent(key(currentOp, stringOrdinal), k -> new ArrayList<>()).add(g);
        }
    }

    // ── Page space ⇄ the page as shown ───────────────────────────────────

    /** Converts page-space boxes to fractions of the page as people see it (crop box and rotation applied). */
    private static final class Shown {
        private final float llx, lly, w, h;
        private final int rotation;

        Shown(PDPage page) {
            PDRectangle box = page.getCropBox();
            llx = box.getLowerLeftX();
            lly = box.getLowerLeftY();
            w = box.getWidth();
            h = box.getHeight();
            rotation = ((page.getRotation() % 360) + 360) % 360;
        }

        Letter letter(Glyph g) {
            double[] b = box(g.minX, g.minY, g.maxX, g.maxY);
            return new Letter(g.text, b[0], b[1], b[2], b[3]);
        }

        /** {left, top, right, bottom} as fractions of the shown page. */
        double[] box(float x1, float y1, float x2, float y2) {
            double[] a = point(x1, y1), b = point(x2, y2);
            return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
        }

        private double[] point(float x, float y) {
            double u = (x - llx) / w, v = (y - lly) / h;            // from the stored page's bottom-left
            return switch (rotation) {
                case 90 -> new double[]{v, u};
                case 180 -> new double[]{1 - u, v};
                case 270 -> new double[]{1 - v, 1 - u};
                default -> new double[]{u, 1 - v};
            };
        }
    }
}
