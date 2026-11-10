package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF to Word keeps the PDF's look: its fonts (embedded in the Word file), sizes, line
 * spacing, backgrounds, quote bars, dividing lines and drawings.
 */
class PdfToWordFidelityTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    /** Something to draw on a page with the bundled IBM Plex Sans (embedded as a subset, as most PDFs do). */
    private interface Drawing {
        void draw(PDPageContentStream cs, PDFont regular, PDFont bold) throws Exception;
    }

    private Path pdf(String name, Drawing drawing) throws Exception {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont regular, bold;
            try (InputStream r = getClass().getResourceAsStream("/fonts/IBMPlexSans-Regular.ttf");
                 InputStream b = getClass().getResourceAsStream("/fonts/IBMPlexSans-Bold.ttf")) {
                regular = PDType0Font.load(doc, r);
                bold = PDType0Font.load(doc, b);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawing.draw(cs, regular, bold);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, float x, float yFromTop, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, 842 - yFromTop);
        cs.showText(text);
        cs.endText();
    }

    /** Lines of body text down the page, 15.2 points apart (10.5 pt text, 1.45 spacing). */
    private static float lines(PDPageContentStream cs, PDFont font, float x, float top, String... lines) throws Exception {
        float y = top;
        for (String line : lines) {
            text(cs, font, 10.5f, x, y, line);
            y += 15.2f;
        }
        return y;
    }

    private Map<String, byte[]> convert(Path pdf) throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put(PdfToWordTool.PAGE_BREAKS, true);
        options.put(PdfToWordTool.PAGES, "");
        FileResult result = new PdfToWordTool().run(List.of(pdf), options, sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(result.getOutput()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) parts.put(e.getName(), zip.readAllBytes());
        }
        return parts;
    }

    private static String part(Map<String, byte[]> parts, String name) {
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }

    @Test
    void thePdfsFontIsEmbeddedUnderItsRealName() throws Exception {
        Path file = pdf("Plex.pdf", (cs, regular, bold) -> {
            text(cs, bold, 22, 64, 80, "Project Specification");
            lines(cs, regular, 64, 120, "A free, fully offline desktop app for working with PDFs.");
        });
        Map<String, byte[]> parts = convert(file);

        String document = part(parts, "word/document.xml");
        assertTrue(document.contains("w:ascii=\"IBM Plex Sans\""), "the runs name the family as Word knows it, not \"IBMPlex Sans\"");
        String table = part(parts, "word/fontTable.xml");
        assertTrue(table.contains("<w:font w:name=\"IBM Plex Sans\">") && table.contains("w:embedRegular") && table.contains("w:embedBold"));
        assertTrue(part(parts, "word/settings.xml").contains("<w:embedTrueTypeFonts/>"));
        assertTrue(part(parts, "[Content_Types].xml").contains("Extension=\"odttf\""));

        // The embedded copy is the whole bundled font (not the PDF's subset), scrambled as Word expects.
        Matcher key = Pattern.compile("<w:embedRegular r:id=\"([^\"]+)\" w:fontKey=\"([^\"]+)\"/>").matcher(table);
        assertTrue(key.find());
        String rels = part(parts, "word/_rels/fontTable.xml.rels");
        Matcher target = Pattern.compile("Id=\"" + key.group(1) + "\"[^>]*Target=\"([^\"]+)\"").matcher(rels);
        assertTrue(target.find());
        byte[] stored = parts.get("word/" + target.group(1));
        byte[] original;
        try (InputStream in = getClass().getResourceAsStream("/fonts/IBMPlexSans-Regular.ttf")) {
            original = in.readAllBytes();
        }
        assertArrayEquals(original, WordFonts.scramble(stored, key.group(2)), "scrambling twice gives the font back");
    }

    @Test
    void fractionalSizesAndExactLineSpacingAreKept() throws Exception {
        Path file = pdf("Body.pdf", (cs, regular, bold) -> lines(cs, regular, 64, 100,
                "The first line of a paragraph that runs across the page from margin to margin,",
                "a second line set the same distance below it as the first, and then a third",
                "line, so the paragraph has a clear line spacing."));
        String document = part(convert(file), "word/document.xml");

        assertTrue(document.contains("<w:sz w:val=\"21\"/>"), "10.5 pt stays 10.5 pt (PDFBox's own size call rounds it down)");
        assertTrue(document.contains("w:line=\"304\" w:lineRule=\"exact\""), "lines exactly 15.2 pt apart, as in the PDF");
    }

    @Test
    void backgroundsBarsAndDividersBecomeWordFormatting() throws Exception {
        Path file = pdf("Shapes.pdf", (cs, regular, bold) -> {
            // A quote with a grey bar beside it.
            cs.setNonStrokingColor(new Color(0xD5, 0xD8, 0xDF));
            cs.addRect(64, 842 - 128, 2.5f, 32);
            cs.fill();
            lines(cs, regular, 80, 108, "A quotation beside a grey bar, set in from the margin.", "Its second line.");
            // A dividing line across the page.
            cs.setStrokingColor(new Color(0xD5, 0xD8, 0xDF));
            cs.setLineWidth(0.8f);
            cs.moveTo(64, 842 - 160);
            cs.lineTo(531, 842 - 160);
            cs.stroke();
            // A code block: a light grey box behind two lines.
            cs.setNonStrokingColor(new Color(0xF4, 0xF5, 0xF7));
            cs.addRect(64, 842 - 238, 467, 40);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            lines(cs, regular, 72, 214, "first line of code", "second line of code");
            // Inline code: a small box behind one word.
            cs.setNonStrokingColor(new Color(0xF4, 0xF5, 0xF7));
            cs.addRect(150, 842 - 283, 40, 12);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            text(cs, regular, 10.5f, 64, 280, "Call the");
            text(cs, regular, 10.5f, 152, 280, "run()");
            text(cs, regular, 10.5f, 194, 280, "method to start.");
        });
        String document = part(convert(file), "word/document.xml");

        assertTrue(document.contains("<w:left w:val=\"single\" w:sz=\"20\" w:space=\"14\" w:color=\"D5D8DF\"/>"),
                "the quote bar: a 2.5 pt grey left border, as far from the text as in the PDF; found "
                + Pattern.compile("<w:left [^>]*/>").matcher(document).results().map(m -> m.group()).toList());
        assertTrue(Pattern.compile("<w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"0\" w:color=\"D5D8DF\"/>").matcher(document).find(),
                "the dividing line: a 0.8 pt grey line");
        assertTrue(document.contains("w:fill=\"F4F5F7\""), "the code block and the inline code are shaded");
        Matcher shadedParagraphs = Pattern.compile("<w:pPr>(?:(?!</w:pPr>).)*<w:shd [^>]*w:fill=\"F4F5F7\"").matcher(document);
        int blocks = 0;
        while (shadedParagraphs.find()) blocks++;
        assertEquals(2, blocks, "both lines of the code block are shaded paragraphs");
        assertTrue(Pattern.compile("<w:rPr>(?:(?!</w:rPr>).)*<w:shd [^>]*w:fill=\"F4F5F7\"(?:(?!</w:r>).)*<w:t[^>]*>run\\(\\)").matcher(document).find(),
                "only run() is shaded in its line");
    }

    @Test
    void wordsMeetWithoutAStraySpaceAndRaggedTextIsntJustified() throws Exception {
        Path file = pdf("Spacing.pdf", (cs, regular, bold) -> {
            text(cs, regular, 10.5f, 64, 100, "Name:");
            float x = 64 + regular.getStringWidth("Name: ") / 1000 * 10.5f;
            text(cs, bold, 10.5f, x, 100, "Ndalamo PDF");
            x += bold.getStringWidth("Ndalamo PDF") / 1000 * 10.5f;
            text(cs, regular, 10.5f, x, 100, ". Window title and header show the name.");
            // Two lines of ragged text whose first line happens to be the longest on the page.
            lines(cs, regular, 64, 140, "Free for now. No licensing, tiers or paywalls. But keep tools behind a clean interface so gating",
                    "is easy later.");
        });
        String document = part(convert(file), "word/document.xml");

        assertTrue(Pattern.compile(">Ndalamo PDF</w:t>.*?<w:t[^>]*>\\. Window").matcher(document).find(),
                "\"PDF\" in bold is followed directly by the plain \".\"");
        assertFalse(document.contains("<w:jc w:val=\"both\"/>"), "ragged text stays left-aligned");
    }

    @Test
    void aDrawnIconBecomesAPicture() throws Exception {
        Path file = pdf("Icon.pdf", (cs, regular, bold) -> {
            // A green circle with a white tick, drawn with curves and slanted lines.
            float cx = 80, cy = 842 - 100, r = 10, k = 0.5523f * r;
            cs.setNonStrokingColor(new Color(0x1E, 0x8A, 0x4C));
            cs.moveTo(cx + r, cy);
            cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
            cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
            cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
            cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
            cs.fill();
            cs.setStrokingColor(Color.WHITE);
            cs.setLineWidth(2);
            cs.moveTo(cx - 5, cy);
            cs.lineTo(cx - 1, cy - 4);
            cs.lineTo(cx + 5, cy + 4);
            cs.stroke();
            cs.setNonStrokingColor(Color.BLACK);
            lines(cs, regular, 64, 140, "The text under the icon stays text.");
        });
        Map<String, byte[]> parts = convert(file);
        assertEquals(1, parts.keySet().stream().filter(n -> n.startsWith("word/media/")).count(), "the icon is one picture");
        assertTrue(part(parts, "word/document.xml").contains("The text under the icon stays text."));
    }
}
