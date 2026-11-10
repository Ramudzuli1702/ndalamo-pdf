package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PDF to Markdown. The Markdown made is read back with commonmark to check it means what it should. */
class PdfToMarkdownToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Map<String, Object> options() {
        Map<String, Object> options = new HashMap<>();
        options.put(PdfToMarkdownTool.PICTURES, true);
        options.put(PdfToMarkdownTool.OCR, true);
        options.put(PdfToMarkdownTool.PAGES, "");
        return options;
    }

    private String convert(Path pdf) throws Exception {
        FileResult result = new PdfToMarkdownTool().run(List.of(pdf), options(), sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        return Files.readString(result.getOutput());
    }

    private static Node parse(String markdown) {
        return Parser.builder().extensions(List.of(TablesExtension.create())).build().parse(markdown);
    }

    /** Every node of a kind in the parsed Markdown. */
    private static <T extends Node> List<T> all(Node root, Class<T> kind) {
        List<T> found = new ArrayList<>();
        Node node = root.getFirstChild();
        java.util.ArrayDeque<Node> todo = new java.util.ArrayDeque<>();
        if (node != null) todo.push(root);
        while (!todo.isEmpty()) {
            Node n = todo.pop();
            if (kind.isInstance(n)) found.add(kind.cast(n));
            for (Node c = n.getLastChild(); c != null; c = c.getPrevious()) todo.push(c);
        }
        return found;
    }

    @Test
    void headingsListsAndEmphasisBecomeMarkdown() throws Exception {
        Path pdf = TestFiles.structuredPdf(dir, "Rules.pdf");
        byte[] before = TestFiles.bytes(pdf);

        String md = convert(pdf);

        assertTrue(md.startsWith("# Club rules\n"), md);
        assertTrue(md.contains("\n## Membership\n"), md);
        assertTrue(md.contains("- Bring your identity document.\n- Pay the joining fee of R 50."), md);
        assertTrue(md.contains("3. The chairperson opens the meeting.\n    - a) Apologies are read out.\n4. The treasurer reports."), md);

        Node doc = parse(md);
        assertEquals(3, all(doc, Heading.class).size(), "Club rules, Membership, Meetings");
        assertEquals(1, all(doc, OrderedList.class).size());
        assertEquals(3, all(doc, OrderedList.class).get(0).getMarkerStartNumber(), "the list still starts at 3");
        assertFalse(all(doc, BulletList.class).isEmpty());
        assertArrayEquals(before, TestFiles.bytes(pdf));
    }

    @Test
    void tablesBecomePipeTables() throws Exception {
        String[][] stock = {{"Item", "Count", "Price"}, {"Printer paper", "12", "1 250.00"}, {"Toner", "3", "800.50"}};
        String md = convert(TestFiles.ruledTablePdf(dir, "Stock.pdf", stock));

        assertTrue(md.contains("| Item | Count | Price |\n| --- | --- | --- |\n| Printer paper | 12 | 1 250.00 |"), md);
        assertEquals(1, all(parse(md), TableBlock.class).size(), "a real Markdown table");
    }

    @Test
    void picturesAreSavedNextToTheMarkdownAndLinked() throws Exception {
        Path pdf = TestFiles.picturesPdf(dir, "Report.pdf");

        String md = convert(pdf);

        Path folder = dir.resolve("Report_markdown_pictures");
        assertTrue(Files.isDirectory(folder));
        assertArrayEquals(Files.readAllBytes(dir.resolve("photo.jpg")), Files.readAllBytes(folder.resolve("picture-2.jpg")),
                "the photo is saved exactly as it was in the PDF");
        assertTrue(md.contains("![](<Report_markdown_pictures/picture-2.jpg>)"), md);
        assertTrue(md.indexOf("picture-2.jpg") > md.indexOf("finished in October")
                && md.indexOf("picture-2.jpg") < md.indexOf("first heavy rain"), "the photo sits between the right paragraphs");
    }

    @Test
    void wrappedLinesBecomeOneParagraphEach() throws Exception {
        String md = convert(TestFiles.letterPdf(dir, "Letter.pdf"));

        assertTrue(md.contains("\n\nI am writing to apply for the bursary for the next academic year. I am a second-year "
                + "undergraduate student in information technology"), md);
        assertTrue(md.contains("My results are *attached* to this letter"), "italics kept: " + md);
    }

    /**
     * A two-page CV-like PDF with the things real PDFs do: a hidden copy of some text clipped
     * out of view (as pages put together by pypdf have), a heading with wide letter spacing
     * drawn in two pieces, lines starting with bold labels, a web link, and a second page.
     */
    private Path cvPdf() throws Exception {
        Path file = dir.resolve("Cv.pdf");
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.saveGraphicsState();                                     // the hidden copy: clipped to a corner
                c.addRect(0, 0, 10, 10);
                c.clip();
                write(c, regular, 11, 72, 600, "Hidden duplicate words");
                c.restoreGraphicsState();

                write(c, bold, 28, 72, 770, "Thabo Mokoena");

                c.beginText();                                             // "PROFILE" spaced out, in two pieces
                c.setFont(regular, 14);
                c.setCharacterSpacing(3);
                c.newLineAtOffset(72, 730);
                c.showText("PROFIL");
                c.newLineAtOffset(regular.getStringWidth("PROFIL") / 1000 * 14 + 6 * 3, 0);
                c.showText("E");
                c.setCharacterSpacing(0);
                c.endText();

                String[][] skills = {
                        {"Networking", " | TCP/IP, subnetting, DHCP and DNS fundamentals; basic connectivity troubleshooting"},
                        {"Windows", " | Windows 10/11 setup and troubleshooting; basic Microsoft 365 and Directory"},
                        {"Tools", " | Git, Maven"}};
                float y = 705;
                for (String[] line : skills) {
                    c.beginText();
                    c.newLineAtOffset(72, y);
                    c.setFont(bold, 11);
                    c.showText(line[0]);
                    c.setFont(regular, 11);
                    c.showText(line[1]);
                    c.endText();
                    y -= 14;
                }

                write(c, regular, 11, 72, 640, "Code on GitHub");
            }
            PDAnnotationLink link = new PDAnnotationLink();
            float left = 72 + regular.getStringWidth("Code on ") / 1000 * 11;
            link.setRectangle(new PDRectangle(left, 637, regular.getStringWidth("GitHub") / 1000 * 11, 13));
            PDActionURI action = new PDActionURI();
            action.setURI("https://github.com/example");
            link.setAction(action);
            page.getAnnotations().add(link);

            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream c = new PDPageContentStream(doc, second)) {
                write(c, regular, 11, 72, 770, "Second page text.");
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static void write(PDPageContentStream c, PDType1Font font, float size, float x, float y, String text) throws Exception {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
    }

    @Test
    void textHiddenByClippingIsLeftOut() throws Exception {
        String md = convert(cvPdf());

        assertFalse(md.contains("Hidden"), "a clipped-away copy isn't shown, so it isn't converted: " + md);
        assertTrue(md.startsWith("# Thabo Mokoena\n"), md);
    }

    @Test
    void letterSpacedHeadingsLabelLinesAndLinksComeOutAsShown() throws Exception {
        String md = convert(cvPdf());

        assertTrue(md.contains("PROFILE\n"), "the spaced-out heading is one word: " + md);
        assertTrue(md.contains("**Networking** | TCP/IP, subnetting, DHCP and DNS fundamentals; basic connectivity troubleshooting\\\n"
                + "**Windows** | Windows 10/11"), "each label line on its own line: " + md);
        assertTrue(md.contains("Code on [GitHub](https://github.com/example)"), md);
    }

    @Test
    void pagesStayPagesThroughMarkdownAndBack() throws Exception {
        Path pdf = cvPdf();
        FileResult toMd = new PdfToMarkdownTool().run(List.of(pdf), options(), sameFolder, new Progress()).get(0);
        String md = Files.readString(toMd.getOutput());
        assertTrue(md.indexOf(PdfToMarkdownTool.PAGE_BREAK) > md.indexOf("GitHub")
                && md.indexOf(PdfToMarkdownTool.PAGE_BREAK) < md.indexOf("Second page text."), md);

        Map<String, Object> toPdf = new HashMap<>();
        toPdf.put(MarkdownToPdfTool.PAGE_SIZE, MarkdownToPdfTool.A4);
        toPdf.put(MarkdownToPdfTool.PAGE_NUMBERS, false);
        FileResult back = new MarkdownToPdfTool().run(List.of(toMd.getOutput()), toPdf, sameFolder, new Progress()).get(0);
        assertEquals(2, TestFiles.pageCount(back.getOutput()), "the page break starts page 2 again");
        assertFalse(TestFiles.text(back.getOutput()).contains("page-break"), "the tag itself isn't printed");
    }

    @Test
    void markdownCharactersInTheTextAreEscaped() {
        assertEquals("2 \\* 3 = 6, a\\_b, \\[note\\]", PdfToMarkdownTool.escape("2 * 3 = 6, a_b, [note]"));
        assertEquals("\\# not a heading", PdfToMarkdownTool.escapeLineStart("# not a heading"));
        assertEquals("2026\\. A good year", PdfToMarkdownTool.escapeLineStart("2026. A good year"));
        assertEquals("\\- not a bullet", PdfToMarkdownTool.escapeLineStart("- not a bullet"));
        assertEquals("Plain text", PdfToMarkdownTool.escapeLineStart("Plain text"));
    }
}
