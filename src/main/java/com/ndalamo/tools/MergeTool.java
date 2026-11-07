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
 * Pictures (JPG, PNG, BMP, GIF, TIFF) are added as A4 pages, scaled to fit.
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
        types.addAll(Set.of("pdf", "tif", "tiff"));
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
                    firstPageOf.put(input, merged.getNumberOfPages());
                    if (isImage(input)) {
                        ImagePages.addTo(merged, input, ImagePages.FILL_A4);    // phone photos the right way up, TIFFs page by page
                    } else {
                        PDDocument source = PdfFiles.open(input);
                        sources.add(source);
                        merger.appendDocument(merged, source);
                    }
                } catch (IOException e) {
                    skipped.add(name + " (" + PdfFiles.problem(e).replaceFirst("^is ", "") + ")");
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
            if (order != null) PdfFiles.reorderPages(merged, order, firstPageOf);

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

    private static boolean isImage(Path file) {
        String ext = FileInspector.extension(file);
        return FileInspector.IMAGE_EXTENSIONS.contains(ext) || ext.equals("tif") || ext.equals("tiff");
    }
}
