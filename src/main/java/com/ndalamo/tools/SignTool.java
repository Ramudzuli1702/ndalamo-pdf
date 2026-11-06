package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Signature;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sign PDF (SPEC §4): puts a signature on a page, drawn with the mouse or a pen,
 * typed in a handwriting font (Dancing Script, decision 013), or taken from a photo
 * of a signature on paper. It's a picture of a signature, like signing a printout
 * and scanning it, not a certificate-based digital signature (SPEC: visual only for
 * v1). Optionally today's date is written under it. The input file is never changed.
 */
public class SignTool implements PdfTool {

    /** Option key: the signature and where it goes (a Signature). */
    static final String SIGNATURE = "signature";
    static final String DATE = "date";

    @Override public String id() { return "sign"; }
    @Override public String name() { return "Sign PDF"; }
    @Override public String description() { return "Draw, type or upload"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "signed"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(SIGNATURE, "Your signature", OptionField.Type.SIGNATURE, null),
                new OptionField(DATE, "Write today's date under it", OptionField.Type.CHECKBOX, false));
    }

    @Override
    public String saveToNote() {
        return "This adds a picture of your signature, like signing a printout. It is not a certificate-based digital signature.";
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        return options.get(SIGNATURE) instanceof Signature ? null : "Create your signature and place it first.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Sign " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Signing " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " signed";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        if (!(options.get(SIGNATURE) instanceof Signature signature)) {
            throw new ToolProblem("Create your signature and place it first. Your files were not changed.", null);
        }
        boolean date = Boolean.TRUE.equals(options.get(DATE));
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(signature.png()));
        if (picture == null) throw new ToolProblem("The signature picture couldn't be read. Please create it again.", null);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.2, "Signing");
            progress.update((double) i / inputs.size(), "Signing " + input.getFileName());
            try (PDDocument doc = PdfFiles.open(input)) {
                // A file with fewer pages than the one placed on gets the signature on its last page.
                int pageNumber = Math.min(signature.page(), doc.getNumberOfPages());
                PDPage page = doc.getPage(pageNumber - 1);
                PDImageXObject image = LosslessFactory.createFromImage(doc, picture);
                PDFont font = date ? PageCanvas.font(doc, false, today) : null;
                PageCanvas.drawOn(doc, page, (content, width, height) -> {
                    float w = (float) (signature.width() * width);
                    float h = w * picture.getHeight() / picture.getWidth();
                    float x = (float) (signature.left() * width);
                    float top = (float) (signature.top() * height);
                    float y = height - top - h;                              // drawing measures from the bottom
                    content.drawImage(image, x, y, w, h);
                    if (font != null) {
                        float size = 9;
                        content.beginText();
                        content.setFont(font, size);
                        content.setNonStrokingColor(0.25f, 0.27f, 0.31f);
                        content.newLineAtOffset(x, y - size * 1.4f);
                        content.showText(today);
                        content.endText();
                    }
                });
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                String note = "Signed on page " + pageNumber + (date ? " · dated " + today : "")
                        + (pageNumber < signature.page() ? " (its last page)" : "");
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS, note));
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }
}
