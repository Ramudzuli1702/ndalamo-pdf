package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.WorkspacePage;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crop PDF (SPEC §4): trims pages to the part worth keeping. Either automatically,
 * cutting away the white margins around each page's content, or to an area the user
 * draws on the first page, the same on every chosen page.
 *
 * Cropping sets the page's crop box: readers and printers show only that part, but
 * the rest is still in the file. Redact is the tool for removing things for good.
 * The input file is never changed.
 */
public class CropTool implements PdfTool {

    static final String MODE = "mode";
    static final String TRIM = "Trim the white margins";
    static final String DRAWN = "Keep the area I draw";
    /** Option key: the drawn area as fractions of the page as shown, {left, top, right, bottom}. */
    static final String AREA = "area";
    static final String PAGES = "pages";

    /** Pages are checked for white margins at this resolution: enough to find the edges, quick to draw. */
    private static final int TRIM_DPI = 48;
    /** A pixel lighter than this counts as white (paper, or a scan's off-white). */
    private static final int WHITE = 238;
    /** Space left around the content when trimming, as a share of the page. */
    private static final double BREATHING_ROOM = 0.012;

    @Override public String id() { return "crop"; }
    @Override public String name() { return "Crop PDF"; }
    @Override public String description() { return "Trim margins or keep an area"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "cropped"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(MODE, "How to crop", OptionField.Type.CHOICE, List.of(TRIM, DRAWN), TRIM)
                        .describe("Each page cut to its content, like a scanned receipt",
                                "The same part of every page, chosen on the first page"),
                new OptionField(AREA, "Area to keep", OptionField.Type.AREA, null).shownWhen(MODE, DRAWN),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced());
    }

    @Override
    public String saveToNote() {
        return "Cropping hides the rest of each page; it is still in the file. To remove something for good, use Redact.";
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        if (DRAWN.equals(options.get(MODE)) && !(options.get(AREA) instanceof double[])) {
            return "Draw the area to keep first: click “Draw the area”.";
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
        return "Crop " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Cropping " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " cropped";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean drawn = DRAWN.equals(options.get(MODE));
        double[] area = options.get(AREA) instanceof double[] a ? a : null;
        if (drawn && area == null) throw new ToolProblem("Draw the area to keep first. Your files were not changed.", null);
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
                PDFRenderer renderer = drawn ? null : new PDFRenderer(doc);
                int cropped = 0, blank = 0;
                for (int n = 0; n < pages.size(); n++) {
                    if (progress.isCancelled()) return results;      // nothing saved for this file
                    progress.file(i, (n + 0.5) / pages.size() * 0.9, "Page " + pages.get(n) + " of " + doc.getNumberOfPages());
                    PDPage page = doc.getPage(pages.get(n) - 1);
                    double[] keep = drawn ? area : content(renderer, pages.get(n) - 1);
                    if (keep == null) {
                        blank++;                                       // nothing on it to crop to
                        continue;
                    }
                    WorkspacePage.Crop crop = WorkspacePage.Crop.fromShown(keep[0], keep[1], keep[2], keep[3], page.getRotation());
                    page.setCropBox(cropBox(page.getCropBox(), crop));
                    cropped++;
                }
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                String note = Format.count(cropped, "page", "pages") + " cropped"
                        + (blank > 0 ? " · " + Format.count(blank, "blank page", "blank pages") + " left as they were" : "");
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        cropped == 0 ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS,
                        cropped == 0 ? "Every page was blank, so nothing was cropped" : note));
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

    /**
     * Where a page's content is, as fractions of the page as shown {left, top, right,
     * bottom}, with a little room around it. Null for a blank page.
     */
    static double[] content(PDFRenderer renderer, int pageIndex) throws IOException {
        BufferedImage picture = renderer.renderImageWithDPI(pageIndex, TRIM_DPI, ImageType.GRAY);
        int w = picture.getWidth(), h = picture.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        var raster = picture.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (raster.getSample(x, y, 0) < WHITE) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        return new double[]{
                Math.max(0, (double) minX / w - BREATHING_ROOM), Math.max(0, (double) minY / h - BREATHING_ROOM),
                Math.min(1, (maxX + 1.0) / w + BREATHING_ROOM), Math.min(1, (maxY + 1.0) / h + BREATHING_ROOM)};
    }

    /** The part of the page's current crop box that the crop keeps. */
    private static PDRectangle cropBox(PDRectangle box, WorkspacePage.Crop crop) {
        float x = box.getLowerLeftX(), y = box.getLowerLeftY(), w = box.getWidth(), h = box.getHeight();
        return new PDRectangle((float) (x + crop.left() * w), (float) (y + crop.bottom() * h),
                (float) ((crop.right() - crop.left()) * w), (float) ((crop.top() - crop.bottom()) * h));
    }
}
