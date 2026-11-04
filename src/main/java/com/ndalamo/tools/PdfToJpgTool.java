package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PDF to JPG: saves each page as a picture (72, 150 or 300 dpi), or pulls out the
 * pictures already inside the PDF (SPEC §4). The pictures go in a new folder next
 * to the PDF, e.g. Report_jpg/Report_page 3.jpg — or, if asked, in one ZIP file.
 * The PDF is only read. If Cancel is pressed partway through a file, that file's
 * pictures are removed again.
 */
public class PdfToJpgTool implements PdfTool {

    static final String WHAT = "what";
    static final String PAGES_AS_PICTURES = "Each page as a picture";
    static final String PICTURES_INSIDE = "The pictures inside the PDF";

    static final String QUALITY = "quality";
    static final String SCREEN = "Screen (72 dpi)";
    static final String GOOD = "Good (150 dpi)";
    static final String PRINT = "Print (300 dpi)";

    static final String PAGES = "pages";
    static final String ZIP = "zip";

    @Override public String id() { return "pdf-to-jpg"; }
    @Override public String name() { return "PDF to JPG"; }
    @Override public String description() { return "Pages as images"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "page 1"; }    // shown on the tool screen as an example
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String outputExtension() { return "jpg"; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(WHAT, "Save", OptionField.Type.CHOICE, List.of(PAGES_AS_PICTURES, PICTURES_INSIDE),
                        PAGES_AS_PICTURES).describe("Exactly what you see on each page", "Photos and logos, as they were put in"),
                new OptionField(QUALITY, "Sharpness", OptionField.Type.CHOICE, List.of(SCREEN, GOOD, PRINT), GOOD)
                        .describe("Small files for sharing", "Clear on any screen", "Big files, for printing")
                        .shownWhen(WHAT, PAGES_AS_PICTURES),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced(),
                new OptionField(ZIP, "Put the pictures in one ZIP file", OptionField.Type.CHECKBOX, false).advanced());
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
        return "Pictures go in a new folder next to each PDF, e.g. Report_jpg.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Save pictures from " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Saving pictures from " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return "Pictures saved from " + Format.count(files, "file", "files");
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean extract = PICTURES_INSIDE.equals(options.get(WHAT));
        int dpi = SCREEN.equals(options.get(QUALITY)) ? 72 : PRINT.equals(options.get(QUALITY)) ? 300 : 150;
        boolean zip = Boolean.TRUE.equals(options.get(ZIP));

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Saving pictures from " + input.getFileName());
            progress.file(i, 0.05, "Working");
            List<Path> written = new ArrayList<>();
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pagesToSave(options, doc.getNumberOfPages());
                Path folder = saveTo.folderFor(input).resolve(stem(input) + "_jpg");
                SaveTo intoFolder = saveTo.inFolder(folder);
                int index = i;

                // Each picture is passed to `save` as it's made, so a 300-page PDF never
                // holds more than one page's picture in memory.
                PictureSink save = zip
                        ? null
                        : (name, picture) -> {
                            Path output = intoFolder.outputFor(input, name, "jpg", 0);
                            SafeSave.write(output, temp -> writeJpeg(picture, temp));
                            written.add(output);
                        };
                if (zip) {
                    Path output = saveTo.outputFor(input, "jpg", "zip", 0);
                    List<String> names = new ArrayList<>();
                    SafeSave.write(output, temp -> {
                        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(temp))) {
                            PictureSink intoZip = (name, picture) -> {
                                String entry = uniqueName(names, stem(input) + "_" + name + ".jpg");
                                zipOut.putNextEntry(new ZipEntry(entry));
                                writeJpeg(picture, zipOut);
                                zipOut.closeEntry();
                            };
                            makePictures(doc, pages, extract, dpi, intoZip, progress, index);
                            // Stop SafeSave here, so the half-written ZIP is thrown away, not moved into place.
                            if (progress.isCancelled()) throw new Stopped();
                        }
                    });
                    results.add(new FileResult(input, output, 0, Files.size(output), FileResult.Status.SUCCESS,
                            Format.count(names.size(), "picture", "pictures") + " in a ZIP file"));
                } else {
                    int made = makePictures(doc, pages, extract, dpi, save, progress, index);
                    if (progress.isCancelled()) {
                        for (Path p : written) Files.deleteIfExists(p);       // no half-finished set left behind
                        break;
                    }
                    if (made == 0) {
                        results.add(new FileResult(input, null, Files.size(input), 0, FileResult.Status.SKIPPED,
                                "There are no pictures inside this PDF. Try “Each page as a picture” instead"));
                    }
                    for (Path p : written) {
                        results.add(new FileResult(input, p, 0, Files.size(p), FileResult.Status.SUCCESS,
                                "From " + input.getFileName()));
                    }
                }
                progress.file(i, 1, "Done");
            } catch (Stopped e) {
                break;
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage().replaceAll("\\.$", "")));
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                for (Path p : written) Files.deleteIfExists(p);
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Thrown inside a ZIP save when Cancel is pressed, so the unfinished ZIP is discarded. */
    private static final class Stopped extends RuntimeException {
        Stopped() {
            super(null, null, false, false);
        }
    }

    /** Where each picture goes: a file in the folder, or an entry in the ZIP. */
    private interface PictureSink {
        void save(String name, BufferedImage picture) throws IOException;
    }

    /** Makes the pictures and hands each to the sink. Returns how many were made. */
    private int makePictures(PDDocument doc, List<Integer> pages, boolean extract, int dpi, PictureSink sink,
                             Progress progress, int fileIndex) throws IOException {
        int made = 0;
        if (!extract) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int n = 0; n < pages.size(); n++) {
                if (progress.isCancelled()) return made;
                int page = pages.get(n);
                sink.save("page " + page, renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB));
                made++;
                progress.file(fileIndex, (n + 1.0) / pages.size() * 0.95,
                        "Page " + (n + 1) + " of " + pages.size());
            }
            return made;
        }
        // The same logo on every page is only saved once.
        Set<COSStream> seen = new HashSet<>();
        for (int n = 0; n < pages.size(); n++) {
            if (progress.isCancelled()) return made;
            List<BufferedImage> found = new ArrayList<>();
            collectImages(doc.getPage(pages.get(n) - 1).getResources(), seen, found, new HashSet<>());
            for (BufferedImage picture : found) sink.save("picture " + (++made), picture);
            progress.file(fileIndex, (n + 1.0) / pages.size() * 0.95, "Page " + (n + 1) + " of " + pages.size());
        }
        return made;
    }

    private static void collectImages(PDResources resources, Set<COSStream> seen, List<BufferedImage> found,
                                      Set<COSStream> visitedForms) throws IOException {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject form && visitedForms.add(form.getCOSObject())) {
                collectImages(form.getResources(), seen, found, visitedForms);
            } else if (object instanceof PDImageXObject image && seen.add(image.getCOSObject())) {
                if (image.getWidth() * (long) image.getHeight() >= 32 * 32) found.add(image.getImage());   // skip tiny bullets and lines
            }
        }
    }

    private static List<Integer> pagesToSave(Map<String, Object> options, int pageCount) {
        String pages = (String) options.get(PAGES);
        if (pages == null || pages.isBlank()) {
            List<Integer> all = new ArrayList<>();
            for (int p = 1; p <= pageCount; p++) all.add(p);
            return all;
        }
        return PageRanges.pages(PageRanges.parse(pages, pageCount));
    }

    private static void writeJpeg(BufferedImage picture, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            writeJpeg(picture, out);
        }
    }

    /** JPEG at 90% quality: clearly sharper than Java's default 75%, still compact. */
    private static void writeJpeg(BufferedImage picture, OutputStream out) throws IOException {
        BufferedImage rgb = picture;
        if (picture.getType() != BufferedImage.TYPE_INT_RGB && picture.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            // JPEG can't hold see-through parts or odd colour layouts: draw onto white.
            rgb = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(picture, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        // Don't close the stream: it may be a ZIP that has more entries to come.
        ImageOutputStream imageOut = ImageIO.createImageOutputStream(out);
        try {
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            imageOut.flush();
            writer.dispose();
        }
    }

    private static String uniqueName(List<String> names, String name) {
        String unique = name;
        for (int n = 2; names.contains(unique); n++) unique = name.replace(".jpg", " (" + n + ").jpg");
        names.add(unique);
        return unique;
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
