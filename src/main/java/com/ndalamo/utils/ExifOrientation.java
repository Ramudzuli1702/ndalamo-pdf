package com.ndalamo.utils;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phone photos are usually saved sideways with a note ("EXIF orientation") saying
 * how to turn them. Most apps read the note; Java's image loading doesn't, so
 * without this a portrait photo would land sideways in the PDF.
 *
 * This reads just that one note from a JPEG — no extra library — and turns the
 * picture the way the phone meant.
 */
public final class ExifOrientation {

    private ExifOrientation() {}

    /** The orientation note, 1–8; 1 (as taken) if there is none or the file isn't a JPEG. */
    public static int read(Path jpeg) {
        try (InputStream raw = Files.newInputStream(jpeg); DataInputStream in = new DataInputStream(raw)) {
            if (in.readUnsignedShort() != 0xFFD8) return 1;                 // not a JPEG
            while (true) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA) return 1;   // picture data starts: no note found
                int length = in.readUnsignedShort() - 2;
                if (marker != 0xFFE1) {
                    in.skipNBytes(length);
                    continue;
                }
                byte[] block = in.readNBytes(length);
                return orientationIn(block);
            }
        } catch (IOException | RuntimeException e) {
            return 1;                                                       // unreadable note: leave the picture as it is
        }
    }

    /** Finds tag 0x0112 (orientation) in an "Exif" block. */
    private static int orientationIn(byte[] block) {
        if (block.length < 14 || block[0] != 'E' || block[1] != 'x' || block[2] != 'i' || block[3] != 'f') return 1;
        int tiff = 6;                                                       // after "Exif\0\0"
        boolean little = block[tiff] == 'I';
        int ifd = tiff + readInt(block, tiff + 4, little);
        int entries = readShort(block, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > block.length) break;
            if (readShort(block, entry, little) == 0x0112) {
                int value = readShort(block, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    /** The picture turned (and, for the rare mirrored notes, flipped) the way the note says. */
    public static BufferedImage apply(BufferedImage picture, int orientation) {
        if (orientation <= 1 || orientation > 8) return picture;
        int w = picture.getWidth(), h = picture.getHeight();
        boolean swap = orientation >= 5;                                    // 5–8 turn the picture a quarter
        // Where each pixel goes, written out per case (arguments: m00, m10, m01, m11, m02, m12).
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);    // mirrored left-right
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);   // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);    // mirrored top-bottom
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);     // mirrored along the diagonal
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);    // quarter turn clockwise (a phone held upright)
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);   // mirrored along the other diagonal
            default -> new AffineTransform(0, -1, 1, 0, 0, w);   // 8: quarter turn anticlockwise
        };
        BufferedImage turned = new BufferedImage(swap ? h : w, swap ? w : h,
                picture.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = turned.createGraphics();
        g.drawImage(picture, t, null);
        g.dispose();
        return turned;
    }

    private static int readShort(byte[] b, int at, boolean little) {
        return little ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int at, boolean little) {
        return little
                ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24
                : (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }
}
