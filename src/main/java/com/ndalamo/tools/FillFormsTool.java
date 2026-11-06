package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.FormField;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Fill forms (SPEC §4): writes answers into a PDF form's fields, the kind people
 * get from a municipality, a bank or a university. The user fills them in on the
 * tool screen (FormDialog); the answers are matched to fields by name, so several
 * copies of the same form can be filled in one go. Optionally the answers are
 * locked into the page ("flattened") so they can't be changed any more.
 *
 * Forms made with Adobe's XFA (dynamic forms) can only be filled in Adobe's own
 * software; those get a plain explanation. The input file is never changed.
 */
public class FillFormsTool implements PdfTool {

    /** Option key: the answers, a Map of field name to answer. */
    static final String ANSWERS = "answers";
    static final String FLATTEN = "flatten";

    @Override public String id() { return "fill-forms"; }
    @Override public String name() { return "Fill forms"; }
    @Override public String description() { return "Complete PDF forms"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "filled"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(ANSWERS, "Answers", OptionField.Type.FORM, null),
                new OptionField(FLATTEN, "Lock the answers in", OptionField.Type.CHECKBOX, false));
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        return options.get(ANSWERS) instanceof Map<?, ?> answers && !answers.isEmpty()
                ? null : "Fill in the form first: click “Fill in the form”.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Fill in " + Format.count(files, "form", "forms");
    }

    @Override
    public String workingLabel(int files) {
        return "Filling in " + Format.count(files, "form", "forms") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "form", "forms") + " filled in";
    }

    /**
     * The fields of a PDF's form, in page order (top to bottom), for the tool screen to
     * show. Empty if it has no form. Throws if it's an XFA form, which can't be filled here.
     */
    public static List<FormField> fields(Path pdf) throws IOException {
        try (PDDocument doc = PdfFiles.open(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) return List.of();
            if (form.xfaIsDynamic()) throw new IOException(XFA_MESSAGE);
            List<FormField> fields = new ArrayList<>();
            List<float[]> places = new ArrayList<>();          // page, top, left, for sorting
            for (PDField field : form.getFieldTree()) {
                FormField f = describe(doc, field);
                if (f == null) continue;
                fields.add(f);
                PDAnnotationWidget widget = field.getWidgets().isEmpty() ? null : field.getWidgets().get(0);
                float top = widget == null || widget.getRectangle() == null ? 0 : -widget.getRectangle().getUpperRightY();
                float left = widget == null || widget.getRectangle() == null ? 0 : widget.getRectangle().getLowerLeftX();
                places.add(new float[]{f.page(), top, left});
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i -> places.get(i)[0])
                    .thenComparingDouble(i -> places.get(i)[1]).thenComparingDouble(i -> places.get(i)[2]));
            return order.stream().map(fields::get).toList();
        }
    }

    private static final String XFA_MESSAGE = "This form was made with Adobe's XFA format, which only Adobe's own "
            + "software can fill in. Your file was not changed.";

    /** A field the user can fill in, or null for ones they can't (buttons, signature boxes, groups). */
    private static FormField describe(PDDocument doc, PDField field) {
        String label = field.getAlternateFieldName() != null && !field.getAlternateFieldName().isBlank()
                ? field.getAlternateFieldName().strip() : readable(field.getPartialName());
        int page = 1;
        if (!field.getWidgets().isEmpty() && field.getWidgets().get(0).getPage() != null) {
            page = doc.getPages().indexOf(field.getWidgets().get(0).getPage()) + 1;
        }
        boolean readOnly = field.isReadOnly();
        String name = field.getFullyQualifiedName();
        if (field instanceof PDTextField text) {
            return new FormField(name, label, text.isMultiline() ? FormField.Kind.LONG_TEXT : FormField.Kind.TEXT,
                    List.of(), text.getValue() == null ? "" : text.getValue(), page, readOnly);
        }
        if (field instanceof PDCheckBox box) {
            return new FormField(name, label, FormField.Kind.TICK_BOX, List.of(), String.valueOf(box.isChecked()), page, readOnly);
        }
        if (field instanceof PDRadioButton radio) {
            List<String> choices = new ArrayList<>(radio.getExportValues().isEmpty() ? radio.getOnValues() : radio.getExportValues());
            String value = radio.getValue();
            return new FormField(name, label, FormField.Kind.RADIO, choices, value == null || value.equals("Off") ? "" : value,
                    page, readOnly);
        }
        if (field instanceof PDChoice choice) {
            List<String> choices = choice.getOptionsDisplayValues();
            String value = choice.getValue().isEmpty() ? "" : choice.getValue().get(0);
            return new FormField(name, label, FormField.Kind.LIST, choices, value, page, readOnly);
        }
        return null;
    }

    /** A field's internal name made readable: "first_name" and "firstName" both become "First name". */
    static String readable(String name) {
        if (name == null || name.isBlank()) return "(unnamed field)";
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[_.-]+", " ").strip().toLowerCase();
        return spaced.isEmpty() ? name : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        Map<?, ?> answers = options.get(ANSWERS) instanceof Map<?, ?> m ? m : Map.of();
        boolean flatten = Boolean.TRUE.equals(options.get(FLATTEN));

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.1, "Filling in");
            progress.update((double) i / inputs.size(), "Filling in " + input.getFileName());
            try (PDDocument doc = PdfFiles.open(input)) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                if (form == null || form.getFields().isEmpty()) {
                    results.add(new FileResult(input, null, Files.size(input), 0, FileResult.Status.SKIPPED,
                            "This PDF has no form to fill in, so nothing was saved."));
                    progress.file(i, 1, "Skipped");
                    continue;
                }
                if (form.xfaIsDynamic()) {
                    results.add(PdfFiles.failed(input, XFA_MESSAGE));
                    progress.file(i, 1, "Couldn't finish");
                    continue;
                }
                // A form with both kinds keeps an XFA copy of its answers; drop it so readers show these answers.
                if (form.hasXFA()) form.getCOSObject().removeItem(COSName.XFA);

                int filled = 0, notInFont = 0;
                for (Map.Entry<?, ?> answer : answers.entrySet()) {
                    PDField field = form.getField(String.valueOf(answer.getKey()));
                    if (field == null || field.isReadOnly()) continue;
                    String value = String.valueOf(answer.getValue());
                    try {
                        write(field, value);
                    } catch (IllegalArgumentException | IOException e) {
                        // Usually a letter the form's font can't show (e.g. Tshivenda ṱ in a Helvetica form):
                        // keep the answer and let the reader draw it with its own font.
                        field.getCOSObject().setString(COSName.V, value);
                        form.setNeedAppearances(true);
                        notInFont++;
                    }
                    filled++;
                }
                if (flatten) form.flatten(form.getFields(), true);

                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                String note = Format.count(filled, "answer", "answers") + " filled in" + (flatten ? " · locked in" : "")
                        + (notInFont > 0 ? " · " + Format.count(notInFont, "answer has", "answers have")
                        + " letters the form's font can't show; PDF readers will show them in their own font" : "");
                boolean nothing = filled == 0;
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        nothing ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS,
                        nothing ? "None of the answers matched this form's fields; it was saved unchanged" : note));
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Writes one answer the way its field takes it. */
    private static void write(PDField field, String value) throws IOException {
        if (field instanceof PDCheckBox box) {
            if (Boolean.parseBoolean(value)) box.check();
            else box.unCheck();
        } else if (field instanceof PDRadioButton radio) {
            if (value.isEmpty()) radio.setValue("Off");
            else radio.setValue(value);
        } else if (field instanceof PDChoice choice) {
            // The user picked what the list shows; the form may store a different value behind it.
            List<String> shown = choice.getOptionsDisplayValues(), stored = choice.getOptionsExportValues();
            int index = shown.indexOf(value);
            if (!value.isEmpty()) choice.setValue(index >= 0 && index < stored.size() ? stored.get(index) : value);
        } else {
            field.setValue(value);
        }
    }
}
