package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.FormField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FillFormsToolTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Map<String, Object> options(Map<String, String> answers, boolean flatten) {
        Map<String, Object> options = new HashMap<>();
        options.put(FillFormsTool.ANSWERS, answers);
        options.put(FillFormsTool.FLATTEN, flatten);
        return options;
    }

    private static Map<String, String> answers() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("name", "Thandeka Dlamini");
        answers.put("address", "12 Church Street\nThohoyandou");
        answers.put("agree", "true");
        answers.put("payment", "EFT");
        answers.put("province", "Limpopo");
        answers.put("reference", "HACKED");                      // read-only: must not change
        return answers;
    }

    @Test
    void theFieldsAreReadInPageOrderWithTheirLabels() throws Exception {
        List<FormField> fields = FillFormsTool.fields(TestFiles.formPdf(dir, "Application.pdf"));

        assertEquals(List.of("name", "address", "agree", "payment", "province", "reference"),
                fields.stream().map(FormField::name).toList(), "top to bottom, as on the page");
        assertEquals("Full name", fields.get(0).label(), "the tooltip is the friendlier label");
        assertEquals("Address", fields.get(1).label(), "no tooltip: the name, made readable");
        assertEquals(FormField.Kind.LONG_TEXT, fields.get(1).kind());
        assertEquals(FormField.Kind.TICK_BOX, fields.get(2).kind());
        assertEquals(List.of("Cash", "EFT"), fields.get(3).choices());
        assertEquals(List.of("Limpopo", "Gauteng"), fields.get(4).choices(), "what the list shows, not what it stores");
        assertTrue(fields.get(5).readOnly());
        assertEquals("REF-001", fields.get(5).value());
    }

    @Test
    void answersAreWrittenIntoTheForm() throws Exception {
        Path form = TestFiles.formPdf(dir, "Application.pdf");
        byte[] before = TestFiles.bytes(form);

        FileResult result = new FillFormsTool().run(List.of(form), options(answers(), false), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertEquals("5 answers filled in", result.getNote(), "the read-only field isn't counted or changed");
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            assertEquals("Thandeka Dlamini", acro.getField("name").getValueAsString());
            assertTrue(((PDCheckBox) acro.getField("agree")).isChecked());
            assertEquals("EFT", acro.getField("payment").getValueAsString());
            assertEquals(List.of("LP"), ((PDComboBox) acro.getField("province")).getValue(), "stored as the form expects");
            assertEquals("REF-001", acro.getField("reference").getValueAsString());
        }
        assertArrayEquals(before, TestFiles.bytes(form));
    }

    @Test
    void lockingInPutsTheAnswersOnThePage() throws Exception {
        FileResult result = new FillFormsTool().run(List.of(TestFiles.formPdf(dir, "Application.pdf")),
                options(answers(), true), sameFolder, new Progress()).get(0);

        assertTrue(result.getNote().contains("locked in"), result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            assertTrue(acro == null || acro.getFields().isEmpty(), "no fields left to change");
        }
        assertTrue(TestFiles.text(result.getOutput()).contains("Thandeka Dlamini"), "the answer is now part of the page");
    }

    @Test
    void lettersTheFormsFontLacksAreStillSaved() throws Exception {
        Map<String, String> answers = Map.of("name", "Ṱhalitha Ḓivhani");
        FileResult result = new FillFormsTool().run(List.of(TestFiles.formPdf(dir, "Application.pdf")),
                options(answers, false), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.getNote().contains("letters the form's font can't show"), result.getNote());
        try (PDDocument doc = Loader.loadPDF(result.getOutput().toFile())) {
            assertEquals("Ṱhalitha Ḓivhani", doc.getDocumentCatalog().getAcroForm().getField("name").getValueAsString());
        }
    }

    @Test
    void aPdfWithoutAFormIsSkipped() throws Exception {
        Path plain = TestFiles.pdf(dir, "Letter.pdf", "Hello", 1);

        FileResult result = new FillFormsTool().run(List.of(plain), options(answers(), false), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SKIPPED, result.getStatus());
        assertTrue(FillFormsTool.fields(plain).isEmpty());
    }

    @Test
    void fieldNamesAreMadeReadable() {
        assertEquals("First name", FillFormsTool.readable("first_name"));
        assertEquals("First name", FillFormsTool.readable("firstName"));
        assertEquals("Id number", FillFormsTool.readable("ID-number".toLowerCase()));
    }

    @Test
    void answersAreNeededFirst() {
        assertNotNull(new FillFormsTool().checkOptions(options(null, false)));
        assertNull(new FillFormsTool().checkOptions(options(answers(), false)));
    }
}
