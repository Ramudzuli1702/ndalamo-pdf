package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redact PDF (SPEC §4): removes words, personal details or areas for good. This is
 * true redaction: on every page with something to remove, the page is redrawn as a
 * picture with black boxes painted over those parts, so the text and images under
 * them no longer exist in the new file (just drawing a box over them would leave the
 * text there to copy). The other words on those pages are written back as invisible
 * text, so the page can still be searched. Pages with nothing to remove are untouched.
 *
 * Form fields are locked into the page first, so answers in them can be redacted too,
 * and the file's hidden details (author, title, keywords) can be cleared. The input
 * file is never changed.
 */
public class RedactTool implements PdfTool {

    static final String FIND = "find";
    static final String BY_TEXT = "Words, numbers or details";
    static final String BY_AREA = "Areas I draw";
    static final String WORDS = "words";
    static final String ID_NUMBERS = "idNumbers";
    static final String PHONE_NUMBERS = "phoneNumbers";
    static final String EMAILS = "emails";
    static final String MATCH_CASE = "matchCase";
    static final String AREA = "area";
    static final String PAGES = "pages";
    static final String HIDDEN_DETAILS = "hiddenDetails";

    /** South African ID numbers: 13 digits, often written in groups (YYMMDD SSSS C A Z). */
    private static final Pattern ID_NUMBER = Pattern.compile("\\b\\d{6}\\s?\\d{4}\\s?\\d{2}\\s?\\d\\b");
    /** South African phone numbers: 0xx xxx xxxx or +27 xx xxx xxxx, with spaces or dashes. */
    private static final Pattern PHONE_NUMBER = Pattern.compile("(?<![\\d+])(\\+27\\s?|0)\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    /** Redacted pages are redrawn at this resolution: sharp text when printed, sensible file size. */
    private static final int DPI = 200;
    /** Boxes reach this far past the letters, so no edge of a letter peeks out. */
    private static final float PADDING = 1.2f;

    @Override public String id() { return "redact"; }
    @Override public String name() { return "Redact PDF"; }
    @Override public String description() { return "Black out sensitive info"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "redacted"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(FIND, "What to remove", OptionField.Type.CHOICE, List.of(BY_TEXT, BY_AREA), BY_TEXT)
                        .describe("Found wherever they appear", "The same part of every chosen page"),
                new OptionField(WORDS, "Words or phrases (separate with commas)", OptionField.Type.TEXT, "")
                        .hint("e.g. Thabo Nkosi, 12 Church Street").shownWhen(FIND, BY_TEXT),
                new OptionField(ID_NUMBERS, "ID numbers", OptionField.Type.CHECKBOX, false).shownWhen(FIND, BY_TEXT),
                new OptionField(PHONE_NUMBERS, "Phone numbers", OptionField.Type.CHECKBOX, false).shownWhen(FIND, BY_TEXT),
                new OptionField(EMAILS, "Email addresses", OptionField.Type.CHECKBOX, false).shownWhen(FIND, BY_TEXT),
                new OptionField(AREA, "Area to remove", OptionField.Type.AREA, null).shownWhen(FIND, BY_AREA),
                new OptionField(HIDDEN_DETAILS, "Also clear the author, title and keywords stored in the file",
                        OptionField.Type.CHECKBOX, true).advanced(),
                new OptionField(MATCH_CASE, "Match upper and lower case exactly", OptionField.Type.CHECKBOX, false).advanced(),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced());
    }

    @Override
    public String saveToNote() {
        return "Redaction is permanent in the new file: what's under the black boxes is removed, not just covered. "
                + "Your original is not changed.";
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        if (BY_AREA.equals(options.get(FIND))) {
            if (!(options.get(AREA) instanceof double[])) return "Draw the area to remove first: click “Draw the area”.";
        } else if (patterns(options).isEmpty()) {
            return "Type the words to remove, or tick ID numbers, phone numbers or email addresses.";
        }
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
    public String buttonLabel(int files) {
        return "Redact " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Redacting " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " redacted";
    }

    /** What to look for: the typed words and phrases, then the ticked kinds of detail. */
    static List<Pattern> patterns(Map<String, Object> options) {
        int flags = Boolean.TRUE.equals(options.get(MATCH_CASE)) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        List<Pattern> patterns = new ArrayList<>();
        Object words = options.get(WORDS);
        if (words instanceof String text) {
            for (String phrase : text.split(",")) {
                String p = phrase.strip();
                if (p.isEmpty()) continue;
                // Spaces in a phrase match any run of spaces or a line break: "Thabo Nkosi" split over two lines.
                StringBuilder regex = new StringBuilder();
                String[] parts = p.split("\\s+");
                for (int i = 0; i < parts.length; i++) regex.append(i == 0 ? "" : "\\s+").append(Pattern.quote(parts[i]));
                // Whole words only, so "Ann" doesn't black out part of "Annual".
                String before = Character.isLetterOrDigit(p.charAt(0)) ? "(?<![\\p{L}\\p{N}])" : "";
                String after = Character.isLetterOrDigit(p.charAt(p.length() - 1)) ? "(?![\\p{L}\\p{N}])" : "";
                patterns.add(Pattern.compile(before + regex + after, flags));
            }
        }
        if (Boolean.TRUE.equals(options.get(ID_NUMBERS))) patterns.add(ID_NUMBER);
        if (Boolean.TRUE.equals(options.get(PHONE_NUMBERS))) patterns.add(PHONE_NUMBER);
        if (Boolean.TRUE.equals(options.get(EMAILS))) patterns.add(EMAIL);
        return patterns;
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean byArea = BY_AREA.equals(options.get(FIND));
        double[] area = options.get(AREA) instanceof double[] a ? a : null;
        List<Pattern> patterns = byArea ? List.of() : patterns(options);
        if (byArea && area == null) throw new ToolProblem("Draw the area to remove first. Your files were not changed.", null);
        boolean clearDetails = !Boolean.FALSE.equals(options.get(HIDDEN_DETAILS));
        String pageChoice = (String) options.get(PAGES);

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));

                // Answers in form fields are part of what's on the page: lock them in first so they can be redacted.
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                boolean hadForm = form != null && !form.getFields().isEmpty();
                if (hadForm) form.flatten(form.getFields(), true);

                PDFRenderer renderer = new PDFRenderer(doc);
                PDFont plex = PageCanvas.load(doc, "/fonts/IBMPlexSans-Regular.ttf");
                PDFont noto = PageCanvas.load(doc, "/fonts/NotoSans-Regular.ttf");
                int boxes = 0, pagesRedacted = 0, scans = 0;
                for (int n = 0; n < pages.size(); n++) {
                    if (progress.isCancelled()) return results;       // nothing saved for this file
                    int index = pages.get(n) - 1;
                    progress.file(i, (n + 0.5) / pages.size() * 0.9, "Page " + pages.get(n) + " of " + doc.getNumberOfPages());
                    PDPage page = doc.getPage(index);
                    float[] size = shownSize(page);
                    List<float[]> found;
                    if (byArea) {
                        found = List.of(new float[]{(float) area[0] * size[0], (float) area[1] * size[1],
                                (float) area[2] * size[0], (float) area[3] * size[1]});
                    } else {
                        Letters letters = letters(doc, index);
                        if (letters.text.toString().replaceAll("\\s", "").length() < DocumentReader.HAS_TEXT) scans++;
                        found = find(letters, patterns);
                    }
                    if (found.isEmpty()) continue;
                    redactPage(doc, page, index, renderer, found, size, plex, noto);
                    boxes += found.size();
                    pagesRedacted++;
                }

                if (boxes == 0) {
                    String why = scans > 0
                            ? "Nothing was found. Some pages are scans, which can't be searched for words: draw the areas "
                              + "instead, or run OCR PDF on it first."
                            : "None of those words or details were found, so nothing was redacted or saved.";
                    results.add(new FileResult(input, null, Files.size(input), 0, FileResult.Status.SKIPPED, why));
                    progress.file(i, 1, "Nothing found");
                    continue;
                }
                if (clearDetails) clearHiddenDetails(doc, patterns);

                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                String note = Format.count(boxes, "part", "parts") + " removed on " + Format.count(pagesRedacted, "page", "pages")
                        + " · the rest of those pages can still be searched"
                        + (hadForm ? " · form answers were locked in" : "")
                        + (scans > 0 ? " · " + Format.count(scans, "scanned page", "scanned pages") + " couldn't be searched" : "");
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        scans > 0 ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note));
                progress.file(i, 1, "Done");
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage()));
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** The page's width and height as shown (turned pages swap them). */
    private static float[] shownSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        boolean sideways = page.getRotation() % 180 != 0;
        return sideways ? new float[]{box.getHeight(), box.getWidth()} : new float[]{box.getWidth(), box.getHeight()};
    }

    /** A page's text, letter by letter, with each letter's box (null for the spaces and line breaks between words). */
    private record Letters(StringBuilder text, List<float[]> boxes) {}

    private static Letters letters(PDDocument doc, int pageIndex) throws IOException {
        StringBuilder text = new StringBuilder();
        List<float[]> boxes = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String string, List<TextPosition> positions) {
                if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) {
                    text.append(' ');
                    boxes.add(null);
                }
                for (TextPosition p : positions) {
                    String unicode = p.getUnicode();
                    for (int k = 0; k < unicode.length(); k++) {     // a ligature ("ﬁ") can be two letters with one box
                        text.append(unicode.charAt(k));
                        // From the font size, not PDFBox's letter height, which runs short of tall letters' tops:
                        // a box that low would leave the top of the words readable.
                        float size = Math.max(p.getFontSizeInPt(), p.getHeightDir());
                        boxes.add(unicode.isBlank() ? null : new float[]{p.getXDirAdj(), p.getYDirAdj() - size * 0.95f,
                                p.getXDirAdj() + p.getWidthDirAdj(), p.getYDirAdj() + size * 0.25f});
                    }
                }
            }

            @Override
            protected void writeLineSeparator() {
                text.append('\n');
                boxes.add(null);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return new Letters(text, boxes);
    }

    /**
     * Every match on the page as boxes {left, top, right, bottom} in points from the
     * page's top-left: one box per line a match covers.
     */
    private static List<float[]> find(Letters letters, List<Pattern> patterns) {
        List<float[]> found = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(letters.text);
            while (m.find()) {
                float[] line = null;
                for (int c = m.start(); c < m.end(); c++) {
                    float[] box = letters.boxes.get(c);
                    if (box == null) continue;
                    if (line != null && Math.abs(box[3] - line[3]) > (line[3] - line[1]) * 0.4f) {
                        found.add(pad(line));                          // the match carries on on the next line
                        line = null;
                    }
                    line = line == null ? box.clone()
                            : new float[]{Math.min(line[0], box[0]), Math.min(line[1], box[1]), Math.max(line[2], box[2]), Math.max(line[3], box[3])};
                }
                if (line != null) found.add(pad(line));
            }
        }
        return found;
    }

    private static float[] pad(float[] box) {
        return new float[]{box[0] - PADDING, box[1] - PADDING, box[2] + PADDING, box[3] + PADDING};
    }

    /**
     * Redraws the page as a picture with the boxes painted black, so nothing under them
     * survives, then writes the page's other words back as invisible text. The same page
     * object is reused, so bookmarks and links to it keep working.
     */
    private static void redactPage(PDDocument doc, PDPage page, int index, PDFRenderer renderer, List<float[]> boxes,
                                   float[] size, PDFont plex, PDFont noto) throws IOException {
        List<TextWord> keep = new ArrayList<>();
        for (TextWord w : PageText.words(doc, index)) {
            boolean covered = boxes.stream().anyMatch(b -> w.right() > b[0] && w.left() < b[2] && w.bottom() > b[1] && w.top() < b[3]);
            if (!covered) keep.add(w);
        }

        BufferedImage picture = renderer.renderImageWithDPI(index, DPI, ImageType.RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.BLACK);
        float scale = DPI / 72f;
        for (float[] b : boxes) {
            g.fillRect(Math.round(b[0] * scale), Math.round(b[1] * scale),
                    Math.round((b[2] - b[0]) * scale), Math.round((b[3] - b[1]) * scale));
        }
        g.dispose();

        // Empty the page: its old drawing instructions, fonts and pictures all go, along with anything on top of it.
        page.setResources(new PDResources());
        page.setRotation(0);
        PDRectangle shown = new PDRectangle(size[0], size[1]);
        page.setMediaBox(shown);
        page.setCropBox(shown);
        page.setBleedBox(null);
        page.setTrimBox(null);
        page.setArtBox(null);
        page.getAnnotations().clear();
        page.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.THUMB);

        var image = LosslessFactory.createFromImage(doc, picture);
        try (PDPageContentStream content = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true)) {
            content.drawImage(image, 0, 0, size[0], size[1]);
            content.beginText();
            content.setRenderingMode(RenderingMode.NEITHER);          // invisible: searchable, not seen
            for (TextWord w : keep) {
                PDFont font = canWrite(plex, w.text()) ? plex : canWrite(noto, w.text()) ? noto : null;
                if (font == null || w.right() <= w.left()) continue;
                float natural = font.getStringWidth(w.text()) / 1000 * w.size();
                if (natural <= 0) continue;
                content.setFont(font, w.size());
                content.setHorizontalScaling(100 * (w.right() - w.left()) / natural);
                content.setTextMatrix(Matrix.getTranslateInstance(w.left(), size[1] - w.baseline()));
                content.showText(w.text());
            }
            content.endText();
        }
    }

    private static boolean canWrite(PDFont font, String text) {
        try {
            font.encode(text);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The file's stored author, title, subject and keywords, and its XMP details, can hold
     * the very names being redacted: clear them. Bookmark titles keep their place, with
     * any redacted words replaced.
     */
    private static void clearHiddenDetails(PDDocument doc, List<Pattern> patterns) {
        doc.setDocumentInformation(new PDDocumentInformation());
        doc.getDocumentCatalog().setMetadata(null);
        PDOutlineNode outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline != null && !patterns.isEmpty()) scrubBookmarks(outline, patterns);
    }

    private static void scrubBookmarks(PDOutlineNode node, List<Pattern> patterns) {
        for (PDOutlineItem item : node.children()) {
            String title = item.getTitle();
            if (title != null) {
                for (Pattern p : patterns) title = p.matcher(title).replaceAll("[redacted]");
                item.setTitle(title);
            }
            scrubBookmarks(item, patterns);
        }
    }
}
