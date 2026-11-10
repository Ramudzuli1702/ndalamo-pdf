package com.ndalamo.tools;

import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the words on a PDF page with how they look: font, size, bold, italic and
 * colour (SPEC §4.1 step 1). Positions are in points from the top-left of the page,
 * with y at the baseline. Used by PDF to Word. Only reads; the document is not changed.
 */
final class PageText {

    /** One word, or part of a word where its look changes (e.g. a bold first letter). */
    record TextWord(String text, float left, float right, float baseline, float size,
                    String font, boolean bold, boolean italic, int colour, boolean underline)
            implements TableFinder.Positioned {

        /** Roughly the height of capital letters above the baseline, like PDFBox measures plain words. */
        @Override public float top() { return baseline - size * 0.7f; }
        @Override public float bottom() { return baseline; }

        /** Same font, size, weight, slant, colour and underline: can go in one Word run. */
        boolean looksLike(TextWord other) {
            return font.equals(other.font) && Math.abs(size - other.size) < 0.6f && bold == other.bold
                    && italic == other.italic && colour == other.colour && underline == other.underline;
        }

        /** The same word with different text, e.g. a hyphenated word joined again. */
        TextWord withText(String newText, float newRight) {
            return new TextWord(newText, left, newRight, baseline, size, font, bold, italic, colour, underline);
        }

        TextWord underlined() {
            return new TextWord(text, left, right, baseline, size, font, bold, italic, colour, true);
        }
    }

    private PageText() {}

    /** The words on a page (0-based index). */
    static List<TextWord> words(PDDocument doc, int pageIndex) throws IOException {
        List<TextWord> words = new ArrayList<>();
        Map<TextPosition, Integer> colours = new IdentityHashMap<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            {
                // The text stripper skips colour instructions by default; without these every word reads as black.
                addOperator(new SetNonStrokingColorSpace(this));
                addOperator(new SetNonStrokingColor(this));
                addOperator(new SetNonStrokingColorN(this));
                addOperator(new SetNonStrokingDeviceRGBColor(this));
                addOperator(new SetNonStrokingDeviceGrayColor(this));
                addOperator(new SetNonStrokingDeviceCMYKColor(this));
            }

            @Override
            protected void processTextPosition(TextPosition letter) {
                // The colour is only known while the page is being drawn, so note it per letter now.
                colours.put(letter, rgb(getGraphicsState().getNonStrokingColor()));
                super.processTextPosition(letter);
            }

            @Override
            protected void writeString(String text, List<TextPosition> letters) {
                // This can be a whole line: PDFBox only splits where it adds spaces itself, and many PDF
                // makers (LibreOffice, Word) write real spaces. So split at spaces here, and also where
                // the look changes partway through a word.
                int start = 0;
                for (int i = 0; i <= letters.size(); i++) {
                    boolean space = i < letters.size() && letters.get(i).getUnicode().isBlank();
                    boolean lookChanges = i < letters.size() && i > start && !sameLook(letters.get(start), letters.get(i), colours);
                    if (i == letters.size() || space || lookChanges) {
                        if (i > start) addWord(words, letters.subList(start, i), colours);
                        start = space ? i + 1 : i;
                    }
                }
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return words;
    }

    private static void addWord(List<TextWord> words, List<TextPosition> letters, Map<TextPosition, Integer> colours) {
        StringBuilder text = new StringBuilder();
        for (TextPosition letter : letters) text.append(plainLetters(letter.getUnicode()));
        if (text.toString().isBlank()) return;
        TextPosition first = letters.get(0), last = letters.get(letters.size() - 1);
        PDFont font = first.getFont();
        String name = font == null || font.getName() == null ? "" : font.getName();
        words.add(new TextWord(text.toString().strip(),
                first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(), first.getYDirAdj(),
                Math.round(sizeInPoints(first) * 2) / 2f,              // to the nearest half point, like Word
                family(font, name), isBold(font, name), isItalic(font, name),
                colours.getOrDefault(first, 0), false));
    }

    /**
     * PDFs draw underlines as separate lines, so a word counts as underlined when a
     * horizontal line runs just below its baseline, under most of the word. Lines
     * that meet a vertical line are table borders, not underlines.
     */
    static List<TextWord> markUnderlines(List<TextWord> words, List<PageContent.Rule> rules) {
        List<PageContent.Rule> horizontal = new ArrayList<>(), vertical = new ArrayList<>();
        for (PageContent.Rule rule : rules) (rule.horizontal() ? horizontal : vertical).add(rule);
        horizontal.removeIf(h -> vertical.stream().anyMatch(v -> v.position() >= h.from() - 2 && v.position() <= h.to() + 2
                && h.position() >= v.from() - 2 && h.position() <= v.to() + 2));
        if (horizontal.isEmpty()) return words;

        List<TextWord> marked = new ArrayList<>(words.size());
        for (TextWord word : words) {
            boolean under = false;
            for (PageContent.Rule rule : horizontal) {
                float below = rule.position() - word.baseline();
                float covered = Math.min(rule.to(), word.right()) - Math.max(rule.from(), word.left());
                if (below >= 0 && below <= word.size() * 0.35f && covered >= (word.right() - word.left()) * 0.7f) {
                    under = true;
                    break;
                }
            }
            marked.add(under ? word.underlined() : word);
        }
        return marked;
    }

    private static boolean sameLook(TextPosition a, TextPosition b, Map<TextPosition, Integer> colours) {
        return a.getFont() == b.getFont() && Math.abs(sizeInPoints(a) - sizeInPoints(b)) < 0.6f
                && colours.getOrDefault(a, 0).equals(colours.getOrDefault(b, 0));
    }

    private static int rgb(PDColor colour) {
        try {
            return colour == null ? 0 : colour.toRGB() & 0xFFFFFF;
        } catch (IOException | UnsupportedOperationException e) {
            return 0;                                            // unusual colour spaces: black is the safe guess
        }
    }

    /**
     * Typesetting ligatures ("ﬁ" as one character) as the letters they stand for, so the text can
     * be searched and edited ("first", not "ﬁrst"). Only the ligature block is touched.
     */
    static String plainLetters(String text) {
        if (text == null) return "";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'ﬀ' && c <= 'ﬆ') return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        }
        return text;
    }

    /**
     * A letter's size in points. PDFBox's getFontSizeInPt() drops the fraction (10.5 pt comes
     * back as 10), so the size is taken from the text's scaling, which also covers PDFs that set
     * size 1 and scale the text up.
     */
    static float sizeInPoints(TextPosition letter) {
        float size = Math.abs(letter.getYScale());
        if (size <= 0.01f) size = Math.abs(letter.getXScale());
        return size > 0.01f ? size : letter.getFontSizeInPt();
    }

    /** Family names already read, per font (reading a font's name table for every word would be slow). */
    private static final Map<PDFont, String> FAMILIES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The font's family for Word: the name the font program inside the PDF gives itself
     * ("IBM Plex Sans"), which Word matches against installed and embedded fonts; otherwise one
     * made from the PDF's own label for it (see familyName).
     */
    static String family(PDFont font, String pdfName) {
        if (font == null) return familyName(pdfName);
        return FAMILIES.computeIfAbsent(font, f -> {
            String embedded = embeddedFamily(f);
            return embedded != null ? embedded : familyName(pdfName);
        });
    }

    /** The family named inside an embedded TrueType font ("IBM Plex Sans"), or null if there's none to read. */
    static String embeddedFamily(PDFont font) {
        try {
            org.apache.fontbox.ttf.TrueTypeFont ttf = null;
            if (font instanceof org.apache.pdfbox.pdmodel.font.PDType0Font type0
                && type0.getDescendantFont() instanceof org.apache.pdfbox.pdmodel.font.PDCIDFontType2 cid) {
                ttf = cid.getTrueTypeFont();
            } else if (font instanceof org.apache.pdfbox.pdmodel.font.PDTrueTypeFont trueType && trueType.isEmbedded()) {
                ttf = trueType.getTrueTypeFont();
            }
            if (ttf == null || ttf.getNaming() == null) return null;
            String family = ttf.getNaming().getFontFamily();
            return family == null || family.isBlank() || family.contains("+") ? null : family.strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static boolean isBold(PDFont font, String name) {
        String lower = name.toLowerCase();
        if (lower.contains("bold") || lower.contains("black") || lower.contains("heavy") || lower.contains("semibold")
                || lower.contains("demi")) return true;
        PDFontDescriptor descriptor = font == null ? null : font.getFontDescriptor();
        return descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
    }

    static boolean isItalic(PDFont font, String name) {
        String lower = name.toLowerCase();
        if (lower.contains("italic") || lower.contains("oblique")) return true;
        PDFontDescriptor descriptor = font == null ? null : font.getFontDescriptor();
        return descriptor != null && (descriptor.isItalic() || descriptor.getItalicAngle() != 0);
    }

    /**
     * The font's family as Word knows it: "ABCDEF+TimesNewRomanPS-BoldMT" → "Times New Roman".
     * PDFs name fonts in many ways; the common ones are mapped, others get spaces between words.
     */
    static String familyName(String pdfName) {
        String name = pdfName.contains("+") ? pdfName.substring(pdfName.indexOf('+') + 1) : pdfName;  // subset tag
        name = name.split("[-,]")[0];                                      // style part: "-Bold", ",Italic"
        name = name.replaceAll("(PSMT|PS|MT)$", "");
        String key = name.toLowerCase().replace(" ", "");
        return switch (key) {
            case "", "helvetica", "arial", "arialmt" -> "Arial";
            case "times", "timesroman", "timesnewroman" -> "Times New Roman";
            case "courier", "couriernew" -> "Courier New";
            case "symbol" -> "Symbol";
            default -> name.replaceAll("([a-z])([A-Z])", "$1 $2");           // "BookAntiqua" → "Book Antiqua"
        };
    }
}
