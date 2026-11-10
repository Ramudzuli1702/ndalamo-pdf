package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.Paragraph;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF to Markdown: writes a PDF's text as Markdown (.md), the plain-text format used
 * for notes, READMEs and wikis. It reads the PDF exactly like PDF to Word does
 * (DocumentReader): headings become #, lists - and 1., bold and italic ** and *,
 * tables pipe tables. Pictures are saved in a folder next to the .md file and linked
 * from it. Layout that Markdown has no way to say (fonts, colours, centring) is left
 * out. The PDF is only read.
 */
public class PdfToMarkdownTool implements PdfTool {

    static final String PICTURES = "pictures";
    static final String OCR = "ocr";
    static final String LANGUAGE = "language";
    static final String PAGES = "pages";

    /** Written between pages; MarkdownRenderer starts a new page on it. */
    static final String PAGE_BREAK = "<div style=\"page-break-after: always;\"></div>";

    @Override public String id() { return "pdf-to-markdown"; }
    @Override public String name() { return "PDF to Markdown"; }
    @Override public String description() { return "Plain-text .md"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "markdown"; }
    @Override public String outputExtension() { return "md"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(PICTURES, "Save pictures next to the .md file", OptionField.Type.CHECKBOX, true),
                new OptionField(OCR, "Read scanned pages with OCR (slower)", OptionField.Type.CHECKBOX, true),
                new OptionField(LANGUAGE, "Language of scanned pages", OptionField.Type.CHOICE,
                        List.of(OcrTool.ENGLISH, OcrTool.AFRIKAANS, OcrTool.BOTH), OcrTool.ENGLISH)
                        .describe("", "", "Slower; for documents that mix both").advanced(),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        String pages = (String) options.get(PAGES);
        return pages == null || pages.isBlank() ? null : PageRanges.check(pages);
    }

    @Override
    public List<Integer> previewPages(Map<String, Object> options, int pageCount) {
        String pages = (String) options.get(PAGES);
        if (pages == null || pages.isBlank()) return List.of();
        try {
            return PageRanges.pages(PageRanges.parse(pages, pageCount));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @Override
    public String saveToNote() {
        return "Pictures go in a folder named after the .md file.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Convert " + Format.count(files, "file", "files") + " to Markdown";
    }

    @Override
    public String workingLabel(int files) {
        return "Converting " + Format.count(files, "file", "files") + " to Markdown…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to Markdown";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean pictures = !Boolean.FALSE.equals(options.get(PICTURES));
        String pageChoice = (String) options.get(PAGES);
        Object language = options.get(LANGUAGE);
        String languages = !Boolean.FALSE.equals(options.get(OCR))
                ? OcrTool.AFRIKAANS.equals(language) ? "afr" : OcrTool.BOTH.equals(language) ? "eng+afr" : "eng"
                : null;
        OcrEngine[] engine = new OcrEngine[1];

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));
                DocumentReader.Reading reading = DocumentReader.read(doc, input, pages,
                        new DocumentReader.Settings(pictures, languages), engine, i, inputs.size(), progress);
                if (reading == null) break;               // cancelled partway: nothing written for this file
                results.add(write(reading, input, doc.getNumberOfPages(), pages.size(), saveTo));
                progress.file(i, 1, "Done");
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage()));     // e.g. "page 9" in a 5-page file
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Writes the .md file, and the pictures into a folder beside it. */
    private FileResult write(DocumentReader.Reading reading, Path input, int pageCount, int pagesRead, SaveTo saveTo)
            throws IOException {
        Path output = saveTo.outputFor(input, outputSuffix(), outputExtension(), pageCount);
        String base = output.getFileName().toString().replaceFirst("\\.md$", "");
        Path pictureFolder = output.resolveSibling(base + "_pictures");

        List<TextWord> allWords = new ArrayList<>();
        reading.paragraphs().forEach(p -> allWords.addAll(p.words()));
        float bodySize = allWords.isEmpty() ? 11 : WordLayout.mostCommonSize(allWords);

        StringBuilder md = new StringBuilder();
        int paragraphs = 0, pictures = 0;
        for (int n = 0; n < reading.pages().size(); n++) {
            List<DocumentReader.Item> page = reading.pages().get(n);
            List<DocumentReader.Link> links = reading.links().get(n);
            // Pages stay pages: browsers and Markdown editors break the page here when printing, and so
            // does Ndalamo's Markdown to PDF. The tag shows nothing when the Markdown is read on screen.
            if (n > 0) md.append(PAGE_BREAK).append("\n\n");
            Paragraph previousLine = null;                    // a one-line paragraph that the next line may join
            for (DocumentReader.Item item : page) {
                if (item.paragraph() != null) {
                    Paragraph p = item.paragraph();
                    Integer heading = reading.headings().get(p);
                    Integer level = reading.listLevels().get(p);
                    // A bold line in body size stays bold text: "###" would print it bigger than in the PDF.
                    if (heading != null && Math.abs(p.size() - bodySize) < 1) heading = null;
                    if (heading != null) {
                        List<TextWord> words = new ArrayList<>(p.words());
                        if (p.marker() != null) words.add(0, p.marker().word());   // "1. Contributions" keeps its number
                        // A heading is already a heading: its bold isn't marked again.
                        md.append("#".repeat(heading)).append(' ').append(inline(words, links, false, true)).append("\n\n");
                        previousLine = null;
                    } else if (level != null) {
                        md.append("    ".repeat(level)).append(listMarker(p)).append(inline(p.words(), links, false, false)).append('\n');
                        // A blank line ends the list unless the next item carries on.
                        if (!nextIsListItem(page, item, reading)) md.append('\n');
                        previousLine = null;
                    } else {
                        List<TextWord> words = new ArrayList<>(p.words());
                        if (p.marker() != null) words.add(0, p.marker().word());
                        // Lines set one under the other with no gap ("University of Johannesburg" under the
                        // degree) stay together, with a line break, instead of becoming spaced paragraphs.
                        if (previousLine != null && oneLine(p) && p.spaceBefore() < p.size() * 0.3f) {
                            md.setLength(md.length() - 2);
                            md.append("\\\n");
                        }
                        md.append(escapeLineStart(inline(words, links, false, false))).append("\n\n");
                        previousLine = oneLine(p) ? p : null;
                    }
                    paragraphs++;
                } else if (item.table() != null) {
                    md.append(table(item.table(), links)).append('\n');
                    previousLine = null;
                } else {
                    previousLine = null;
                    pictures++;
                    String name = "picture-" + pictures + (item.picture().jpeg() ? ".jpg" : ".png");
                    Files.createDirectories(pictureFolder);
                    byte[] bytes = item.picture().bytes();
                    SafeSave.write(pictureFolder.resolve(name), temp -> Files.write(temp, bytes));
                    md.append("![](<").append(pictureFolder.getFileName()).append('/').append(name).append(">)\n\n");
                }
            }
        }
        String text = md.toString().strip() + "\n";
        SafeSave.write(output, temp -> Files.writeString(temp, text, StandardCharsets.UTF_8));

        String note = Format.count(pagesRead, "page", "pages") + " · " + Format.count(paragraphs, "paragraph", "paragraphs")
                + (reading.tables() > 0 ? " · " + Format.count(reading.tables(), "table", "tables") : "")
                + (pictures > 0 ? " · " + Format.count(pictures, "picture", "pictures") + " in " + pictureFolder.getFileName() : "")
                + (reading.scansRead() > 0 ? " · " + Format.count(reading.scansRead(), "scanned page", "scanned pages") + " read with OCR" : "");
        if (reading.scans() > 0) {
            note += " · " + Format.count(reading.scans(), "page looks", "pages look") + " scanned and came out empty"
                    + " — tick “Read scanned pages with OCR” to read them";
        }
        return new FileResult(input, output, java.nio.file.Files.size(input), Files.size(output),
                reading.scans() == pagesRead ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note);
    }

    private static boolean nextIsListItem(List<DocumentReader.Item> page, DocumentReader.Item item, DocumentReader.Reading reading) {
        int i = page.indexOf(item);
        return i + 1 < page.size() && page.get(i + 1).paragraph() != null
                && reading.listLevels().containsKey(page.get(i + 1).paragraph());
    }

    /**
     * "- " for bullets, "3. " for numbers (Markdown keeps the first number). Markdown can't
     * number with letters or Roman numerals, so "b)" and "(iv)" stay as text after a bullet.
     */
    private static String listMarker(Paragraph p) {
        WordLayout.ListMarker marker = p.marker();
        if (marker.bullet()) return "- ";
        if (marker.format().equals("decimal")) return marker.number() + ". ";
        return "- " + escape(marker.word().text()) + " ";
    }

    /** One paragraph on a single line. */
    private static boolean oneLine(Paragraph p) {
        float first = p.words().get(0).baseline();
        return p.words().stream().allMatch(w -> Math.abs(w.baseline() - first) < p.size() * 0.5f);
    }

    /**
     * Words as Markdown text: **bold**, *italic*, ***both***, [linked](https://…), with
     * Markdown's own characters escaped. In a heading, bold isn't marked, since a heading
     * is bold anyway.
     */
    static String inline(List<TextWord> words, List<DocumentReader.Link> links, boolean inTable, boolean inHeading) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < words.size()) {
            TextWord first = words.get(i);
            String uri = linkOf(first, links);
            StringBuilder run = new StringBuilder();
            int j = i;
            while (j < words.size() && (inHeading || words.get(j).bold() == first.bold()) && words.get(j).italic() == first.italic()
                    && java.util.Objects.equals(linkOf(words.get(j), links), uri)) {
                if (j > i) run.append(' ');
                String text = escape(words.get(j).text());
                run.append(inTable ? text.replace("|", "\\|") : text);
                j++;
            }
            boolean bold = first.bold() && !inHeading;
            String mark = bold && first.italic() ? "***" : bold ? "**" : first.italic() ? "*" : "";
            String styled = mark + run + mark;
            if (uri != null) styled = "[" + styled + "](" + linkTarget(uri) + ")";
            // Next to a link, a new run starts at a link's edge, not at a space: keep the words apart
            // only where the PDF has a gap.
            TextWord before = i > 0 ? words.get(i - 1) : null;
            if (out.length() > 0 && (Math.abs(first.baseline() - before.baseline()) > 1
                    || first.left() - before.right() > first.size() * 0.1f)) out.append(' ');
            out.append(styled);
            i = j;
        }
        return out.toString();
    }

    private static String linkOf(TextWord word, List<DocumentReader.Link> links) {
        for (DocumentReader.Link link : links) if (link.covers(word)) return link.uri();
        return null;
    }

    /** A link address as Markdown needs it: in angle brackets when it has spaces or brackets. */
    private static String linkTarget(String uri) {
        return uri.matches(".*[\\s()<>].*") ? "<" + uri.replace("<", "%3C").replace(">", "%3E") + ">" : uri;
    }

    /** Backslashes before characters that would otherwise change the text's meaning in Markdown. */
    static String escape(String text) {
        return text.replaceAll("([\\\\`*_\\[\\]<>])", "\\\\$1");
    }

    /** A paragraph starting "# ", "- ", "+ " or "3. " would turn into a heading or list; escape that start. */
    static String escapeLineStart(String text) {
        if (text.matches("^(#{1,6}|[-+])(\\s.*)?$")) return "\\" + text;
        if (text.matches("^\\d{1,9}[.)](\\s.*)?$")) return text.replaceFirst("^(\\d+)([.)])", "$1\\\\$2");
        return text;
    }

    /** A pipe table; the first row is the header, as Markdown needs one. */
    private static String table(TableFinder.Table<TextWord> table, List<DocumentReader.Link> links) {
        StringBuilder out = new StringBuilder();
        List<List<List<TextWord>>> rows = table.cells();
        for (int r = 0; r < rows.size(); r++) {
            out.append('|');
            for (List<TextWord> cell : rows.get(r)) {
                List<TextWord> words = new ArrayList<>(cell);
                words.sort(java.util.Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
                out.append(' ').append(inline(words, links, true, false)).append(" |");
            }
            out.append('\n');
            if (r == 0) out.append('|').append(" --- |".repeat(table.columns())).append('\n');
        }
        return out.toString();
    }
}
