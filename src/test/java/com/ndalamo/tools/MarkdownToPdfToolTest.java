package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownToPdfToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static final String NOTES = """
            # Stokvel notes

            The group met on **Saturday** and agreed on the *new* rules. See [our website](https://example.org/stokvel)
            for the full minutes, or use `ndalamo --help`. Old rule: ~~cash only~~.

            ## To do

            - [x] Open the bank account
            - [ ] Collect November contributions

            1. Treasurer's report
            2. General matters
               - Venue for December
               - Food

            > Save a little every month and it adds up.

            ```
            total = members * 500
            print(total)
            ```

            | Member | Paid |
            | --- | ---: |
            | Mpho | 500 |
            | Thandeka | 500 |

            ![Logo](logo.png)

            ![Chart](https://example.org/chart.png)

            Ndi a livhuwa — ṱhoho ḓivhazwakale.
            """;

    private Path notes() throws Exception {
        BufferedImage logo = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var g = logo.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        javax.imageio.ImageIO.write(logo, "png", dir.resolve("logo.png").toFile());
        return Files.writeString(dir.resolve("Notes.md"), NOTES, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> options(boolean pageNumbers) {
        Map<String, Object> options = new HashMap<>();
        options.put(MarkdownToPdfTool.PAGE_SIZE, MarkdownToPdfTool.A4);
        options.put(MarkdownToPdfTool.PAGE_NUMBERS, pageNumbers);
        return options;
    }

    @Test
    void everyKindOfMarkdownEndsUpInThePdfInOrder() throws Exception {
        Path md = notes();
        byte[] before = Files.readAllBytes(md);

        FileResult result = new MarkdownToPdfTool().run(List.of(md), options(false), sameFolder, new Progress()).get(0);

        assertEquals(dir.resolve("Notes_converted.pdf"), result.getOutput());
        String text = TestFiles.text(result.getOutput()).replaceAll("\\s+", " ");
        String[] inOrder = {"Stokvel notes", "Saturday", "new", "our website", "ndalamo --help", "cash only", "To do",
                "Open the bank account", "Collect November contributions", "1. Treasurer's report", "2. General matters",
                "Venue for December", "Food", "Save a little every month", "total = members * 500", "print(total)",
                "Member", "Paid", "Mpho", "Thandeka", "Picture: Chart", "Ndi a livhuwa", "ṱhoho ḓivhazwakale"};
        int at = -1;
        for (String expected : inOrder) {
            int next = text.indexOf(expected, at + 1);
            assertTrue(next > at, "\"" + expected + "\" missing or out of order in: " + text);
            at = next;
        }
        assertArrayEquals(before, Files.readAllBytes(md));
    }

    @Test
    void linksPicturesAndTitleWork() throws Exception {
        FileResult result = new MarkdownToPdfTool().run(List.of(notes()), options(false), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.PARTIAL, result.getStatus(), "the web picture couldn't be shown");
        assertTrue(result.getNote().contains("1 picture couldn't be shown"), result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertEquals("Stokvel notes", doc.getDocumentInformation().getTitle(), "the first heading becomes the title");
            var page = doc.getPage(0);
            PDAnnotationLink link = (PDAnnotationLink) page.getAnnotations().stream()
                    .filter(a -> a instanceof PDAnnotationLink).findFirst().orElseThrow();
            assertEquals("https://example.org/stokvel", ((PDActionURI) link.getAction()).getURI());
            long pictures = 0;
            for (var p : doc.getPages()) {
                for (COSName name : p.getResources().getXObjectNames()) {
                    if (p.getResources().getXObject(name) instanceof PDImageXObject) pictures++;
                }
            }
            assertEquals(1, pictures, "the logo from this PC is drawn; the web picture is not downloaded");
        }
    }

    @Test
    void longTextFlowsOntoMorePagesWithNumbers() throws Exception {
        StringBuilder md = new StringBuilder("# Long report\n\n");
        for (int i = 1; i <= 60; i++) {
            md.append("Paragraph ").append(i).append(" of the report, long enough to wrap onto a second line when it is ")
                    .append("laid out between the margins of an A4 page.\n\n");
        }
        Path file = Files.writeString(dir.resolve("Long.md"), md);

        FileResult result = new MarkdownToPdfTool().run(List.of(file), options(true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        int pages = TestFiles.pageCount(result.getOutput());
        assertTrue(pages >= 3, "pages: " + pages);
        String text = TestFiles.text(result.getOutput());
        assertTrue(text.contains("Paragraph 60 of the report"), "nothing lost at the end");
        assertTrue(text.contains("\n" + pages + "\n") || text.strip().endsWith(String.valueOf(pages)), "page numbers drawn");
    }

    @Test
    void aFileThatIsNotTextFailsPlainly() throws Exception {
        Path notText = Files.write(dir.resolve("Broken.md"), new byte[]{(byte) 0xFF, (byte) 0xFE, 0, (byte) 0xD8, (byte) 0xC3});

        FileResult result = new MarkdownToPdfTool().run(List.of(notText), options(true), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().contains("UTF-8"), result.getNote());
    }

    @Test
    void pdfToMarkdownAndBackKeepsTheWords() throws Exception {
        // Round trip: the structured PDF to Markdown, then that Markdown back to PDF.
        Path pdf = TestFiles.structuredPdf(dir, "Rules.pdf");
        Map<String, Object> toMd = new HashMap<>(Map.of(PdfToMarkdownTool.PICTURES, true, PdfToMarkdownTool.OCR, false,
                PdfToMarkdownTool.PAGES, ""));
        Path md = new PdfToMarkdownTool().run(List.of(pdf), toMd, sameFolder, new Progress()).get(0).getOutput();

        Path back = new MarkdownToPdfTool().run(List.of(md), options(false), sameFolder, new Progress()).get(0).getOutput();

        String text = TestFiles.text(back);
        for (String words : List.of("Club rules", "Membership", "Bring your identity document.", "3.",
                "The chairperson opens the meeting.", "a) Apologies are read out.", "4.", "The treasurer reports.")) {
            assertTrue(text.contains(words), "\"" + words + "\" missing in: " + text);
        }
    }
}
