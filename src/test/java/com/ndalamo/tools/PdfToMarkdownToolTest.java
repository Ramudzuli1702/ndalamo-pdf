package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
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

    @Test
    void markdownCharactersInTheTextAreEscaped() {
        assertEquals("2 \\* 3 = 6, a\\_b, \\[note\\]", PdfToMarkdownTool.escape("2 * 3 = 6, a_b, [note]"));
        assertEquals("\\# not a heading", PdfToMarkdownTool.escapeLineStart("# not a heading"));
        assertEquals("2026\\. A good year", PdfToMarkdownTool.escapeLineStart("2026. A good year"));
        assertEquals("\\- not a bullet", PdfToMarkdownTool.escapeLineStart("- not a bullet"));
        assertEquals("Plain text", PdfToMarkdownTool.escapeLineStart("Plain text"));
    }
}
