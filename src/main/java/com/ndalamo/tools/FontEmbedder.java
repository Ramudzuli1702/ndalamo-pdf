package com.ndalamo.tools;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Embeds a missing font for PDF/A (decision 014). PDF/A needs every font inside the
 * file; many PDFs only name Helvetica, Times or Courier and leave the reader to
 * supply them. Those are replaced, where they are defined, by the bundled Liberation
 * fonts, which are made to have exactly the same letter widths. Only the letters the
 * font can use are embedded, and the widths are written from Liberation itself, so
 * text stays where it was and PDF/A checkers find the widths match.
 */
final class FontEmbedder {

    private final PDDocument doc;
    private final Map<String, TrueTypeFont> loaded = new HashMap<>();

    FontEmbedder(PDDocument doc) {
        this.doc = doc;
    }

    /** The bundled Liberation font standing in for a PDF font name, or null if there's no exact twin. */
    static String twin(String pdfFontName) {
        String name = pdfFontName.contains("+") ? pdfFontName.substring(pdfFontName.indexOf('+') + 1) : pdfFontName;
        String n = name.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace(",", "");
        String family;
        if (n.startsWith("helvetica") || n.startsWith("arial") && !n.contains("narrow") && !n.contains("black")) family = "Sans";
        else if (n.startsWith("times")) family = "Serif";
        else if (n.startsWith("courier")) family = "Mono";
        else return null;
        if (n.contains("narrow") || n.contains("condensed")) return null;        // different widths: no twin
        boolean bold = n.contains("bold"), italic = n.contains("italic") || n.contains("oblique");
        String style = bold && italic ? "BoldItalic" : bold ? "Bold" : italic ? "Italic" : "Regular";
        return "Liberation" + family + "-" + style;
    }

    /**
     * Turns a simple font that isn't embedded into an embedded Liberation TrueType font,
     * changing its dictionary in place so every page using it keeps working. False if it
     * has no Liberation twin.
     */
    boolean embed(PDSimpleFont font) throws IOException {
        String twin = twin(font.getName() == null ? "" : font.getName());
        if (twin == null) return false;
        TrueTypeFont ttf = load(twin);
        COSDictionary dict = font.getCOSObject();
        Encoding encoding = font.getEncoding();

        // Which letters the font can show: its codes 32-255, named by its encoding.
        int first = 32, last = 255;
        String[] names = new String[256];
        TTFSubsetter subsetter = new TTFSubsetter(ttf);
        GlyphList glyphs = GlyphList.getAdobeGlyphList();
        COSArray widths = new COSArray();
        float unitsPerEm = ttf.getUnitsPerEm();
        var cmap = ttf.getUnicodeCmapLookup();
        for (int code = first; code <= last; code++) {
            String name = encoding == null ? null : encoding.getName(code);
            if (name == null || name.equals(".notdef")) name = WinAnsiEncoding.INSTANCE.getName(code);
            names[code] = name;
            String unicode = glyphs.toUnicode(name);
            int gid = 0;
            if (unicode != null && !unicode.isEmpty()) {
                int cp = unicode.codePointAt(0);
                subsetter.add(cp);
                gid = cmap.getGlyphId(cp);
            }
            widths.add(COSInteger.get(Math.round(ttf.getAdvanceWidth(gid) * 1000 / unitsPerEm)));
        }
        ByteArrayOutputStream subset = new ByteArrayOutputStream();
        subsetter.writeToStream(subset);
        byte[] program = subset.toByteArray();

        // PDF/A names a subset with six capital letters and "+".
        String tag = tag(dict.hashCode() ^ twin.hashCode());
        String baseFont = tag + "+" + twin;

        // Encoding: WinAnsi, plus the letters where this font's encoding differs from it.
        COSDictionary newEncoding = new COSDictionary();
        newEncoding.setItem(COSName.TYPE, COSName.ENCODING);
        newEncoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
        COSArray differences = new COSArray();
        int run = -2;
        for (int code = first; code <= last; code++) {
            String win = WinAnsiEncoding.INSTANCE.getName(code);
            if (names[code] != null && !names[code].equals(win)) {
                if (code != run + 1) differences.add(COSInteger.get(code));
                differences.add(COSName.getPDFName(names[code]));
                run = code;
            }
        }
        if (differences.size() > 0) newEncoding.setItem(COSName.DIFFERENCES, differences);

        PDStream file = new PDStream(doc, new java.io.ByteArrayInputStream(program), COSName.FLATE_DECODE);
        file.getCOSObject().setInt(COSName.LENGTH1, program.length);

        PDFontDescriptor descriptor = new PDFontDescriptor(new COSDictionary());
        descriptor.setFontName(baseFont);
        descriptor.setNonSymbolic(true);
        descriptor.setSymbolic(false);
        descriptor.setItalic(twin.contains("Italic"));
        descriptor.setForceBold(false);
        var head = ttf.getHeader();
        float scale = 1000 / unitsPerEm;
        descriptor.setFontBoundingBox(new PDRectangle(head.getXMin() * scale, head.getYMin() * scale,
                (head.getXMax() - head.getXMin()) * scale, (head.getYMax() - head.getYMin()) * scale));
        descriptor.setAscent(ttf.getHorizontalHeader().getAscender() * scale);
        descriptor.setDescent(ttf.getHorizontalHeader().getDescender() * scale);
        descriptor.setCapHeight(ttf.getOS2Windows() != null ? ttf.getOS2Windows().getCapHeight() * scale : 700);
        descriptor.setItalicAngle(ttf.getPostScript() != null ? ttf.getPostScript().getItalicAngle() : 0);
        descriptor.setStemV(twin.contains("Bold") ? 140 : 80);
        descriptor.setFontFile2(file);

        dict.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        dict.setName(COSName.BASE_FONT, baseFont);
        dict.setInt(COSName.FIRST_CHAR, first);
        dict.setInt(COSName.LAST_CHAR, last);
        dict.setItem(COSName.WIDTHS, widths);
        dict.setItem(COSName.ENCODING, newEncoding);
        dict.setItem(COSName.FONT_DESC, descriptor.getCOSObject());
        dict.removeItem(COSName.TO_UNICODE);           // the standard names already say what each letter is
        return true;
    }

    private TrueTypeFont load(String name) throws IOException {
        TrueTypeFont cached = loaded.get(name);
        if (cached != null) return cached;
        try (InputStream in = FontEmbedder.class.getResourceAsStream("/fonts/liberation/" + name + ".ttf")) {
            if (in == null) throw new IOException("Missing bundled font " + name);
            TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
            loaded.put(name, ttf);
            return ttf;
        }
    }

    /** Six capital letters from a number, e.g. "QXBRTA". */
    private static String tag(int seed) {
        StringBuilder tag = new StringBuilder();
        long n = seed & 0xFFFFFFFFL;
        for (int i = 0; i < 6; i++) {
            tag.append((char) ('A' + (int) (n % 26)));
            n /= 26;
        }
        return tag.toString();
    }

    /** Closes the Liberation fonts read so far. */
    void close() throws IOException {
        for (TrueTypeFont ttf : loaded.values()) ttf.close();
        loaded.clear();
    }

    /** Is this font inside the file already (Type 3 fonts are always drawn from the file itself)? */
    static boolean embedded(PDFont font) {
        return font.isEmbedded() || font instanceof org.apache.pdfbox.pdmodel.font.PDType3Font;
    }
}
