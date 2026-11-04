package com.ndalamo.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.InputStream;

/**
 * Draws on top of a page in the page's visible orientation, so "bottom centre" is
 * the bottom of the page as people see it, even when the page is stored turned
 * (scans often are). Used by Page numbers and Watermark.
 */
final class PageCanvas {

    /** What to draw, in visible-page coordinates: (0, 0) is bottom-left, width × height in points. */
    interface Drawing {
        void draw(PDPageContentStream content, float width, float height) throws IOException;
    }

    private PageCanvas() {}

    static void drawOn(PDDocument doc, PDPage page, Drawing drawing) throws IOException {
        PDRectangle box = page.getCropBox();
        float x = box.getLowerLeftX(), y = box.getLowerLeftY(), w = box.getWidth(), h = box.getHeight();
        int rotation = ((page.getRotation() % 360) + 360) % 360;

        // APPEND + resetContext: draw after the page's own content, without inheriting its
        // leftover settings (a page that ended mid-transform would otherwise skew our text).
        try (PDPageContentStream content = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            // Turn the visible page's coordinates into the stored page's coordinates.
            switch (rotation) {
                case 90 -> content.transform(new Matrix(0, 1, -1, 0, x + w, y));
                case 180 -> content.transform(new Matrix(-1, 0, 0, -1, x + w, y + h));
                case 270 -> content.transform(new Matrix(0, -1, 1, 0, x, y + h));
                default -> content.transform(Matrix.getTranslateInstance(x, y));
            }
            boolean sideways = rotation == 90 || rotation == 270;
            drawing.draw(content, sideways ? h : w, sideways ? w : h);
        }
    }

    /**
     * A bundled font that can write {@code text}, embedded (only the letters used).
     * IBM Plex Sans matches the app, but it lacks some letters — Tshivenda's ṱ, ḓ, ḽ,
     * ṅ and ṋ among them — so for those Noto Sans, which covers every Latin-script
     * language, is used instead. (The PDF's built-in fonts can't write them either.)
     */
    static PDFont font(PDDocument doc, boolean bold, String text) throws IOException {
        PDFont plex = load(doc, bold ? "/fonts/IBMPlexSans-Bold.ttf" : "/fonts/IBMPlexSans-Regular.ttf");
        if (canWrite(plex, text)) return plex;
        return load(doc, bold ? "/fonts/NotoSans-Bold.ttf" : "/fonts/NotoSans-Regular.ttf");
    }

    private static PDFont load(PDDocument doc, String file) throws IOException {
        try (InputStream in = PageCanvas.class.getResourceAsStream(file)) {
            if (in == null) throw new IOException("Missing bundled font " + file);
            return PDType0Font.load(doc, in);
        }
    }

    private static boolean canWrite(PDFont font, String text) {
        try {
            font.encode(text);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    static float textWidth(PDFont font, String text, float size) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }
}
