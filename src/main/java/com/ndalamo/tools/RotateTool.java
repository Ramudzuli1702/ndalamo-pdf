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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rotate pages: turns all pages, or only the ones listed, by 90° or 180°.
 * Only the page's rotation setting changes — the content isn't redrawn, so
 * nothing loses quality. Each input gets its own "_rotated" copy.
 */
public class RotateTool implements PdfTool {

    static final String TURN = "turn";
    static final String RIGHT = "Right (clockwise)";
    static final String UPSIDE_DOWN = "Upside down";
    static final String LEFT = "Left (anticlockwise)";

    static final String WHICH = "which";
    static final String ALL = "All pages";
    static final String SOME = "Only some pages";
    static final String PAGES = "pages";

    @Override public String id() { return "rotate"; }
    @Override public String name() { return "Rotate pages"; }
    @Override public String description() { return "Turn pages the right way up"; }
    @Override public String category() { return "Organize"; }
    @Override public String outputSuffix() { return "rotated"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(TURN, "Turn pages", OptionField.Type.CHOICE, List.of(RIGHT, UPSIDE_DOWN, LEFT), RIGHT),
                new OptionField(WHICH, "Which pages", OptionField.Type.CHOICE, List.of(ALL, SOME), ALL),
                new OptionField(PAGES, "Pages to turn", OptionField.Type.TEXT, "")
                        .hint("e.g. 1, 3-5").shownWhen(WHICH, SOME));
    }

    @Override public Preview preview() { return Preview.ROTATE; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<Integer> previewPages(Map<String, Object> options, int pageCount) {
        if (!SOME.equals(options.get(WHICH))) return allPages(pageCount);
        try {
            return PageRanges.pages(PageRanges.parse((String) options.get(PAGES), pageCount));
        } catch (IllegalArgumentException e) {
            return List.of();      // half-typed or doesn't fit this file: nothing highlighted yet
        }
    }

    @Override
    public int previewTurn(Map<String, Object> options) {
        return degrees(options);
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        return SOME.equals(options.get(WHICH)) ? PageRanges.check((String) options.get(PAGES)) : null;
    }

    @Override
    public String buttonLabel(int files) {
        return "Rotate " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Rotating " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " rotated";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        int degrees = degrees(options);
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Rotating " + input.getFileName());
            progress.file(i, 0.1, "Working");
            int resultsBefore = results.size();
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = SOME.equals(options.get(WHICH))
                        ? PageRanges.pages(PageRanges.parse((String) options.get(PAGES), doc.getNumberOfPages()))
                        : allPages(doc.getNumberOfPages());
                for (int number : pages) {
                    PDPage page = doc.getPage(number - 1);
                    page.setRotation((page.getRotation() + degrees) % 360);   // adds to any turn it already had
                }
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        FileResult.Status.SUCCESS, Format.count(pages.size(), "page", "pages") + " turned"));
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

    private static int degrees(Map<String, Object> options) {
        Object turn = options.get(TURN);
        if (UPSIDE_DOWN.equals(turn)) return 180;
        if (LEFT.equals(turn)) return 270;
        return 90;
    }

    private static List<Integer> allPages(int pageCount) {
        List<Integer> pages = new ArrayList<>();
        for (int p = 1; p <= pageCount; p++) pages.add(p);
        return pages;
    }

    /** Short status for this file's card on the progress screen. */
    private static String fileStatus(List<FileResult> forThisFile) {
        if (forThisFile.isEmpty()) return "Stopped";
        FileResult first = forThisFile.get(0);
        return first.getStatus() == FileResult.Status.FAILED ? "Couldn't finish" : "Done";
    }
}
