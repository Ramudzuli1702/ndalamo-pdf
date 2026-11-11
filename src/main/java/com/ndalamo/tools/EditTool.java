package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.PdfEdits;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edit PDF: add and remove text. In the editor the user drags over text to select it
 * and removes it (or picks out text marked with a highlighter), and clicks on a page to
 * type new text. This tool applies those edits (a PdfEdits) to the file; the page work
 * is in TextEdits. Removed letters are taken out of the file, not covered. The input
 * file is never changed; with several files, each gets the same edits.
 */
public class EditTool implements PdfTool {

    /** Option key: the edits made in the editor (a PdfEdits). */
    static final String EDITS = "edits";

    @Override public String id() { return "edit"; }
    @Override public String name() { return "Edit PDF"; }
    @Override public String description() { return "Add or remove text"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "edited"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(new OptionField(EDITS, "Your changes", OptionField.Type.EDITS, null));
    }

    @Override
    public String saveToNote() {
        return "Removed text is taken out of the new file for good, not just covered. Your original is not changed.";
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        return options.get(EDITS) instanceof PdfEdits edits && !edits.isEmpty()
                ? null : "Open the editor and add or remove some text first.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Save changes · " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Editing " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " edited";
    }

    // ── For the editor (read-only, off the JavaFX thread) ────────────────

    /** A letter the editor can select, in page fractions as shown (0,0 top left). */
    public record Letter(String text, double left, double top, double right, double bottom) {}

    /** The letters on a page (1-based) that can be removed. Scanned pages have none. */
    public static List<Letter> letters(Path file, int page) throws IOException {
        try (PDDocument doc = PdfFiles.open(file)) {
            return TextEdits.letters(doc, page - 1).stream()
                    .map(l -> new Letter(l.text(), l.left(), l.top(), l.right(), l.bottom())).toList();
        }
    }

    /**
     * What the editor needs for one page (1-based): its size as shown, in points (for drawing
     * added text at the right size), its removable letters and its highlighter marks.
     */
    public record PageInfo(double width, double height, List<Letter> letters, List<double[]> highlights) {}

    public static PageInfo page(Path file, int page) throws IOException {
        try (PDDocument doc = PdfFiles.open(file)) {
            var p = doc.getPage(page - 1);
            boolean sideways = Math.floorMod(p.getRotation(), 180) == 90;
            double w = p.getCropBox().getWidth(), h = p.getCropBox().getHeight();
            List<Letter> letters = TextEdits.letters(doc, page - 1).stream()
                    .map(l -> new Letter(l.text(), l.left(), l.top(), l.right(), l.bottom())).toList();
            return new PageInfo(sideways ? h : w, sideways ? w : h, letters, TextEdits.highlights(p));
        }
    }

    /** Areas marked with a highlighter on a page (1-based), each {left, top, right, bottom}. */
    public static List<double[]> highlights(Path file, int page) throws IOException {
        try (PDDocument doc = PdfFiles.open(file)) {
            return TextEdits.highlights(doc.getPage(page - 1));
        }
    }

    // ── Running ──────────────────────────────────────────────────────────

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        if (!(options.get(EDITS) instanceof PdfEdits edits) || edits.isEmpty()) {
            throw new ToolProblem("Open the editor and add or remove some text first. Your files were not changed.", null);
        }
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Editing " + input.getFileName());
            progress.file(i, 0.2, "Editing");
            try (PDDocument doc = PdfFiles.open(input)) {
                int pages = doc.getNumberOfPages();
                int removed = 0, added = 0, missed = 0;
                for (int p = 1; p <= pages; p++) {
                    int page = p;
                    List<PdfEdits.Removal> areas = edits.removals().stream().filter(r -> r.page() == page).toList();
                    List<PdfEdits.Addition> texts = edits.additions().stream().filter(a -> a.page() == page).toList();
                    if (!areas.isEmpty()) {
                        int n = TextEdits.remove(doc, doc.getPage(p - 1), areas);
                        removed += n;
                        if (n == 0) missed += areas.size();
                    }
                    TextEdits.add(doc, doc.getPage(p - 1), texts);
                    added += texts.size();
                    if (progress.isCancelled()) break;
                }
                if (progress.isCancelled()) break;
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pages);
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        missed > 0 && removed == 0 && added == 0 ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS,
                        note(removed, added, missed)));
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    private static String note(int removed, int added, int missed) {
        List<String> parts = new ArrayList<>();
        if (removed > 0) parts.add("Removed " + Format.count(removed, "letter", "letters"));
        if (added > 0) parts.add("added " + Format.count(added, "text", "texts"));
        String note = String.join(", ", parts);
        if (!note.isEmpty()) note = Character.toUpperCase(note.charAt(0)) + note.substring(1);
        if (missed > 0) {
            note += (note.isEmpty() ? "" : " · ") + "no removable text found in "
                    + Format.count(missed, "area", "areas") + " (scanned pages are pictures: use Redact for those)";
        }
        return note;
    }
}
