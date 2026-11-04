package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.PageRanges.Range;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Split PDF: turns one PDF into several, in one of four ways (SPEC §4):
 * by page ranges ("1-3, 5, 8-10" → three files), every N pages, every page,
 * or by largest file size (for upload portals).
 *
 * Each input's parts go into a new folder next to it, e.g. Assignment2_split/,
 * named after their pages ("Assignment2_pages 1-3.pdf"), so splitting a long
 * file doesn't fill the original folder with dozens of files. The input is
 * only read; if Cancel is pressed partway through a file, that file's parts
 * are deleted again.
 */
public class SplitTool implements PdfTool {

    static final String MODE = "mode";
    static final String BY_RANGES = "Page ranges";
    static final String EVERY_N = "Every N pages";
    static final String EVERY_PAGE = "Every page";
    static final String BY_SIZE = "File size";

    static final String RANGES = "ranges";
    static final String PAGES_PER_FILE = "pagesPerFile";
    static final String MAX_MB = "maxMb";

    @Override public String id() { return "split"; }
    @Override public String name() { return "Split PDF"; }
    @Override public String description() { return "By range, every page or size"; }
    @Override public String category() { return "Organize"; }
    @Override public String outputSuffix() { return "pages 1-3"; }   // shown on the tool screen as an example

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(MODE, "Split by", OptionField.Type.CHOICE,
                        List.of(BY_RANGES, EVERY_N, EVERY_PAGE, BY_SIZE), BY_RANGES),
                new OptionField(RANGES, "Each range becomes its own file", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5, 8-10").shownWhen(MODE, BY_RANGES),
                new OptionField(PAGES_PER_FILE, "Pages per file", OptionField.Type.NUMBER, 2)
                        .shownWhen(MODE, EVERY_N),
                new OptionField(MAX_MB, "Largest file size (MB)", OptionField.Type.NUMBER, 2)
                        .shownWhen(MODE, BY_SIZE));
    }

    @Override public Preview preview() { return Preview.SPLIT; }
    @Override public String pagesOption() { return RANGES; }

    @Override
    public List<Range> previewParts(Map<String, Object> options, int pageCount) {
        if (checkOptions(options) != null) return List.of();      // half-typed: show pages without groups
        Object mode = options.get(MODE);
        if (EVERY_PAGE.equals(mode)) return everyN(pageCount, 1);
        if (EVERY_N.equals(mode)) return everyN(pageCount, ((Double) options.get(PAGES_PER_FILE)).intValue());
        if (BY_RANGES.equals(mode)) {
            try {
                return PageRanges.parse((String) options.get(RANGES), pageCount);
            } catch (IllegalArgumentException e) {
                return List.of();
            }
        }
        return null;     // by size: the parts are only known once the pages are measured
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        Object mode = options.get(MODE);
        if (BY_RANGES.equals(mode)) return PageRanges.check((String) options.get(RANGES));
        if (EVERY_N.equals(mode)) {
            Double n = (Double) options.get(PAGES_PER_FILE);
            if (n == null || n < 1 || n != Math.floor(n)) return "Pages per file must be a whole number, like 2 or 10.";
        }
        if (BY_SIZE.equals(mode)) {
            Double mb = (Double) options.get(MAX_MB);
            if (mb == null || mb <= 0) return "Type the largest size in MB, like 2 or 4.5.";
        }
        return null;
    }

    @Override
    public String saveToNote() {
        return "Each file's parts go in a new folder, e.g. Assignment2_split.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Split " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Splitting " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " split";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Splitting " + input.getFileName());
            progress.file(i, 0.1, "Working");
            int resultsBefore = results.size();
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Range> parts = parts(doc, options);
                results.addAll(saveParts(doc, input, parts, saveTo, progress));
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage().replaceAll("\\.$", "")));
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
            }
            progress.file(i, 1, fileStatus(results.subList(resultsBefore, results.size())));
        }
        progress.update(1, "Done");
        return results;
    }

    /** Which pages go into each new file. */
    private List<Range> parts(PDDocument doc, Map<String, Object> options) throws IOException {
        int pageCount = doc.getNumberOfPages();
        Object mode = options.get(MODE);
        if (BY_RANGES.equals(mode)) return PageRanges.parse((String) options.get(RANGES), pageCount);
        if (EVERY_PAGE.equals(mode)) return everyN(pageCount, 1);
        if (EVERY_N.equals(mode)) return everyN(pageCount, ((Double) options.get(PAGES_PER_FILE)).intValue());
        return bySize(doc, (long) ((Double) options.get(MAX_MB) * 1024 * 1024));
    }

    private static List<Range> everyN(int pageCount, int n) {
        List<Range> ranges = new ArrayList<>();
        for (int from = 1; from <= pageCount; from += n) {
            ranges.add(new Range(from, Math.min(from + n - 1, pageCount)));
        }
        return ranges;
    }

    /**
     * Groups pages so each file stays under the size limit. First guesses from each
     * page's size on its own, then checks each group's real size and moves pages to
     * the next group if needed. (Pages often share fonts and images, so the real size
     * of a group is usually smaller than the sum of its pages.)
     */
    private List<Range> bySize(PDDocument doc, long limit) throws IOException {
        int pageCount = doc.getNumberOfPages();
        long[] pageSize = new long[pageCount + 1];
        for (int p = 1; p <= pageCount; p++) {
            try (PDDocument single = extract(doc, new Range(p, p))) {
                pageSize[p] = PdfFiles.savedSize(single);
            }
        }

        List<Range> ranges = new ArrayList<>();
        int from = 1;
        while (from <= pageCount) {
            int to = from;
            long total = pageSize[from];
            while (to < pageCount && total + pageSize[to + 1] <= limit) total += pageSize[++to];
            // Check the real size; shrink until it fits (a single oversized page stays on its own).
            while (to > from) {
                try (PDDocument part = extract(doc, new Range(from, to))) {
                    if (PdfFiles.savedSize(part) <= limit) break;
                }
                to--;
            }
            ranges.add(new Range(from, to));
            from = to + 1;
        }
        return ranges;
    }

    private List<FileResult> saveParts(PDDocument doc, Path input, List<Range> parts, SaveTo saveTo,
                                       Progress progress) throws IOException {
        Path folder = saveTo.folderFor(input).resolve(stem(input) + "_split");
        SaveTo intoFolder = saveTo.inFolder(folder);
        List<FileResult> results = new ArrayList<>();
        for (Range range : parts) {
            if (progress.isCancelled()) {
                // Stopped halfway through this file: take back its parts, so no half-split file is left.
                for (FileResult r : results) Files.deleteIfExists(r.getOutput());
                deleteIfEmpty(folder);
                return List.of();
            }
            Path output = intoFolder.outputFor(input, range.label(), "pdf", range.size());
            try (PDDocument part = extract(doc, range)) {
                SafeSave.write(output, temp -> part.save(temp.toFile()));
            }
            results.add(new FileResult(input, output, 0, Files.size(output), FileResult.Status.SUCCESS,
                    Format.count(range.size(), "page", "pages") + " · from " + input.getFileName()));
        }
        return results;
    }

    private static PDDocument extract(PDDocument doc, Range range) throws IOException {
        Splitter splitter = new Splitter();
        splitter.setStreamCacheCreateFunction(IOUtils.createTempFileOnlyStreamCache());
        splitter.setStartPage(range.from());
        splitter.setEndPage(range.to());
        splitter.setSplitAtPage(range.size());
        return splitter.split(doc).get(0);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteIfEmpty(Path folder) throws IOException {
        try (var contents = Files.list(folder)) {
            if (contents.findAny().isEmpty()) Files.delete(folder);
        }
    }

    /** Short status for this file's card on the progress screen. */
    private static String fileStatus(List<FileResult> forThisFile) {
        if (forThisFile.isEmpty()) return "Stopped";
        FileResult first = forThisFile.get(0);
        return first.getStatus() == FileResult.Status.FAILED ? "Couldn't finish" : "Done";
    }
}
