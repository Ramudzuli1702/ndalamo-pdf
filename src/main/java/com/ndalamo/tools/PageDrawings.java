package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Icons, logos, arrows and other drawings made of lines and curves (not pictures inside the
 * PDF, which PageImages takes out as they are) become pictures for PDF to Word: each group of
 * nearby drawn shapes is drawn on its own, sharp, without the page's text (the text comes out
 * as text), and placed where it is on the page. Only reads the PDF.
 */
final class PageDrawings {

    /** Drawn at 3× the PDF's size (216 dpi): sharp in Word and when printed. */
    private static final float SCALE = 3;

    private PageDrawings() {}

    /**
     * @param words    the page's words: an area that's mostly text is a background, not a drawing
     * @param pictures the page's pictures already taken out: drawings over them aren't repeated
     */
    static List<PageImages.Picture> pictures(PDDocument doc, int pageIndex, List<TextWord> words, List<PageImages.Picture> pictures) {
        List<float[]> areas = group(PageShapes.drawings(doc.getPage(pageIndex)));
        areas.removeIf(a -> a[2] - a[0] < 3 || a[3] - a[1] < 3);                 // specks
        areas.removeIf(a -> mostlyText(a, words) || coveredBy(a, pictures));
        if (areas.isEmpty()) return List.of();
        List<PageImages.Picture> found = new ArrayList<>();
        try {
            BufferedImage page = new GraphicsOnly(doc).renderImage(pageIndex, SCALE, ImageType.ARGB);
            for (float[] a : areas) {
                float pad = 1;
                int x = Math.max(0, (int) Math.floor((a[0] - pad) * SCALE));
                int y = Math.max(0, (int) Math.floor((a[1] - pad) * SCALE));
                int w = Math.min(page.getWidth() - x, (int) Math.ceil((a[2] - a[0] + 2 * pad) * SCALE));
                int h = Math.min(page.getHeight() - y, (int) Math.ceil((a[3] - a[1] + 2 * pad) * SCALE));
                if (w < 2 || h < 2) continue;
                BufferedImage crop = page.getSubimage(x, y, w, h);
                if (empty(crop)) continue;
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(crop, "png", png);
                found.add(new PageImages.Picture(png.toByteArray(), false, x / SCALE, y / SCALE, w / SCALE, h / SCALE));
            }
        } catch (IOException | RuntimeException e) {
            return List.of();       // the drawings are left out; the text and pictures still come through
        }
        return found;
    }

    /** Joins areas that touch or nearly touch (an icon is often many small shapes). */
    static List<float[]> group(List<float[]> areas) {
        List<float[]> groups = new ArrayList<>();
        for (float[] a : areas) groups.add(a.clone());
        boolean joined = true;
        while (joined) {
            joined = false;
            outer:
            for (int i = 0; i < groups.size(); i++) {
                for (int j = i + 1; j < groups.size(); j++) {
                    float[] p = groups.get(i), q = groups.get(j);
                    if (p[0] - 2 <= q[2] && q[0] - 2 <= p[2] && p[1] - 2 <= q[3] && q[1] - 2 <= p[3]) {
                        p[0] = Math.min(p[0], q[0]);
                        p[1] = Math.min(p[1], q[1]);
                        p[2] = Math.max(p[2], q[2]);
                        p[3] = Math.max(p[3], q[3]);
                        groups.remove(j);
                        joined = true;
                        break outer;
                    }
                }
            }
        }
        return groups;
    }

    private static boolean mostlyText(float[] a, List<TextWord> words) {
        float area = (a[2] - a[0]) * (a[3] - a[1]), covered = 0;
        for (TextWord w : words) {
            float x = Math.min(a[2], w.right()) - Math.max(a[0], w.left());
            float y = Math.min(a[3], w.bottom()) - Math.max(a[1], w.top());
            if (x > 0 && y > 0) covered += x * y;
        }
        return covered > area * 0.4f;
    }

    private static boolean coveredBy(float[] a, List<PageImages.Picture> pictures) {
        for (PageImages.Picture p : pictures) {
            if (p.left() <= a[0] + 1 && p.left() + p.width() >= a[2] - 1 && p.top() <= a[1] + 1 && p.bottom() >= a[3] - 1) return true;
        }
        return false;
    }

    /** True if nothing visible was drawn there (all see-through or white). */
    private static boolean empty(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) > 16 && (argb & 0xFFFFFF) != 0xFFFFFF) return false;
            }
        }
        return true;
    }

    /** Draws a page without its text, so a drawing's picture doesn't repeat words that come out as text. */
    private static final class GraphicsOnly extends PDFRenderer {
        GraphicsOnly(PDDocument doc) {
            super(doc);
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                @Override
                protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) {
                    // no text
                }

                @Override
                protected void showType3Glyph(Matrix textRenderingMatrix, org.apache.pdfbox.pdmodel.font.PDType3Font font,
                                              int code, Vector displacement) {
                    // no text
                }
            };
        }
    }
}
