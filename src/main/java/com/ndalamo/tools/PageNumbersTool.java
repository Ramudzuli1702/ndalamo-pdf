package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.PageStamp;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Page numbers: writes a number on each page, in one of six places and three styles
 * ("3", "Page 3 of 9", "- 3 -"). Numbers count every page, so with "skip the first
 * page" the second page still says 2 — like a cover page in a printed report.
 */
public class PageNumbersTool implements PdfTool {

    static final String POSITION = "position";
    static final String BOTTOM_CENTRE = "Bottom centre";
    static final String BOTTOM_RIGHT = "Bottom right";
    static final String BOTTOM_LEFT = "Bottom left";
    static final String TOP_CENTRE = "Top centre";
    static final String TOP_RIGHT = "Top right";
    static final String TOP_LEFT = "Top left";

    static final String STYLE = "style";
    static final String PLAIN = "1, 2, 3";
    static final String PAGE_OF = "Page 1 of 9";
    static final String DASHES = "- 1 -";

    static final String START = "start";
    static final String SKIP_FIRST = "skipFirst";
    static final String SIZE = "size";

    private static final float MARGIN = 28;      // about 1 cm from the edge

    @Override public String id() { return "page-numbers"; }
    @Override public String name() { return "Page numbers"; }
    @Override public String description() { return "Position, style, start number"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "numbered"; }
    @Override public Preview preview() { return Preview.STAMP; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(POSITION, "Position", OptionField.Type.CHOICE,
                        List.of(BOTTOM_CENTRE, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_CENTRE, TOP_RIGHT, TOP_LEFT), BOTTOM_CENTRE),
                new OptionField(STYLE, "Style", OptionField.Type.CHOICE, List.of(PLAIN, PAGE_OF, DASHES), PLAIN),
                new OptionField(START, "Start counting at", OptionField.Type.NUMBER, 1),
                new OptionField(SKIP_FIRST, "Leave the first page without a number (e.g. a cover)",
                        OptionField.Type.CHECKBOX, false),
                new OptionField(SIZE, "Text size (points)", OptionField.Type.NUMBER, 11).advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        Double start = (Double) options.get(START);
        if (start == null || start < 0 || start != Math.floor(start)) return "Start counting at a whole number, like 1.";
        Double size = (Double) options.get(SIZE);
        if (size == null || size < 6 || size > 48) return "Text size should be between 6 and 48 points.";
        return null;
    }

    @Override
    public PageStamp previewStamp(Map<String, Object> options, int page, int pageCount) {
        if (checkOptions(options) != null) return null;
        if (page == 1 && Boolean.TRUE.equals(options.get(SKIP_FIRST))) return null;
        String position = (String) options.get(POSITION);
        double size = (Double) options.get(SIZE) / 595;    // as a share of an A4 page's width
        return new PageStamp(label(options, page, pageCount), position.startsWith("Top") ? "top" : "bottom",
                horizontal(position), 0, 1, size, "#16181D", false);
    }

    @Override
    public String buttonLabel(int files) {
        return "Number " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Numbering " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return "Page numbers added to " + Format.count(files, "file", "files");
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        String position = (String) options.get(POSITION);
        float size = ((Double) options.get(SIZE)).floatValue();
        boolean skipFirst = Boolean.TRUE.equals(options.get(SKIP_FIRST));

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Numbering " + input.getFileName());
            progress.file(i, 0.1, "Working");
            try (PDDocument doc = PdfFiles.open(input)) {
                PDFont font = PageCanvas.font(doc, false, label(options, doc.getNumberOfPages(), doc.getNumberOfPages()));
                int pageCount = doc.getNumberOfPages();
                for (int p = 1; p <= pageCount; p++) {
                    if (progress.isCancelled()) return results;
                    if (p == 1 && skipFirst) continue;
                    String text = label(options, p, pageCount);
                    float width = PageCanvas.textWidth(font, text, size);
                    PageCanvas.drawOn(doc, doc.getPage(p - 1), (content, w, h) -> {
                        float x = switch (horizontal(position)) {
                            case "left" -> MARGIN;
                            case "right" -> w - MARGIN - width;
                            default -> (w - width) / 2;
                        };
                        float y = position.startsWith("Top") ? h - MARGIN - size : MARGIN;
                        content.beginText();
                        content.setFont(font, size);
                        content.newLineAtOffset(x, y);
                        content.showText(text);
                        content.endText();
                    });
                    progress.file(i, (double) p / pageCount * 0.9, "Numbering pages · " + Math.round(90.0 * p / pageCount) + "%");
                }
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pageCount);
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                        Format.count(skipFirst ? pageCount - 1 : pageCount, "page", "pages") + " numbered"));
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** "3", "Page 3 of 9" or "- 3 -", counting from the chosen start number. */
    static String label(Map<String, Object> options, int page, int pageCount) {
        int start = ((Double) options.get(START)).intValue();
        int number = start + page - 1;
        int last = start + pageCount - 1;
        Object style = options.get(STYLE);
        if (PAGE_OF.equals(style)) return "Page " + number + " of " + last;
        if (DASHES.equals(style)) return "- " + number + " -";
        return String.valueOf(number);
    }

    private static String horizontal(String position) {
        if (position.endsWith("left")) return "left";
        if (position.endsWith("right")) return "right";
        return "centre";
    }
}
