package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Compare PDF (SPEC §4): shows what changed between two versions of a document. The
 * text is compared line by line across the whole document (so a page added in the
 * middle doesn't make every later page "different"), then word by word inside the
 * lines that changed. Pages are paired up by the text they share. Optionally the pages
 * are also compared as pictures, to catch changed images and drawings that have no text.
 *
 * The result is a report PDF: a summary page, then each changed page of the original
 * beside its new version, with removed words marked red and added words green. The
 * first file is the original; each other file is compared with it. Inputs are only read.
 */
public class CompareTool implements PdfTool {

    static final String VISUAL = "visual";

    private static final float REPORT_WIDTH = 842, REPORT_HEIGHT = 595;   // A4 landscape
    private static final float MARGIN = 24, GAP = 24, HEADER = 64;
    private static final float PAGE_DPI = 110;
    /** The pictures for the visual check are small: changes smaller than a few points don't matter here. */
    private static final float CHECK_DPI = 40;
    private static final int CELL = 4;                 // pixels per square at CHECK_DPI (about 7 points)
    private static final int CELL_DIFFERENCE = 22;     // average grey difference (0-255) that counts as changed
    private static final Color REMOVED = new Color(220, 53, 69);
    private static final Color ADDED = new Color(30, 150, 80);
    private static final Color VISUAL_CHANGE = new Color(232, 140, 20);

    @Override public String id() { return "compare"; }
    @Override public String name() { return "Compare PDF"; }
    @Override public String description() { return "Spot differences"; }
    @Override public String category() { return "Security & compare"; }
    @Override public String outputSuffix() { return "compared"; }
    @Override public int minFiles() { return 2; }
    @Override public boolean oneOutput() { return true; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(new OptionField(VISUAL, "Also mark changed pictures and drawings", OptionField.Type.CHECKBOX, true));
    }

    @Override
    public String saveToNote() {
        return "The first file is the original; each other file is compared with it.";
    }

    @Override
    public String buttonLabel(int files) {
        return files <= 2 ? "Compare 2 files" : "Compare " + (files - 1) + " files with the first";
    }

    @Override
    public String workingLabel(int files) {
        return "Comparing…";
    }

    @Override
    public String doneLabel(int files) {
        return files <= 2 ? "Comparison ready" : Format.count(files - 1, "comparison", "comparisons") + " ready";
    }

    // ── What is read from each file ──────────────────────────────────────

    /** A word, where it is (points from the top left of the page as shown), and its page (0-based). */
    record Word(String text, float left, float top, float right, float bottom, int page) {}

    /** A line of words; {@code key} is its text, tidied so invisible differences don't count. */
    record Line(int page, List<Word> words, String key) {}

    /** One file's lines, and the size of each page as shown (width, height in points). */
    record Side(List<Line> lines, float[][] pageSizes) {
        int pages() {
            return pageSizes.length;
        }

        List<Word> wordsOn(int page) {
            List<Word> words = new ArrayList<>();
            for (Line line : lines) if (line.page() == page) words.addAll(line.words());
            return words;
        }
    }

    /** One page of the original beside one of the new file; -1 when there is no partner. */
    record Pair(int original, int changed) {}

    /** Everything found: which words were removed and added, and how the pages pair up. */
    record Comparison(Set<Word> removed, Set<Word> added, List<Pair> pairs) {
        long removedOn(int page) {
            return page < 0 ? 0 : removed.stream().filter(w -> w.page() == page).count();
        }

        long addedOn(int page) {
            return page < 0 ? 0 : added.stream().filter(w -> w.page() == page).count();
        }
    }

    // ── Running ──────────────────────────────────────────────────────────

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean visual = !Boolean.FALSE.equals(options.get(VISUAL));
        List<FileResult> results = new ArrayList<>();
        Path original = inputs.get(0);
        PDDocument before;
        try {
            before = PdfFiles.open(original);
        } catch (IOException e) {
            String reason = "The original (the first file) couldn't be opened: it " + PdfFiles.problem(e)
                            + ". Nothing was compared.";
            for (Path input : inputs.subList(1, inputs.size())) results.add(PdfFiles.failed(input, reason));
            return results;
        }
        try (before) {
            progress.update(0.02, "Reading " + original.getFileName());
            Side a = read(before);
            int count = inputs.size() - 1;
            for (int j = 1; j < inputs.size(); j++) {
                if (progress.isCancelled()) break;
                Path input = inputs.get(j);
                double from = (double) (j - 1) / count, share = 1.0 / count;
                progress.update(from + share * 0.05, "Reading " + input.getFileName());
                PDDocument after;
                try {
                    after = PdfFiles.open(input);
                } catch (IOException e) {
                    results.add(PdfFiles.failed(input, "It " + PdfFiles.problem(e) + ", so it wasn't compared."));
                    continue;
                }
                try (after) {
                    Side b = read(after);
                    progress.update(from + share * 0.2, "Comparing the text");
                    Comparison found = compare(a, b);
                    // With more than two files there is one report per file, named after it.
                    Path nameFrom = count == 1 ? original : input;
                    Path output = saveTo.outputFor(nameFrom, outputSuffix(), "pdf", 0);
                    String[] note = new String[1];
                    try {
                        SafeSave.write(output, temp -> note[0] = writeReport(before, after, a, b, found, visual,
                                original.getFileName().toString(), input.getFileName().toString(), temp, progress,
                                from + share * 0.25, share * 0.75));
                    } catch (CancellationException e) {
                        break;     // stopped part-way: SafeSave leaves nothing behind
                    }
                    results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS, note[0]));
                }
            }
        }
        progress.update(1, "Done");
        return results;
    }

    // ── Reading ──────────────────────────────────────────────────────────

    static Side read(PDDocument doc) throws IOException {
        List<Line> lines = new ArrayList<>();
        float[][] sizes = new float[doc.getNumberOfPages()][];
        for (int p = 0; p < sizes.length; p++) {
            PDPage page = doc.getPage(p);
            PDRectangle box = page.getCropBox();
            boolean turned = page.getRotation() % 180 != 0;
            sizes[p] = turned ? new float[] {box.getHeight(), box.getWidth()} : new float[] {box.getWidth(), box.getHeight()};
            List<Word> current = new ArrayList<>();
            for (PageContent.Box box1 : PageContent.words(doc, p)) {
                String text = tidy(box1.text());
                if (text.isEmpty()) continue;
                Word word = new Word(text, box1.left(), box1.top(), box1.right(), box1.bottom(), p);
                if (!current.isEmpty() && !sameLine(current.get(0), word)) {
                    lines.add(line(p, current));
                    current = new ArrayList<>();
                }
                current.add(word);
            }
            if (!current.isEmpty()) lines.add(line(p, current));
        }
        return new Side(lines, sizes);
    }

    private static boolean sameLine(Word first, Word next) {
        float height = Math.max(1, first.bottom() - first.top());
        return Math.abs(next.bottom() - first.bottom()) < height * 0.5f;
    }

    private static Line line(int page, List<Word> words) {
        StringBuilder key = new StringBuilder();
        for (Word w : words) key.append(key.isEmpty() ? "" : " ").append(w.text());
        return new Line(page, List.copyOf(words), key.toString());
    }

    /** Ligatures (ﬁ), odd spaces and curly quotes made plain, so only real changes count. */
    static String tidy(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace('­', ' ').strip();
    }

    // ── Comparing ────────────────────────────────────────────────────────

    static Comparison compare(Side a, Side b) {
        Set<Word> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Word> added = Collections.newSetFromMap(new IdentityHashMap<>());
        // shared[b page][a page]: how many words the two pages have in common, for pairing them up.
        int[][] shared = new int[b.pages()][a.pages()];
        List<Diff.Step> steps = Diff.of(keys(a.lines()), keys(b.lines()));
        for (int i = 0; i < steps.size(); i++) {
            Diff.Step step = steps.get(i);
            if (step.kind() == Diff.Kind.SAME) {
                for (int k = 0; k < step.aEnd() - step.aStart(); k++) {
                    Line la = a.lines().get(step.aStart() + k), lb = b.lines().get(step.bStart() + k);
                    shared[lb.page()][la.page()] += lb.words().size();
                }
                continue;
            }
            // A run of changed lines: compare their words, so only the words that changed are marked.
            int aFrom = step.aStart(), bFrom = step.bStart(), aTo = step.aEnd(), bTo = step.bEnd();
            while (i + 1 < steps.size() && steps.get(i + 1).kind() != Diff.Kind.SAME) {
                i++;
                aTo = steps.get(i).aEnd();
                bTo = steps.get(i).bEnd();
            }
            List<Word> wa = words(a.lines().subList(aFrom, aTo)), wb = words(b.lines().subList(bFrom, bTo));
            for (Diff.Step w : Diff.of(texts(wa), texts(wb))) {
                switch (w.kind()) {
                    case REMOVED -> removed.addAll(wa.subList(w.aStart(), w.aEnd()));
                    case ADDED -> added.addAll(wb.subList(w.bStart(), w.bEnd()));
                    case SAME -> {
                        for (int k = 0; k < w.aEnd() - w.aStart(); k++) shared[wb.get(w.bStart() + k).page()][wa.get(w.aStart() + k).page()]++;
                    }
                }
            }
        }
        return new Comparison(removed, added, pair(a.pages(), b.pages(), shared));
    }

    /**
     * Pairs pages in order: each new page with the original page it shares most words with.
     * Original pages passed over are removed pages; a new page sharing nothing takes the next
     * unclaimed original page (an all-new page of text, or a scan), or else counts as added.
     */
    static List<Pair> pair(int originalPages, int newPages, int[][] shared) {
        int[] partner = new int[newPages];
        boolean[] claimed = new boolean[originalPages];
        for (int p = 0; p < newPages; p++) {
            partner[p] = -1;
            int best = 2;    // a couple of shared words ("Page", "1") don't make a pair
            for (int q = 0; q < originalPages; q++) {
                if (shared[p][q] > best) {
                    best = shared[p][q];
                    partner[p] = q;
                }
            }
            if (partner[p] >= 0) claimed[partner[p]] = true;
        }
        List<Pair> pairs = new ArrayList<>();
        int last = -1;
        for (int p = 0; p < newPages; p++) {
            int q = partner[p];
            if (q > last) {
                for (int skipped = last + 1; skipped < q; skipped++) pairs.add(new Pair(skipped, -1));
                pairs.add(new Pair(q, p));
                last = q;
            } else if (q < 0 && last + 1 < originalPages && !claimed[last + 1]) {
                pairs.add(new Pair(last + 1, p));
                last++;
            } else {
                pairs.add(new Pair(-1, p));
            }
        }
        for (int skipped = last + 1; skipped < originalPages; skipped++) pairs.add(new Pair(skipped, -1));
        return pairs;
    }

    private static List<String> keys(List<Line> lines) {
        return lines.stream().map(Line::key).toList();
    }

    private static List<Word> words(List<Line> lines) {
        List<Word> words = new ArrayList<>();
        for (Line line : lines) words.addAll(line.words());
        return words;
    }

    private static List<String> texts(List<Word> words) {
        return words.stream().map(Word::text).toList();
    }

    // ── Pictures: what changed without being text ────────────────────────

    /**
     * Areas (in points on the new page) that look different when both pages are drawn, leaving
     * out areas that are mostly text: text changes are already marked word by word, and text
     * that moved down after an insertion would otherwise light up half the page.
     */
    static List<float[]> visualChanges(PDFRenderer before, int pa, PDFRenderer after, int pb, float[] sizeA, float[] sizeB,
                                       List<Word> textA, List<Word> textB) throws IOException {
        if (Math.abs(sizeA[0] / sizeA[1] - sizeB[0] / sizeB[1]) > 0.02) return List.of();   // different shapes: no point
        BufferedImage ia = before.renderImageWithDPI(pa, CHECK_DPI, ImageType.GRAY);
        BufferedImage ib = after.renderImageWithDPI(pb, CHECK_DPI, ImageType.GRAY);
        if (ib.getWidth() != ia.getWidth() || ib.getHeight() != ia.getHeight()) ib = resized(ib, ia.getWidth(), ia.getHeight());
        int cols = ia.getWidth() / CELL, rows = ia.getHeight() / CELL;
        boolean[][] changed = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                long sum = 0;
                for (int y = r * CELL; y < (r + 1) * CELL; y++) {
                    for (int x = c * CELL; x < (c + 1) * CELL; x++) {
                        sum += Math.abs((ia.getRGB(x, y) & 0xFF) - (ib.getRGB(x, y) & 0xFF));
                    }
                }
                changed[r][c] = sum / (CELL * CELL) > CELL_DIFFERENCE;
            }
        }
        float toPoints = 72f / CHECK_DPI * CELL * (sizeB[0] / (ia.getWidth() * 72f / CHECK_DPI));
        List<float[]> areas = new ArrayList<>();
        boolean[][] seen = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!changed[r][c] || seen[r][c]) continue;
                int[] bounds = grow(changed, seen, r, c);
                if ((bounds[2] - bounds[0] + 1) * (bounds[3] - bounds[1] + 1) < 2) continue;   // a speck
                float[] area = {bounds[1] * toPoints, bounds[0] * toPoints, (bounds[3] + 1) * toPoints, (bounds[2] + 1) * toPoints};
                if (textShare(area, textA) + textShare(area, textB) < 0.3f) areas.add(area);
            }
        }
        return areas;
    }

    /** Collects the changed squares touching this one (gaps of one square bridged); returns {top, left, bottom, right}. */
    private static int[] grow(boolean[][] changed, boolean[][] seen, int startRow, int startCol) {
        int rows = changed.length, cols = changed[0].length;
        int[] bounds = {startRow, startCol, startRow, startCol};
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.add(new int[] {startRow, startCol});
        seen[startRow][startCol] = true;
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (int dr = -2; dr <= 2; dr++) {
                for (int dc = -2; dc <= 2; dc++) {
                    int r = at[0] + dr, c = at[1] + dc;
                    if (r < 0 || c < 0 || r >= rows || c >= cols || seen[r][c] || !changed[r][c]) continue;
                    seen[r][c] = true;
                    queue.add(new int[] {r, c});
                    bounds[0] = Math.min(bounds[0], r);
                    bounds[1] = Math.min(bounds[1], c);
                    bounds[2] = Math.max(bounds[2], r);
                    bounds[3] = Math.max(bounds[3], c);
                }
            }
        }
        return bounds;
    }

    /** How much of the area (left, top, right, bottom) words cover, 0 to 1. */
    private static float textShare(float[] area, List<Word> words) {
        float covered = 0;
        for (Word w : words) {
            float x = Math.min(area[2], w.right()) - Math.max(area[0], w.left());
            float y = Math.min(area[3], w.bottom()) - Math.max(area[1], w.top());
            if (x > 0 && y > 0) covered += x * y;
        }
        float size = (area[2] - area[0]) * (area[3] - area[1]);
        return size <= 0 ? 1 : Math.min(1, covered / size);
    }

    private static BufferedImage resized(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    // ── The report ───────────────────────────────────────────────────────

    /** Writes the report to {@code target}; returns the line for the result screen. */
    private String writeReport(PDDocument before, PDDocument after, Side a, Side b, Comparison found, boolean visual,
                               String originalName, String newName, Path target, Progress progress,
                               double from, double share) throws IOException {
        PDFRenderer drawBefore = new PDFRenderer(before), drawAfter = new PDFRenderer(after);
        List<Pair> pairs = found.pairs();
        List<List<float[]>> pictures = new ArrayList<>();
        List<Pair> changedPairs = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            if (progress.isCancelled()) throw new CancellationException();
            Pair pair = pairs.get(i);
            List<float[]> areas = List.of();
            if (visual && pair.original() >= 0 && pair.changed() >= 0) {
                progress.update(from + share * 0.4 * i / pairs.size(), "Comparing page " + (pair.changed() + 1) + " as a picture");
                areas = visualChanges(drawBefore, pair.original(), drawAfter, pair.changed(),
                        a.pageSizes()[pair.original()], b.pageSizes()[pair.changed()],
                        a.wordsOn(pair.original()), b.wordsOn(pair.changed()));
            }
            boolean differs = pair.original() < 0 || pair.changed() < 0 || found.removedOn(pair.original()) > 0
                              || found.addedOn(pair.changed()) > 0 || !areas.isEmpty();
            if (differs) {
                changedPairs.add(pair);
                pictures.add(areas);
            }
        }

        try (PDDocument report = new PDDocument()) {
            Fonts fonts = new Fonts(report);
            summary(report, fonts, originalName, newName, a, b, found, changedPairs, pictures);
            for (int i = 0; i < changedPairs.size(); i++) {
                if (progress.isCancelled()) throw new CancellationException();
                Pair pair = changedPairs.get(i);
                progress.update(from + share * (0.4 + 0.6 * i / changedPairs.size()), "Drawing page " + (i + 1) + " of the report");
                pairPage(report, fonts, drawBefore, drawAfter, a, b, found, pair, pictures.get(i));
            }
            report.getDocumentInformation().setTitle("Comparison of " + originalName + " and " + newName);
            report.save(target.toFile());
        }
        return changedPairs.isEmpty() ? "No differences found"
                : summaryLine(found, changedPairs, pictures);
    }

    private static String summaryLine(Comparison found, List<Pair> changed, List<List<float[]>> pictures) {
        List<String> parts = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        for (Pair p : changed) if (p.changed() >= 0) pages.add(p.changed() + 1);
        if (!pages.isEmpty()) parts.add((pages.size() == 1 ? "Page " : "Pages ") + PageRanges.format(pages) + " changed");
        long removedPages = changed.stream().filter(p -> p.changed() < 0).count();
        if (removedPages > 0) parts.add(Format.count((int) removedPages, "page", "pages") + " removed");
        parts.add(Format.count(found.removed().size(), "word", "words") + " removed, " + found.added().size() + " added");
        int areas = pictures.stream().mapToInt(List::size).sum();
        if (areas > 0) parts.add(Format.count(areas, "picture change", "picture changes"));
        return String.join(" · ", parts);
    }

    private void summary(PDDocument report, Fonts fonts, String originalName, String newName, Side a, Side b,
                         Comparison found, List<Pair> changed, List<List<float[]>> pictures) throws IOException {
        PDPage page = new PDPage(new PDRectangle(REPORT_WIDTH, REPORT_HEIGHT));
        report.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(report, page);
        float y = REPORT_HEIGHT - 56;
        text(cs, fonts, true, 22, MARGIN + 16, y, "Comparison", Color.BLACK);
        y -= 30;
        text(cs, fonts, false, 12, MARGIN + 16, y, "Original: " + originalName + " (" + Format.count(a.pages(), "page", "pages") + ")", Color.DARK_GRAY);
        y -= 18;
        text(cs, fonts, false, 12, MARGIN + 16, y, "New: " + newName + " (" + Format.count(b.pages(), "page", "pages") + ")", Color.DARK_GRAY);
        y -= 18;
        text(cs, fonts, false, 10, MARGIN + 16, y, "Made by NdalamoPDF on " + LocalDate.now(), Color.GRAY);
        y -= 34;
        if (changed.isEmpty()) {
            text(cs, fonts, true, 15, MARGIN + 16, y, "No differences found.", Color.BLACK);
            y -= 20;
            text(cs, fonts, false, 11, MARGIN + 16, y, a.lines().isEmpty() && b.lines().isEmpty()
                    ? "Neither file has text to compare, and the pages look the same."
                    : "The text is the same, and the pages look the same.", Color.DARK_GRAY);
            cs.close();
            return;
        }
        text(cs, fonts, true, 15, MARGIN + 16, y, summaryLine(found, changed, pictures), Color.BLACK);
        y -= 26;
        // The key to the colours
        float x = MARGIN + 16;
        for (Object[] key : new Object[][] {{REMOVED, "Removed (shown on the original)"}, {ADDED, "Added (shown on the new file)"},
                {VISUAL_CHANGE, "Picture or drawing changed"}}) {
            Color colour = (Color) key[0];
            highlight(report, cs, colour, x, y - 2, 14, 11, colour == VISUAL_CHANGE);
            text(cs, fonts, false, 11, x + 20, y, (String) key[1], Color.DARK_GRAY);
            x += 230;
        }
        y -= 30;
        text(cs, fonts, false, 11, MARGIN + 16, y, "Pages without changes are left out. Each page below is shown side by side on the following pages:", Color.DARK_GRAY);
        y -= 22;
        for (int i = 0; i < changed.size(); i++) {
            if (y < 40) {
                cs.close();
                page = new PDPage(new PDRectangle(REPORT_WIDTH, REPORT_HEIGHT));
                report.addPage(page);
                cs = new PDPageContentStream(report, page);
                y = REPORT_HEIGHT - 48;
            }
            text(cs, fonts, false, 11, MARGIN + 28, y, "•  " + describe(changed.get(i), found, pictures.get(i).size()), Color.BLACK);
            y -= 17;
        }
        cs.close();
    }

    /** "Page 3 → page 4: 2 words removed, 5 added" and the like. */
    private static String describe(Pair pair, Comparison found, int pictureChanges) {
        if (pair.changed() < 0) return "Original page " + (pair.original() + 1) + ": removed";
        if (pair.original() < 0) return "New page " + (pair.changed() + 1) + ": added";
        String where = pair.original() == pair.changed() ? "Page " + (pair.changed() + 1)
                : "Original page " + (pair.original() + 1) + " → new page " + (pair.changed() + 1);
        List<String> parts = new ArrayList<>();
        long removed = found.removedOn(pair.original()), added = found.addedOn(pair.changed());
        if (removed > 0) parts.add(Format.count((int) removed, "word", "words") + " removed");
        if (added > 0) parts.add(Format.count((int) added, "word", "words") + " added");
        if (pictureChanges > 0) parts.add(Format.count(pictureChanges, "picture change", "picture changes"));
        return where + ": " + String.join(", ", parts);
    }

    private void pairPage(PDDocument report, Fonts fonts, PDFRenderer drawBefore, PDFRenderer drawAfter, Side a, Side b,
                          Comparison found, Pair pair, List<float[]> pictureChanges) throws IOException {
        PDPage page = new PDPage(new PDRectangle(REPORT_WIDTH, REPORT_HEIGHT));
        report.addPage(page);
        float half = (REPORT_WIDTH - 2 * MARGIN - GAP) / 2;
        try (PDPageContentStream cs = new PDPageContentStream(report, page)) {
            text(cs, fonts, false, 10, MARGIN, REPORT_HEIGHT - 22, describe(pair, found, pictureChanges.size()), Color.GRAY);
            String left = pair.original() < 0 ? "Original: no matching page" : "Original · page " + (pair.original() + 1);
            String right = pair.changed() < 0 ? "New: page removed" : "New · page " + (pair.changed() + 1);
            text(cs, fonts, true, 13, MARGIN, REPORT_HEIGHT - 44, left, Color.BLACK);
            text(cs, fonts, true, 13, MARGIN + half + GAP, REPORT_HEIGHT - 44, right, Color.BLACK);
            side(report, cs, fonts, drawBefore, a, pair.original(), MARGIN, half, found.removed(), REMOVED, pictureChanges);
            side(report, cs, fonts, drawAfter, b, pair.changed(), MARGIN + half + GAP, half, found.added(), ADDED, pictureChanges);
        }
    }

    /** One half of a report page: the page drawn as a picture, with its changes marked on top. */
    private void side(PDDocument report, PDPageContentStream cs, Fonts fonts, PDFRenderer renderer, Side side, int pageIndex,
                      float x, float width, Set<Word> marked, Color colour, List<float[]> pictureChanges) throws IOException {
        float top = REPORT_HEIGHT - HEADER, height = top - MARGIN;
        if (pageIndex < 0) {
            highlight(report, cs, new Color(236, 238, 242), x, MARGIN, width, height, false);
            text(cs, fonts, false, 11, x + 16, top - 30, "Nothing here: see the other side.", Color.GRAY);
            return;
        }
        float[] size = side.pageSizes()[pageIndex];
        float scale = Math.min(width / size[0], height / size[1]);
        float w = size[0] * scale, h = size[1] * scale;
        float left = x + (width - w) / 2, bottom = top - h;
        BufferedImage picture = renderer.renderImageWithDPI(pageIndex, PAGE_DPI, ImageType.RGB);
        PDImageXObject image = JPEGFactory.createFromImage(report, picture, 0.82f);
        cs.drawImage(image, left, bottom, w, h);
        cs.setStrokingColor(new Color(200, 204, 212));
        cs.setLineWidth(0.6f);
        cs.addRect(left, bottom, w, h);
        cs.stroke();
        for (Word word : marked) {
            if (word.page() != pageIndex) continue;
            highlight(report, cs, colour, left + (word.left() - 1) * scale, top - (word.bottom() + 1.5f) * scale,
                    (word.right() - word.left() + 2) * scale, (word.bottom() - word.top() + 2.5f) * scale, false);
        }
        for (float[] area : pictureChanges) {
            highlight(report, cs, VISUAL_CHANGE, left + area[0] * scale, top - area[3] * scale,
                    (area[2] - area[0]) * scale, (area[3] - area[1]) * scale, true);
        }
    }

    /** A see-through coloured box (or, for picture changes, an outline with a light fill). */
    private static void highlight(PDDocument report, PDPageContentStream cs, Color colour, float x, float y, float w, float h,
                                  boolean outline) throws IOException {
        PDExtendedGraphicsState seeThrough = new PDExtendedGraphicsState();
        seeThrough.setNonStrokingAlphaConstant(outline ? 0.15f : 0.35f);
        cs.saveGraphicsState();
        cs.setGraphicsStateParameters(seeThrough);
        cs.setNonStrokingColor(colour);
        cs.addRect(x, y, w, h);
        cs.fill();
        cs.restoreGraphicsState();
        if (outline) {
            cs.setStrokingColor(colour);
            cs.setLineWidth(1.2f);
            cs.addRect(x, y, w, h);
            cs.stroke();
        }
    }

    private static void text(PDPageContentStream cs, Fonts fonts, boolean bold, float size, float x, float y, String text,
                             Color colour) throws IOException {
        text = text.replaceAll("\\p{Cntrl}", " ");
        cs.beginText();
        cs.setFont(bold ? fonts.bold(text) : fonts.regular(text), size);
        cs.setNonStrokingColor(colour);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    /** IBM Plex Sans like the app, embedded once; Noto Sans for text Plex can't write (some file names). */
    private static final class Fonts {
        private final PDDocument doc;
        private PDFont regular, bold, notoRegular, notoBold;

        Fonts(PDDocument doc) {
            this.doc = doc;
        }

        PDFont regular(String text) throws IOException {
            if (regular == null) regular = PageCanvas.load(doc, "/fonts/IBMPlexSans-Regular.ttf");
            if (canWrite(regular, text)) return regular;
            if (notoRegular == null) notoRegular = PageCanvas.load(doc, "/fonts/NotoSans-Regular.ttf");
            return notoRegular;
        }

        PDFont bold(String text) throws IOException {
            if (bold == null) bold = PageCanvas.load(doc, "/fonts/IBMPlexSans-Bold.ttf");
            if (canWrite(bold, text)) return bold;
            if (notoBold == null) notoBold = PageCanvas.load(doc, "/fonts/NotoSans-Bold.ttf");
            return notoBold;
        }

        private static boolean canWrite(PDFont font, String text) {
            try {
                font.encode(text);
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }
    }
}
