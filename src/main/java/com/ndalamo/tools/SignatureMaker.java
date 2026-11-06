package com.ndalamo.tools;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Makes the signature picture for Sign PDF: a PNG with a see-through background,
 * cropped close around the ink, from a drawing, a typed name or a photo of a
 * signature on paper. No JavaFX here: the sign dialog passes plain points and text.
 */
public final class SignatureMaker {

    /** Pictures are made at this many pixels per point of the drawing area, so they stay sharp when printed. */
    private static final int SCALE = 4;

    private SignatureMaker() {}

    /**
     * From strokes drawn with the mouse or a pen. Each stroke is a list of {x, y} points
     * in a drawing area {@code width} × {@code height}. Null if nothing was drawn.
     */
    public static byte[] drawn(List<List<double[]>> strokes, double width, double height, Color ink) throws IOException {
        BufferedImage picture = new BufferedImage((int) (width * SCALE), (int) (height * SCALE), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(picture);
        g.setColor(ink);
        g.setStroke(new BasicStroke(2.6f * SCALE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        boolean any = false;
        for (List<double[]> stroke : strokes) {
            if (stroke.isEmpty()) continue;
            any = true;
            Path2D path = new Path2D.Double();
            path.moveTo(stroke.get(0)[0] * SCALE, stroke.get(0)[1] * SCALE);
            if (stroke.size() == 1) path.lineTo(stroke.get(0)[0] * SCALE + 0.5, stroke.get(0)[1] * SCALE);   // a dot
            for (int i = 1; i < stroke.size(); i++) path.lineTo(stroke.get(i)[0] * SCALE, stroke.get(i)[1] * SCALE);
            g.draw(path);
        }
        g.dispose();
        return any ? png(crop(picture)) : null;
    }

    /**
     * A typed name in Dancing Script, the bundled handwriting font. If the name has a
     * letter Dancing Script lacks (e.g. Tshivenda ṱ), Noto Sans is used so it isn't
     * written with blank boxes. Null for an empty name.
     */
    public static byte[] typed(String name, Color ink) throws IOException {
        String text = name == null ? "" : name.strip();
        if (text.isEmpty()) return null;
        Font font = load("/fonts/DancingScript-Regular.ttf");
        if (font.canDisplayUpTo(text) != -1) font = load("/fonts/NotoSans-Regular.ttf");
        font = font.deriveFont(48f * SCALE);

        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = measure.createGraphics();
        var metrics = m.getFontMetrics(font);
        int w = metrics.stringWidth(text) + 40 * SCALE, h = metrics.getHeight() + 20 * SCALE;
        m.dispose();

        BufferedImage picture = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(picture);
        g.setFont(font);
        g.setColor(ink);
        g.drawString(text, 20 * SCALE, 10 * SCALE + metrics.getAscent());
        g.dispose();
        return png(crop(picture));
    }

    /**
     * From a photo or scan of a signature on paper: the paper becomes see-through, the
     * ink stays. Pale greys fade gradually, so the ink keeps soft edges. Null if the
     * photo has no ink on it (all paper).
     */
    public static byte[] fromPhoto(BufferedImage photo) throws IOException {
        BufferedImage picture = new BufferedImage(photo.getWidth(), photo.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < photo.getHeight(); y++) {
            for (int x = 0; x < photo.getWidth(); x++) {
                int rgb = photo.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int light = (r * 299 + gr * 587 + b * 114) / 1000;
                // Lighter than 215: paper. Darker than 150: ink. In between: partly see-through.
                int alpha = light >= 215 ? 0 : light <= 150 ? 255 : (215 - light) * 255 / 65;
                alpha = Math.min(alpha, (rgb >>> 24) == 0 && photo.getColorModel().hasAlpha() ? 0 : 255);
                picture.setRGB(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
            }
        }
        BufferedImage cropped = crop(picture);
        return cropped == null ? null : png(cropped);
    }

    /** The picture cut down to its ink, with a little room around it. Null if it has no ink. */
    static BufferedImage crop(BufferedImage picture) {
        int minX = picture.getWidth(), minY = picture.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                if ((picture.getRGB(x, y) >>> 24) > 16) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        int room = Math.max(4, (maxY - minY) / 12);
        int x0 = Math.max(0, minX - room), y0 = Math.max(0, minY - room);
        int x1 = Math.min(picture.getWidth(), maxX + 1 + room), y1 = Math.min(picture.getHeight(), maxY + 1 + room);
        return picture.getSubimage(x0, y0, x1 - x0, y1 - y0);
    }

    private static Graphics2D smooth(BufferedImage picture) {
        Graphics2D g = picture.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static Font load(String resource) throws IOException {
        try (InputStream in = SignatureMaker.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing bundled font " + resource);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (FontFormatException e) {
            throw new IOException("Bundled font " + resource + " can't be read", e);
        }
    }

    private static byte[] png(BufferedImage picture) throws IOException {
        if (picture == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", out);
        return out.toByteArray();
    }
}
