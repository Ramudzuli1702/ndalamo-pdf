package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown to PDF: turns .md files (notes, READMEs, assignments written in Markdown)
 * into PDFs. commonmark reads the Markdown, including tables, strikethrough and task
 * lists (decision 011), and MarkdownRenderer draws it. Pictures are taken from files
 * on this PC, found next to the .md file; pictures on the web are not downloaded.
 * The .md file is only read.
 */
public class MarkdownToPdfTool implements PdfTool {

    static final String PAGE_SIZE = "pageSize";
    static final String A4 = "A4";
    static final String LETTER = "Letter";
    static final String PAGE_NUMBERS = "pageNumbers";

    @Override public String id() { return "markdown-to-pdf"; }
    @Override public String name() { return "Markdown to PDF"; }
    @Override public String description() { return "Notes and READMEs"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "converted"; }
    @Override public Set<String> fileTypes() { return Set.of("md", "markdown"); }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(PAGE_SIZE, "Page size", OptionField.Type.CHOICE, List.of(A4, LETTER), A4)
                        .describe("Used in South Africa and most of the world", "Used in the US and Canada"),
                new OptionField(PAGE_NUMBERS, "Number the pages", OptionField.Type.CHECKBOX, true));
    }

    @Override
    public String buttonLabel(int files) {
        return "Convert " + Format.count(files, "file", "files") + " to PDF";
    }

    @Override
    public String workingLabel(int files) {
        return "Converting " + Format.count(files, "file", "files") + " to PDF…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to PDF";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        PDRectangle size = LETTER.equals(options.get(PAGE_SIZE)) ? PDRectangle.LETTER : PDRectangle.A4;
        boolean numbers = !Boolean.FALSE.equals(options.get(PAGE_NUMBERS));
        Parser parser = Parser.builder()
                .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
                .build();

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.1, "Reading");
            progress.update((double) i / inputs.size(), "Converting " + input.getFileName());
            try {
                String text = read(input);
                Node document = parser.parse(text);
                try (PDDocument doc = new PDDocument()) {
                    MarkdownRenderer renderer = new MarkdownRenderer(doc, input.toAbsolutePath().getParent(), size);
                    progress.file(i, 0.4, "Laying out the pages");
                    renderer.render(document);
                    if (numbers) renderer.numberPages();
                    String title = firstHeading(document);
                    if (title != null) doc.getDocumentInformation().setTitle(title);
                    if (progress.isCancelled()) break;        // nothing saved for this file

                    Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                    SafeSave.write(output, temp -> doc.save(temp.toFile()));
                    String note = Format.count(doc.getNumberOfPages(), "page", "pages")
                            + (renderer.missingPictures() > 0 ? " · " + Format.count(renderer.missingPictures(),
                            "picture couldn't be shown", "pictures couldn't be shown") + " (on the web or not found)" : "");
                    results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                            renderer.missingPictures() > 0 ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note));
                }
                progress.file(i, 1, "Done");
            } catch (MalformedInputException e) {
                results.add(PdfFiles.failed(input, "It isn't a text file NdalamoPDF can read (it should be saved as UTF-8)."));
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't read it — " + e.getMessage()));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** The file as text. Markdown is UTF-8; a byte-order mark at the start (Notepad adds one) is dropped. */
    private static String read(Path input) throws IOException {
        // Throws on bytes that aren't UTF-8, e.g. a Word file renamed to .md.
        String text = Files.readString(input, StandardCharsets.UTF_8);
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    private static String firstHeading(Node document) {
        for (Node n = document.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h && h.getLevel() == 1) {
                StringBuilder title = new StringBuilder();
                for (Node c = h.getFirstChild(); c != null; c = c.getNext()) if (c instanceof Text t) title.append(t.getLiteral());
                return title.toString().strip();
            }
        }
        return null;
    }
}
