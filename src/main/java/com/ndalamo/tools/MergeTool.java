package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.PageRef;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.services.FileInspector;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge PDF: joins files into one PDF in the order the user arranged them.
 * Images (JPG, PNG, BMP, GIF) are added as A4 pages, scaled to fit.
 *
 * A file that can't be read (damaged, or needs a password) is skipped and named
 * in the result note, so one bad file doesn't spoil the rest. The originals are
 * only read, never changed; the merged file is written with SafeSave.
 */
public class MergeTool implements PdfTool {

    @Override public String id() { return "merge"; }
    @Override public String name() { return "Merge PDF"; }
    @Override public String description() { return "Combine files in any order"; }
    @Override public String category() { return "Organize"; }
    @Override public List<OptionField> optionFields() { return List.of(); }

    @Override
    public Set<String> fileTypes() {
        Set<String> types = new HashSet<>(FileInspector.IMAGE_EXTENSIONS);
        types.add("pdf");
        return types;
    }

    @Override public int minFiles() { return 2; }
    @Override public Preview preview() { return Preview.MERGE; }
    @Override public boolean oneOutput() { return true; }
    @Override public String outputSuffix() { return "merged"; }

    @Override
    public String buttonLabel(int files) {
        return "Merge " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Merging " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " merged into one PDF";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        Path first = inputs.get(0);
        long sizeBefore = 0;
        for (Path input : inputs) sizeBefore += Files.size(input);

        List<String> skipped = new ArrayList<>();
        Map<Path, Integer> firstPageOf = new HashMap<>();    // where each file's pages start in the merged PDF
        List<PDDocument> sources = new ArrayList<>();   // must stay open until the merged file is saved
        PDFMergerUtility merger = new PDFMergerUtility();

        // Temp-file cache throughout: merging big scans on a 4 GB laptop must not run out of memory.
        try (PDDocument merged = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            for (int i = 0; i < inputs.size(); i++) {
                if (progress.isCancelled()) return List.of();   // nothing has been written yet
                Path input = inputs.get(i);
                String name = input.getFileName().toString();
                progress.update((double) i / inputs.size(), "Adding " + name);
                try {
                    PDDocument source = isImage(input) ? imageAsPage(input) : PdfFiles.open(input);
                    sources.add(source);
                    firstPageOf.put(input, merged.getNumberOfPages());
                    merger.appendDocument(merged, source);
                } catch (IOException e) {
                    skipped.add(name + " (" + PdfFiles.problem(e) + ")");
                }
            }

            int pages = merged.getNumberOfPages();
            if (pages == 0) {
                return List.of(FileResult.failed(first, sizeBefore,
                        "None of the files could be read: " + String.join(", ", skipped)
                                + ". Your files were not changed."));
            }
            if (progress.isCancelled()) return List.of();

            @SuppressWarnings("unchecked")
            List<PageRef> order = (List<PageRef>) options.get(PAGE_ORDER);
            if (order != null) reorder(merged, order, firstPageOf);

            progress.update(0.95, "Saving the merged PDF");
            Path output = saveTo.outputFor(first, outputSuffix(), "pdf", pages);
            SafeSave.write(output, temp -> merged.save(temp.toFile()));
            progress.update(1, "Done");

            String note = skipped.isEmpty()
                    ? pages + " pages"
                    : pages + " pages · skipped " + String.join(", ", skipped);
            FileResult.Status status = skipped.isEmpty() ? FileResult.Status.SUCCESS : FileResult.Status.PARTIAL;
            return List.of(new FileResult(first, output, sizeBefore, Files.size(output), status, note));
        } finally {
            for (PDDocument source : sources) source.close();
        }
    }

    /**
     * Puts the merged pages in the order dragged into place in the preview. The files
     * are merged normally first (which keeps links and bookmarks working), then the
     * finished pages are rearranged. Pages of skipped files are simply left out.
     */
    private static void reorder(PDDocument merged, List<PageRef> order, Map<Path, Integer> firstPageOf) {
        List<PDPage> current = new ArrayList<>();
        merged.getPages().forEach(current::add);
        List<PDPage> wanted = new ArrayList<>();
        for (PageRef ref : order) {
            Integer start = firstPageOf.get(ref.file());
            if (start != null && start + ref.page() - 1 < current.size()) wanted.add(current.get(start + ref.page() - 1));
        }
        for (PDPage page : current) {
            if (!wanted.contains(page)) wanted.add(page);    // anything the order didn't mention stays, at the end
        }
        for (PDPage page : current) merged.getPages().remove(page);
        for (PDPage page : wanted) merged.getPages().add(page);
    }

    private static boolean isImage(Path file) {
        return FileInspector.IMAGE_EXTENSIONS.contains(FileInspector.extension(file));
    }

    /** One A4 page with the image centred and scaled to fit (landscape for wide images). */
    private static PDDocument imageAsPage(Path file) throws IOException {
        PDDocument doc = new PDDocument();
        try {
            PDImageXObject image = PDImageXObject.createFromFileByContent(file.toFile(), doc);
            boolean wide = image.getWidth() > image.getHeight();
            PDRectangle a4 = wide ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            PDPage page = new PDPage(a4);
            doc.addPage(page);

            float scale = Math.min(a4.getWidth() / image.getWidth(), a4.getHeight() / image.getHeight());
            float w = image.getWidth() * scale;
            float h = image.getHeight() * scale;
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, (a4.getWidth() - w) / 2, (a4.getHeight() - h) / 2, w, h);
            }
            return doc;
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }
}
