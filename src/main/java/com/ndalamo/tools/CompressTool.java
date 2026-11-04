package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compress PDF: makes files smaller by shrinking and re-saving their pictures,
 * which is where nearly all the size of scans and slide decks is (SPEC §4).
 *
 * Modes:
 * - Recommended: pictures down to 150 dpi, good JPEG quality.
 * - Strong: 100 dpi, lower quality.
 * - Target size: tries steadily stronger settings until the file is under the
 *   limit (e.g. 2 MB for an upload portal). It never goes below 72 dpi, because
 *   text in scans becomes hard to read; if that's still too big, it keeps the
 *   smallest readable version and reports PARTIAL ("Reached 2.6 MB…").
 *
 * Text and drawings are left alone, so they stay sharp. Black-and-white scans
 * (1-bit) and pictures with transparency are skipped: they're either already
 * small or would look wrong as JPEG. If compressing doesn't help, the output is
 * an unchanged copy. The input is only read.
 */
public class CompressTool implements PdfTool {

    static final String MODE = "mode";
    static final String RECOMMENDED = "Recommended";
    static final String STRONG = "Strong";
    static final String TARGET = "Target size";
    static final String TARGET_MB = "targetMb";
    static final String DOWNSCALE = "downscale";
    static final String GREYSCALE = "greyscale";
    static final String REMOVE_METADATA = "removeMetadata";

    /** One set of settings: how far to shrink pictures and how hard to compress them. */
    record Level(int dpi, float jpegQuality) {}

    static final Level RECOMMENDED_LEVEL = new Level(150, 0.75f);
    static final Level STRONG_LEVEL = new Level(100, 0.5f);
    /** Tried in order for Target size; the last one is the smallest that stays readable. */
    static final List<Level> TARGET_STEPS = List.of(
            new Level(150, 0.75f), new Level(120, 0.65f), new Level(100, 0.55f),
            new Level(85, 0.45f), new Level(72, 0.35f));

    private final boolean toSize;

    /** The "Compress PDF" card. */
    public CompressTool() {
        this(false);
    }

    /** @param toSize true for the "Compress to size" card, which starts with Target size chosen */
    public CompressTool(boolean toSize) {
        this.toSize = toSize;
    }

    @Override public String id() { return toSize ? "compress-to-size" : "compress"; }
    @Override public String name() { return toSize ? "Compress to size" : "Compress PDF"; }
    @Override public String description() { return toSize ? "e.g. under 2 MB for uploads" : "Smaller files, same pages"; }
    @Override public String category() { return "Optimize"; }
    @Override public String outputSuffix() { return "compressed"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(MODE, "Compression", OptionField.Type.CHOICE,
                        List.of(RECOMMENDED, STRONG, TARGET), toSize ? TARGET : RECOMMENDED)
                        .describe("Good quality, good size", "Smallest file, lower image quality",
                                "For upload portals with a size limit"),
                new OptionField(TARGET_MB, "MB per file", OptionField.Type.NUMBER, 2).shownWhen(MODE, TARGET),
                new OptionField(DOWNSCALE, "Downscale images above 150 dpi", OptionField.Type.CHECKBOX, true).advanced(),
                new OptionField(GREYSCALE, "Convert to greyscale", OptionField.Type.CHECKBOX, false).advanced(),
                new OptionField(REMOVE_METADATA, "Remove metadata", OptionField.Type.CHECKBOX, false).advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        if (TARGET.equals(options.get(MODE))) {
            Double mb = (Double) options.get(TARGET_MB);
            if (mb == null || mb <= 0) return "Type the size to aim for in MB, like 2 or 4.5.";
        }
        return null;
    }

    @Override
    public String buttonLabel(int files) {
        return "Compress " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Compressing " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " compressed";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        Object mode = options.get(MODE);
        List<Level> steps = TARGET.equals(mode) ? TARGET_STEPS
                : STRONG.equals(mode) ? List.of(STRONG_LEVEL) : List.of(RECOMMENDED_LEVEL);
        Double targetMb = TARGET.equals(mode) ? (Double) options.get(TARGET_MB) : null;
        Long target = targetMb != null ? (long) (targetMb * 1024 * 1024) : null;

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            try {
                FileResult result = compressOne(input, i, inputs.size(), steps, target, targetMb, options, saveTo, progress);
                if (result == null) break;          // cancelled partway: nothing written for this file
                results.add(result);
                progress.file(i, 1, result.getStatus() == FileResult.Status.SUCCESS
                        ? "Done · " + Format.sizeChange(result.getSizeBefore(), result.getSizeAfter())
                        : "Partly done · " + Format.sizeChange(result.getSizeBefore(), result.getSizeAfter()));
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Compresses one file, trying each step in turn until it's small enough. Null if cancelled. */
    private FileResult compressOne(Path input, int index, int fileCount, List<Level> steps, Long target, Double targetMb,
                                   Map<String, Object> options, SaveTo saveTo, Progress progress) throws IOException {
        long before = Files.size(input);
        boolean downscale = !Boolean.FALSE.equals(options.get(DOWNSCALE));
        boolean greyscale = Boolean.TRUE.equals(options.get(GREYSCALE));
        boolean removeMetadata = Boolean.TRUE.equals(options.get(REMOVE_METADATA));

        if (target != null && before <= target && !greyscale && !removeMetadata) {
            Path output = saveTo.outputFor(input, outputSuffix(), "pdf", 0);
            SafeSave.write(output, temp -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING));
            return new FileResult(input, output, before, before, FileResult.Status.SUCCESS,
                    "Already under " + Format.megabytes(targetMb) + " · saved an unchanged copy");
        }

        for (int s = 0; s < steps.size(); s++) {
            Level level = steps.get(s);
            boolean lastStep = s == steps.size() - 1;
            try (PDDocument doc = PdfFiles.open(input)) {
                int pages = doc.getNumberOfPages();
                int step = s;
                boolean finished = shrinkImages(doc, level, downscale, greyscale, page -> {
                    double withinFile = (step + (double) page / pages) / steps.size() * 0.95;
                    progress.file(index, withinFile, step == 0
                            ? "Compressing images · " + Math.round(100 * withinFile) + "%"
                            : "Still over the target — trying stronger settings · " + Math.round(100 * withinFile) + "%");
                    progress.update((index + withinFile) / fileCount, "Compressing " + input.getFileName());
                }, progress);
                if (!finished) return null;
                if (removeMetadata) removeMetadata(doc);

                long size = PdfFiles.savedSize(doc);
                if (target != null && size > target && !lastStep) continue;     // not small enough yet: try harder

                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pages);
                if (size >= before && !greyscale && !removeMetadata) {
                    // Compressing made it bigger (it was already efficient): keep the original as it is.
                    SafeSave.write(output, temp -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING));
                    return result(input, output, before, target, targetMb, "Already as small as it gets · saved an unchanged copy");
                }
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                return result(input, output, before, target, targetMb, null);
            }
        }
        throw new IllegalStateException("no compression steps");   // unreachable: the last step always saves
    }

    private static FileResult result(Path input, Path output, long before, Long target, Double targetMb, String note)
            throws IOException {
        long after = Files.size(output);
        if (target != null && after > target) {
            return new FileResult(input, output, before, after, FileResult.Status.PARTIAL,
                    "Reached " + Format.size(after) + " — any smaller and the pages would be hard to read. "
                            + "Try splitting it into parts.");
        }
        if (note == null) {
            note = target != null ? "Under " + Format.megabytes(targetMb)
                    : Format.saving(before, after).isEmpty() ? "Compressed" : Format.saving(before, after);
        }
        return new FileResult(input, output, before, after, FileResult.Status.SUCCESS, note);
    }

    // ── Pictures ─────────────────────────────────────────────────────────

    /** Calls back after each page with the page number. */
    private interface PageDone {
        void after(int page);
    }

    /** Shrinks every picture in the document. Returns false if cancelled. */
    private boolean shrinkImages(PDDocument doc, Level level, boolean downscale, boolean greyscale,
                                 PageDone pageDone, Progress progress) throws IOException {
        // A picture used on many pages (a logo, a slide background) is shrunk once and reused.
        Map<COSStream, PDImageXObject> replacements = new HashMap<>();
        int page = 0;
        for (PDPage p : doc.getPages()) {
            if (progress.isCancelled()) return false;
            shrinkIn(doc, p.getResources(), p.getCropBox(), level, downscale, greyscale, replacements, new HashSet<>());
            pageDone.after(++page);
        }
        return true;
    }

    private void shrinkIn(PDDocument doc, PDResources resources, PDRectangle page, Level level, boolean downscale,
                          boolean greyscale, Map<COSStream, PDImageXObject> replacements, Set<COSStream> visited)
            throws IOException {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject form) {
                // Forms can contain pictures too; the visited set stops forms that include themselves.
                if (visited.add(form.getCOSObject())) {
                    shrinkIn(doc, form.getResources(), page, level, downscale, greyscale, replacements, visited);
                }
            } else if (object instanceof PDImageXObject image) {
                COSStream key = image.getCOSObject();
                PDImageXObject smaller = replacements.containsKey(key)
                        ? replacements.get(key)
                        : shrink(doc, image, page, level, downscale, greyscale);
                replacements.put(key, smaller);
                if (smaller != null) resources.put(name, smaller);
            }
        }
    }

    /** A smaller copy of the picture, or null to leave it as it is. */
    private static PDImageXObject shrink(PDDocument doc, PDImageXObject image, PDRectangle page, Level level,
                                         boolean downscale, boolean greyscale) throws IOException {
        if (image.isStencil() || image.getBitsPerComponent() == 1) return null;      // black-and-white scans: already tiny
        if (image.getSoftMask() != null || image.getMask() != null) return null;     // transparency would turn black in JPEG
        int width = image.getWidth();
        int height = image.getHeight();
        if ((long) width * height < 128 * 128) return null;                          // icons: not worth it

        BufferedImage picture = image.getImage();
        // We don't know how big the picture is drawn, so assume it fills the page. That can only
        // under-estimate its sharpness, so pictures are never shrunk more than they should be.
        double dpi = Math.max(width / (page.getWidth() / 72.0), height / (page.getHeight() / 72.0));
        double scale = downscale && dpi > level.dpi() ? level.dpi() / dpi : 1;
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(newWidth, newHeight,
                greyscale ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(picture, 0, 0, newWidth, newHeight, null);
        g.dispose();

        PDImageXObject jpeg = JPEGFactory.createFromImage(doc, resized, level.jpegQuality());
        // Keep the original if the new one isn't actually smaller (e.g. it was already a small JPEG).
        return jpeg.getCOSObject().getLength() < image.getCOSObject().getLength() || greyscale ? jpeg : null;
    }

    private static void removeMetadata(PDDocument doc) {
        doc.setDocumentInformation(new PDDocumentInformation());
        doc.getDocumentCatalog().setMetadata(null);
    }
}
