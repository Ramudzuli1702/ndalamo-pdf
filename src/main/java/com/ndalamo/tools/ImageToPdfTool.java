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
 * Image to PDF: puts pictures (JPG, PNG, BMP, GIF, TIFF) into one PDF, a page each,
 * in the order shown (SPEC §4). Pages are A4, Letter or the picture's own shape;
 * wide pictures get landscape pages. Phone photos come out the right way up, and
 * big photos are shrunk to a sensible sharpness so the PDF stays easy to upload.
 */
public class ImageToPdfTool implements PdfTool {

    static final String SIZE = "size";
    static final String A4 = "A4";
    static final String LETTER = "Letter";
    static final String SAME = "Same shape as the picture";

    static final String MARGIN = "margin";
    static final String NO_MARGIN = "None";
    static final String SMALL_MARGIN = "Small";
    static final String LARGE_MARGIN = "Large";

    static final String SHRINK = "shrink";

    @Override public String id() { return "image-to-pdf"; }
    @Override public String name() { return "Image to PDF"; }
    @Override public String description() { return "JPG, PNG, scans"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "converted"; }
    @Override public boolean oneOutput() { return true; }
    @Override public Preview preview() { return Preview.MERGE; }

    @Override
    public Set<String> fileTypes() {
        Set<String> types = new HashSet<>(FileInspector.IMAGE_EXTENSIONS);
        types.addAll(Set.of("tif", "tiff"));
        return types;
    }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(SIZE, "Page size", OptionField.Type.CHOICE, List.of(A4, LETTER, SAME), A4)
                        .describe("Turns landscape for wide pictures", "US paper size", "No white space around the picture"),
                new OptionField(MARGIN, "Margin", OptionField.Type.CHOICE,
                        List.of(NO_MARGIN, SMALL_MARGIN, LARGE_MARGIN), SMALL_MARGIN),
                new OptionField(SHRINK, "Shrink big photos to a sensible size (smaller PDF)",
                        OptionField.Type.CHECKBOX, true).advanced());
    }

    @Override
    public String buttonLabel(int files) {
        return "Make a PDF from " + Format.count(files, "picture", "pictures");
    }

    @Override
    public String workingLabel(int files) {
        return "Making a PDF from " + Format.count(files, "picture", "pictures") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "picture", "pictures") + " in one PDF";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        ImagePages.Layout layout = layout(options);
        long sizeBefore = 0;
        for (Path input : inputs) sizeBefore += Files.size(input);

        List<String> skipped = new ArrayList<>();
        Map<Path, Integer> firstPageOf = new HashMap<>();
        try (PDDocument pdf = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            for (int i = 0; i < inputs.size(); i++) {
                if (progress.isCancelled()) return List.of();      // nothing written yet
                Path input = inputs.get(i);
                progress.update((double) i / inputs.size(), "Adding " + input.getFileName());
                try {
                    firstPageOf.put(input, pdf.getNumberOfPages());
                    ImagePages.addTo(pdf, input, layout);
                } catch (IOException | RuntimeException e) {
                    skipped.add(input.getFileName() + " (not a picture that can be read)");
                }
            }
            int pages = pdf.getNumberOfPages();
            if (pages == 0) {
                return List.of(FileResult.failed(inputs.get(0), sizeBefore,
                        "None of the pictures could be read: " + String.join(", ", skipped) + ". Your files were not changed."));
            }
            @SuppressWarnings("unchecked")
            List<PageRef> order = (List<PageRef>) options.get(PAGE_ORDER);
            if (order != null) PdfFiles.reorderPages(pdf, order, firstPageOf);

            progress.update(0.95, "Saving the PDF");
            Path output = saveTo.outputFor(inputs.get(0), outputSuffix(), "pdf", pages);
            SafeSave.write(output, temp -> pdf.save(temp.toFile()));
            progress.update(1, "Done");

            String note = Format.count(pages, "page", "pages") + (skipped.isEmpty() ? "" : " · skipped " + String.join(", ", skipped));
            return List.of(new FileResult(inputs.get(0), output, sizeBefore, Files.size(output),
                    skipped.isEmpty() ? FileResult.Status.SUCCESS : FileResult.Status.PARTIAL, note));
        }
    }

    static ImagePages.Layout layout(Map<String, Object> options) {
        Object size = options.get(SIZE);
        ImagePages.PageSize pageSize = LETTER.equals(size) ? ImagePages.PageSize.LETTER
                : SAME.equals(size) ? ImagePages.PageSize.SAME_AS_PICTURE : ImagePages.PageSize.A4;
        Object margin = options.get(MARGIN);
        float points = LARGE_MARGIN.equals(margin) ? 36 : NO_MARGIN.equals(margin) ? 0 : 18;
        int shrinkDpi = Boolean.FALSE.equals(options.get(SHRINK)) ? 0 : 200;
        return new ImagePages.Layout(pageSize, points, shrinkDpi);
    }
}
