package com.ndalamo.tools;

import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes PDF to Word use the PDF's own fonts. For each font family in the PDF:
 * - if Ndalamo carries the font (IBM Plex Sans, Noto Sans, Liberation, Dancing Script), the
 *   whole font is embedded in the Word file, so it looks right on any PC and can be typed in;
 * - if Windows has it, it's simply named (fonts that come with Windows aren't embedded);
 * - otherwise the copy embedded in the PDF is embedded in the Word file too (only the letters
 *   the PDF uses, so new text in that font falls back to another);
 * - failing all that, a close Windows font: Courier New, Times New Roman or Arial.
 * Fonts whose makers don't allow embedding (a flag inside the font) are never embedded.
 * Word stores embedded fonts lightly scrambled with a key (ECMA-376 §17.8.1); that's done here.
 */
final class WordFonts {

    /** Fonts Ndalamo ships; their family and style are read from the files themselves. */
    private static final List<String> BUNDLED = List.of(
            "/fonts/IBMPlexSans-Regular.ttf", "/fonts/IBMPlexSans-Bold.ttf", "/fonts/IBMPlexSans-Medium.ttf",
            "/fonts/IBMPlexSans-SemiBold.ttf", "/fonts/NotoSans-Regular.ttf", "/fonts/NotoSans-Bold.ttf",
            "/fonts/DancingScript-Regular.ttf",
            "/fonts/liberation/LiberationSans-Regular.ttf", "/fonts/liberation/LiberationSans-Bold.ttf",
            "/fonts/liberation/LiberationSans-Italic.ttf", "/fonts/liberation/LiberationSans-BoldItalic.ttf",
            "/fonts/liberation/LiberationSerif-Regular.ttf", "/fonts/liberation/LiberationSerif-Bold.ttf",
            "/fonts/liberation/LiberationSerif-Italic.ttf", "/fonts/liberation/LiberationSerif-BoldItalic.ttf",
            "/fonts/liberation/LiberationMono-Regular.ttf", "/fonts/liberation/LiberationMono-Bold.ttf",
            "/fonts/liberation/LiberationMono-Italic.ttf", "/fonts/liberation/LiberationMono-BoldItalic.ttf");

    /** family (lower case) → style → resource path */
    private static volatile Map<String, Map<Style, String>> bundled;
    private static volatile Map<String, String> installed;     // squashed name → Windows' name

    enum Style {
        REGULAR("embedRegular"), BOLD("embedBold"), ITALIC("embedItalic"), BOLD_ITALIC("embedBoldItalic");

        final String element;

        Style(String element) {
            this.element = element;
        }

        static Style of(boolean bold, boolean italic) {
            return bold ? (italic ? BOLD_ITALIC : BOLD) : (italic ? ITALIC : REGULAR);
        }
    }

    /** What's known about one family in the PDF. */
    private static final class Family {
        final Map<Style, byte[]> pdfCopies = new LinkedHashMap<>();
        boolean fixedPitch, serif;
    }

    private final Map<String, Family> inPdf = new LinkedHashMap<>();       // by family as written (case kept)
    private final Map<String, Map<Style, byte[]>> toEmbed = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();

    /** Looks at the fonts on the given pages of the PDF (numbered from 1, as the tool's page lists are). */
    static WordFonts of(PDDocument doc, List<Integer> pages) {
        WordFonts fonts = new WordFonts();
        Set<PDFont> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int page : pages) {
            if (page < 1 || page > doc.getNumberOfPages()) continue;
            fonts.collect(doc.getPage(page - 1).getResources(), seen, 0);
        }
        return fonts;
    }

    private void collect(PDResources resources, Set<PDFont> seen, int depth) {
        if (resources == null || depth > 4) return;
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                if (font == null || !seen.add(font)) continue;
                String pdfName = font.getName() == null ? "" : font.getName();
                String family = PageText.family(font, pdfName);
                Family entry = inPdf.computeIfAbsent(family, k -> new Family());
                PDFontDescriptor descriptor = descriptorOf(font);
                if (descriptor != null) {
                    entry.fixedPitch |= descriptor.isFixedPitch();
                    entry.serif |= descriptor.isSerif();
                }
                byte[] program = trueTypeProgram(descriptor);
                if (program != null && embeddingAllowed(program)) {
                    entry.pdfCopies.putIfAbsent(Style.of(PageText.isBold(font, pdfName), PageText.isItalic(font, pdfName)), program);
                }
            } catch (IOException | RuntimeException e) {
                // a font that can't be read: its words still come out, in a fallback font
            }
        }
        // Forms (XObjects) can carry fonts of their own.
        for (COSName name : resources.getXObjectNames()) {
            try {
                if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    collect(form.getResources(), seen, depth + 1);
                }
            } catch (IOException | RuntimeException e) {
                // skip
            }
        }
    }

    /**
     * The family name to write in Word for a family read from the PDF, deciding (once) whether
     * its font gets embedded or replaced.
     */
    String name(String family, boolean bold, boolean italic) {
        Style style = Style.of(bold, italic);
        String key = family + "|" + style;
        String known = names.get(key);
        if (known != null) return known;
        String chosen = choose(family, style);
        names.put(key, chosen);
        return chosen;
    }

    private String choose(String pdfFamily, Style style) {
        // Names are compared without spaces or punctuation: a PDF often calls IBM Plex Sans "IBMPlexSans".
        String key = squash(pdfFamily);
        String family = pdfFamily;
        for (String ourName : bundledNames().keySet()) if (squash(ourName).equals(key)) family = bundledNames().get(ourName);
        Map<Style, String> ours = bundled().get(family.toLowerCase(Locale.ROOT));
        if (ours != null) {
            // Embed the style asked for if we have it (Word makes up bold or italic from the regular otherwise).
            String file = ours.containsKey(style) ? ours.get(style) : ours.get(Style.REGULAR);
            Style as = ours.containsKey(style) ? style : Style.REGULAR;
            if (file != null) {
                byte[] bytes = resource(file);
                if (bytes != null) {
                    toEmbed.computeIfAbsent(family, k -> new LinkedHashMap<>()).putIfAbsent(as, bytes);
                    return family;
                }
            }
        }
        String windowsName = installed().get(key);
        if (windowsName != null) return windowsName;
        Family pdf = inPdf.get(pdfFamily);
        if (pdf != null && !pdf.pdfCopies.isEmpty()) {
            byte[] copy = pdf.pdfCopies.containsKey(style) ? pdf.pdfCopies.get(style) : pdf.pdfCopies.values().iterator().next();
            Style as = pdf.pdfCopies.containsKey(style) ? style : pdf.pdfCopies.keySet().iterator().next();
            // Word and LibreOffice find an embedded font by the name inside it, so only a copy that names
            // itself properly is any use (a subset often calls itself "ABCDEF+Name").
            String inside = familyInside(copy);
            if (inside != null) {
                toEmbed.computeIfAbsent(inside, k -> new LinkedHashMap<>()).putIfAbsent(as, copy);
                return inside;
            }
        }
        if (pdf != null && pdf.fixedPitch) return "Courier New";
        if (pdf != null && pdf.serif) return "Times New Roman";
        String lower = family.toLowerCase(Locale.ROOT);
        if (lower.contains("mono") || lower.contains("courier") || lower.contains("code")) return "Courier New";
        if (lower.contains("serif") && !lower.contains("sans") || lower.contains("times") || lower.contains("roman")) return "Times New Roman";
        return "Arial";
    }

    /** True if any font will be embedded. */
    boolean embedsAny() {
        return !toEmbed.isEmpty();
    }

    /**
     * Adds the chosen fonts to a Word file written by POI: the font files (scrambled as Word
     * expects), the font table listing them, and the setting that tells Word fonts are embedded.
     */
    byte[] embedInto(byte[] docx) throws IOException {
        if (toEmbed.isEmpty()) return docx;
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) parts.put(e.getName(), zip.readAllBytes());
        }

        StringBuilder fontEntries = new StringBuilder();
        StringBuilder fontRels = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Map<Style, byte[]>> family : toEmbed.entrySet()) {
            Family pdf = inPdf.get(family.getKey());
            String genre = pdf != null && pdf.fixedPitch ? "modern" : pdf != null && pdf.serif ? "roman" : "swiss";
            fontEntries.append("<w:font w:name=\"").append(xml(family.getKey())).append("\">")
                    .append("<w:charset w:val=\"00\"/><w:family w:val=\"").append(genre).append("\"/>")
                    .append("<w:pitch w:val=\"").append(pdf != null && pdf.fixedPitch ? "fixed" : "variable").append("\"/>");
            for (Style style : Style.values()) {
                byte[] font = family.getValue().get(style);
                if (font == null) continue;
                n++;
                String id = "rIdNdalamoFont" + n;
                String key = "{" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT) + "}";
                parts.put("word/fonts/font" + n + ".odttf", scramble(font, key));
                fontEntries.append("<w:").append(style.element).append(" r:id=\"").append(id)
                        .append("\" w:fontKey=\"").append(key).append("\"/>");
                fontRels.append("<Relationship Id=\"").append(id)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/font\" Target=\"fonts/font")
                        .append(n).append(".odttf\"/>");
            }
            fontEntries.append("</w:font>");
        }

        // The font table: POI may or may not have written one.
        String table = text(parts.get("word/fontTable.xml"));
        if (table == null) {
            table = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:fonts xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"></w:fonts>";
            addRelationship(parts, "word/_rels/document.xml.rels", "rIdNdalamoFontTable",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable", "fontTable.xml");
            addOverride(parts, "/word/fontTable.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml");
        } else {
            for (String family : toEmbed.keySet()) {
                // A plain entry for the same family would hide ours.
                table = table.replaceAll("<w:font w:name=\"" + java.util.regex.Pattern.quote(xml(family)) + "\">.*?</w:font>", "");
            }
            if (!table.contains("xmlns:r=")) table = table.replaceFirst("<w:fonts ", "<w:fonts xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ");
        }
        table = table.replace("</w:fonts>", fontEntries + "</w:fonts>");
        parts.put("word/fontTable.xml", table.getBytes(StandardCharsets.UTF_8));

        String rels = text(parts.get("word/_rels/fontTable.xml.rels"));
        if (rels == null) rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                 + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
        parts.put("word/_rels/fontTable.xml.rels", rels.replace("</Relationships>", fontRels + "</Relationships>").getBytes(StandardCharsets.UTF_8));

        String types = text(parts.get("[Content_Types].xml"));
        if (!types.contains("Extension=\"odttf\"")) {
            types = types.replaceFirst("<Default ", "<Default Extension=\"odttf\" ContentType=\"application/vnd.openxmlformats-officedocument.obfuscatedFont\"/><Default ");
        }
        parts.put("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8));

        String settings = text(parts.get("word/settings.xml"));
        if (settings != null && !settings.contains("embedTrueTypeFonts")) {
            // Its place in the settings is fixed: right after zoom (and the few elements before it), before the rest.
            java.util.regex.Matcher zoom = java.util.regex.Pattern.compile("<w:zoom[^>]*/>|<w:zoom[^>]*>.*?</w:zoom>").matcher(settings);
            java.util.regex.Matcher empty = java.util.regex.Pattern.compile("<w:settings([^>]*?)/>").matcher(settings);
            if (empty.find()) {
                // POI writes an empty <w:settings .../>: open it up.
                settings = settings.substring(0, empty.start()) + "<w:settings" + empty.group(1) + "><w:embedTrueTypeFonts/></w:settings>"
                           + settings.substring(empty.end());
            } else if (zoom.find()) {
                settings = settings.substring(0, zoom.end()) + "<w:embedTrueTypeFonts/>" + settings.substring(zoom.end());
            } else {
                settings = settings.replaceFirst("(<w:settings[^>]*>)", "$1<w:embedTrueTypeFonts/>");
            }
            parts.put("word/settings.xml", settings.getBytes(StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(docx.length + 1_000_000);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * Word's scrambling of embedded fonts: the first 32 bytes are XORed with the key, which is
     * the GUID's 16 bytes taken from its text backwards.
     */
    static byte[] scramble(byte[] font, String guidWithBraces) {
        String hex = guidWithBraces.replaceAll("[{}-]", "");
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(30 - 2 * i, 32 - 2 * i), 16);
        byte[] out = Arrays.copyOf(font, font.length);
        for (int i = 0; i < 32 && i < out.length; i++) out[i] ^= key[i % 16];
        return out;
    }

    private static void addRelationship(Map<String, byte[]> parts, String relsPart, String id, String type, String target) {
        String rels = text(parts.get(relsPart));
        if (rels == null || rels.contains("Target=\"" + target + "\"")) return;
        parts.put(relsPart, rels.replace("</Relationships>", "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + target
                                                            + "\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
    }

    private static void addOverride(Map<String, byte[]> parts, String partName, String contentType) {
        String types = text(parts.get("[Content_Types].xml"));
        if (types == null || types.contains("PartName=\"" + partName + "\"")) return;
        parts.put("[Content_Types].xml", types.replace("</Types>", "<Override PartName=\"" + partName + "\" ContentType=\""
                                                                   + contentType + "\"/></Types>").getBytes(StandardCharsets.UTF_8));
    }

    private static PDFontDescriptor descriptorOf(PDFont font) {
        if (font instanceof PDType0Font type0 && type0.getDescendantFont() != null) return type0.getDescendantFont().getFontDescriptor();
        return font.getFontDescriptor();
    }

    /** The TrueType font file embedded in the PDF for this font, or null (not embedded, or another kind). */
    private static byte[] trueTypeProgram(PDFontDescriptor descriptor) throws IOException {
        if (descriptor == null) return null;
        PDStream stream = descriptor.getFontFile2();
        if (stream == null) return null;
        try (InputStream in = stream.createInputStream()) {
            return in.readAllBytes();
        }
    }

    /** False if the font says it mustn't be embedded (OS/2 "restricted licence" flag). */
    private static boolean embeddingAllowed(byte[] font) {
        try (TrueTypeFont ttf = new TTFParser(true).parse(new RandomAccessReadBuffer(font))) {
            OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            return os2 == null || (os2.getFsType() & 0x000F) != 0x0002;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Lower case and without spaces or punctuation: "IBM Plex Sans" and "IBMPlexSans" both become "ibmplexsans". */
    static String squash(String family) {
        return family.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** The family named inside a font file, if it's a usable name (not a subset tag). */
    private static String familyInside(byte[] font) {
        try (TrueTypeFont ttf = new TTFParser(true).parse(new RandomAccessReadBuffer(font))) {
            String family = ttf.getNaming() == null ? null : ttf.getNaming().getFontFamily();
            return family == null || family.isBlank() || family.contains("+") ? null : family.strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static volatile Map<String, String> bundledNames;

    /** Our fonts' families as lower case → as written ("ibm plex sans" → "IBM Plex Sans"). */
    private static Map<String, String> bundledNames() {
        if (bundledNames == null) bundled();
        return bundledNames;
    }

    private static Map<String, Map<Style, String>> bundled() {
        if (bundled != null) return bundled;
        Map<String, Map<Style, String>> map = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (String path : BUNDLED) {
            byte[] bytes = resource(path);
            if (bytes == null) continue;
            try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(bytes))) {
                String family = ttf.getNaming().getFontFamily();
                String sub = ttf.getNaming().getFontSubFamily() == null ? "" : ttf.getNaming().getFontSubFamily().toLowerCase(Locale.ROOT);
                Style style = Style.of(sub.contains("bold"), sub.contains("italic") || sub.contains("oblique"));
                map.computeIfAbsent(family.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>()).putIfAbsent(style, path);
                names.putIfAbsent(family.toLowerCase(Locale.ROOT), family);
            } catch (IOException | RuntimeException e) {
                // a damaged bundled font: just not offered
            }
        }
        bundledNames = names;
        bundled = map;
        return map;
    }

    private static Map<String, String> installed() {
        if (installed != null) return installed;
        Map<String, String> names = new java.util.HashMap<>();
        try {
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) {
                names.putIfAbsent(squash(family), family);
            }
        } catch (RuntimeException | Error e) {
            // no font list (unusual): everything not ours counts as missing
        }
        installed = names;
        return names;
    }

    private static byte[] resource(String path) {
        try (InputStream in = WordFonts.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
