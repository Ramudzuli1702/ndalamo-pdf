package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remove pages: saves a copy of each PDF without the pages listed. Refuses to
 * remove every page, since that would leave an empty file. The original keeps
 * all its pages.
 */
public class RemovePagesTool implements PdfTool {

    static final String PAGES = "pages";

    @Override public String id() { return "remove-pages"; }
    @Override public String name() { return "Remove pages"; }
    @Override public String description() { return "Delete pages you don't need"; }
    @Override public String category() { return "Organize"; }
    @Override public String outputSuffix() { return "pages removed"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(new OptionField(PAGES, "Pages to remove", OptionField.Type.TEXT, "").hint("e.g. 2, 5-7"));
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        return PageRanges.check((String) options.get(PAGES));
    }

    @Override
    public String buttonLabel(int files) {
        return "Remove pages from " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Removing pages from " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return "Pages removed from " + Format.count(files, "file", "files");
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Removing pages from " + input.getFileName());
            try (PDDocument doc = PdfFiles.open(input)) {
                int before = doc.getNumberOfPages();
                List<Integer> remove = PageRanges.pages(PageRanges.parse((String) options.get(PAGES), before));
                if (remove.size() >= before) {
                    results.add(PdfFiles.failed(input, "That would remove every page, leaving an empty file"));
                    continue;
                }
                // Highest page first, so removing one doesn't shift the numbers of the others.
                for (int k = remove.size() - 1; k >= 0; k--) doc.removePage(remove.get(k) - 1);

                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                        Format.count(remove.size(), "page", "pages") + " removed · "
                                + Format.count(doc.getNumberOfPages(), "page", "pages") + " left"));
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage().replaceAll("\\.$", "")));
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
            }
        }
        progress.update(1, "Done");
        return results;
    }
}
