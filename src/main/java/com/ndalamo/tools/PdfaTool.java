package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF to PDF/A (SPEC §4): makes a PDF/A-2b copy for long-term archiving, the format
 * government departments, courts and universities ask for. PDF/A files carry
 * everything needed to show them the same way decades from now. So the copy gets its
 * fonts embedded (Liberation stands in for Helvetica, Times and Courier, decision 014),
 * a colour profile, and the metadata that says it's PDF/A, and loses what PDF/A
 * forbids: encryption, JavaScript, attachments, hidden annotations.
 *
 * The result is then checked against the rules this tool knows. If it can't comply
 * (e.g. a font with no stand-in), nothing is saved and the reason is given: a file
 * labelled PDF/A that isn't would be worse than none. The input file is never changed.
 */
public class PdfaTool implements PdfTool {

    @Override public String id() { return "pdfa"; }
    @Override public String name() { return "PDF to PDF/A"; }
    @Override public String description() { return "Long-term archiving"; }
    @Override public String category() { return "Optimize"; }
    @Override public String outputSuffix() { return "pdfa"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of();
    }

    @Override
    public String saveToNote() {
        return "Makes PDF/A-2b, the archiving format most offices ask for. Attachments and JavaScript are removed.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Make " + Format.count(files, "PDF/A file", "PDF/A files");
    }

    @Override
    public String workingLabel(int files) {
        return "Making " + Format.count(files, "PDF/A file", "PDF/A files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " made PDF/A";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.05, "Opening");
            progress.update((double) i / inputs.size(), "Converting " + input.getFileName());
            try (PDDocument doc = PdfFiles.open(input)) {
                if (doc.isEncrypted() && !doc.getCurrentAccessPermission().isOwnerPermission()) {
                    // Opens, but with print/copy limits: removing those needs the owner password.
                    results.add(PdfFiles.failed(input, "It has print or copy limits, which PDF/A doesn't allow. "
                            + "Remove them with Unlock first (it needs the full password)."));
                    progress.file(i, 1, "Couldn't finish");
                    continue;
                }
                doc.setAllSecurityToBeRemoved(true);
                progress.file(i, 0.2, "Embedding fonts");
                List<String> cantFix = new ArrayList<>();
                int embedded = embedFonts(doc, cantFix);
                if (progress.isCancelled()) break;
                progress.file(i, 0.6, "Adding the archive details");
                List<String> removed = clean(doc);
                addColourProfile(doc);
                addMetadata(doc);
                doc.setVersion(1.7f);

                List<String> problems = new ArrayList<>(cantFix);
                problems.addAll(check(doc));
                if (!problems.isEmpty()) {
                    results.add(PdfFiles.failed(input, "It can't be made PDF/A, so nothing was saved: "
                            + String.join("; ", problems)));
                    progress.file(i, 1, "Couldn't finish");
                    continue;
                }
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                String note = "PDF/A-2b" + (embedded > 0 ? " · " + Format.count(embedded, "font", "fonts") + " embedded" : "")
                        + (removed.isEmpty() ? "" : " · removed: " + String.join(", ", removed));
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

    // ── Fonts ────────────────────────────────────────────────────────────

    /** Embeds every font that isn't in the file yet. Fonts that can't be are described in {@code cantFix}. */
    private static int embedFonts(PDDocument doc, List<String> cantFix) throws IOException {
        FontEmbedder embedder = new FontEmbedder(doc);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> missing = new LinkedHashSet<>();
        int[] count = {0};
        try {
            for (PDPage page : doc.getPages()) {
                fonts(page.getResources(), seen, font -> {
                    if (FontEmbedder.embedded(font)) return;
                    if (font instanceof PDSimpleFont simple && embedder.embed(simple)) count[0]++;
                    else missing.add(font.getName() == null ? "an unnamed font" : stripTag(font.getName()));
                });
                for (PDAnnotation annotation : page.getAnnotations()) {
                    PDAppearanceStream look = annotation.getNormalAppearanceStream();
                    if (look != null) fonts(look.getResources(), seen, font -> {
                        if (FontEmbedder.embedded(font)) return;
                        if (font instanceof PDSimpleFont simple && embedder.embed(simple)) count[0]++;
                        else missing.add(font.getName() == null ? "an unnamed font" : stripTag(font.getName()));
                    });
                }
            }
        } finally {
            embedder.close();
        }
        if (!missing.isEmpty()) {
            cantFix.add("it uses " + (missing.size() == 1 ? "a font" : "fonts") + " that aren't in the file and have no "
                    + "stand-in here (" + String.join(", ", missing) + ")");
        }
        return count[0];
    }

    private interface FontAction {
        void apply(PDFont font) throws IOException;
    }

    /** Every font used by these resources, and by the drawings (form XObjects) inside them, once each. */
    private static void fonts(PDResources resources, Set<Object> seen, FontAction action) throws IOException {
        if (resources == null || !seen.add(resources.getCOSObject())) return;
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            if (font != null && seen.add(font.getCOSObject())) action.apply(font);
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDFormXObject form) fonts(form.getResources(), seen, action);
        }
    }

    private static String stripTag(String name) {
        return name.contains("+") ? name.substring(name.indexOf('+') + 1) : name;
    }

    // ── Clean-up ─────────────────────────────────────────────────────────

    /** Removes what PDF/A-2b doesn't allow. Returns what was removed, in plain words. */
    private static List<String> clean(PDDocument doc) throws IOException {
        List<String> removed = new ArrayList<>();
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        var names = catalog.getNames();
        if (names != null && names.getJavaScript() != null) {
            names.setJavascript(null);
            removed.add("JavaScript");
        }
        if (names != null && names.getEmbeddedFiles() != null) {
            names.setEmbeddedFiles(null);
            removed.add("attachments");
        }
        catalog.getCOSObject().removeItem(COSName.AA);
        if (catalog.getCOSObject().getDictionaryObject(COSName.OPEN_ACTION) instanceof org.apache.pdfbox.cos.COSDictionary action
                && COSName.JAVA_SCRIPT.equals(action.getCOSName(COSName.S))) {
            catalog.getCOSObject().removeItem(COSName.OPEN_ACTION);
            if (!removed.contains("JavaScript")) removed.add("JavaScript");
        }

        boolean droppedAnnotations = false;
        for (PDPage page : doc.getPages()) {
            page.getCOSObject().removeItem(COSName.AA);
            List<PDAnnotation> keep = new ArrayList<>();
            for (PDAnnotation annotation : page.getAnnotations()) {
                String type = annotation.getSubtype();
                // Sound, video and attached files can't be archived this way.
                if ("Sound".equals(type) || "Movie".equals(type) || "Screen".equals(type) || "FileAttachment".equals(type)
                        || "3D".equals(type) || "RichMedia".equals(type)) {
                    droppedAnnotations = true;
                    continue;
                }
                annotation.getCOSObject().removeItem(COSName.AA);
                annotation.setPrinted(true);                 // PDF/A: annotations print, and nothing is hidden
                annotation.setHidden(false);
                annotation.setInvisible(false);
                annotation.setNoView(false);
                keep.add(annotation);
            }
            page.setAnnotations(keep);
        }
        if (droppedAnnotations) removed.add("sound, video and attached files");

        PDAcroForm form = catalog.getAcroForm();
        if (form != null) {
            if (form.hasXFA()) form.getCOSObject().removeItem(COSName.XFA);
            if (form.getNeedAppearances()) {
                form.refreshAppearances();                   // PDF/A: every field needs its own appearance
                form.setNeedAppearances(false);
            }
        }
        return removed;
    }

    // ── Colour profile and metadata ──────────────────────────────────────

    /** The sRGB colour profile, from Java itself, so colours mean the same on any screen or printer. */
    private static void addColourProfile(PDDocument doc) throws IOException {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        if (!catalog.getOutputIntents().isEmpty()) return;
        byte[] srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        PDOutputIntent intent = new PDOutputIntent(doc, new ByteArrayInputStream(srgb));
        intent.setInfo("sRGB IEC61966-2.1");
        intent.setOutputCondition("sRGB IEC61966-2.1");
        intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
        intent.setRegistryName("http://www.color.org");
        catalog.addOutputIntent(intent);
    }

    /**
     * The XMP metadata that says "PDF/A-2b", with the same title, author and dates as the
     * document information (PDF/A checks they agree).
     */
    private static void addMetadata(PDDocument doc) throws IOException {
        PDDocumentInformation info = doc.getDocumentInformation();
        Calendar now = new GregorianCalendar();
        if (info.getCreationDate() == null) info.setCreationDate(now);
        info.setModificationDate(now);
        info.setProducer("NdalamoPDF");
        // PDF/A wants only what XMP can mirror; drop the old "Trapped" key, which needs special values.
        info.getCOSObject().removeItem(COSName.TRAPPED);

        String xmp = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"\n"
                + "    xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"\n"
                + "    xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n"
                + "    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n"
                + "    xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n"
                + "   <pdfaid:part>2</pdfaid:part>\n"
                + "   <pdfaid:conformance>B</pdfaid:conformance>\n"
                + (info.getTitle() != null ? "   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + xml(info.getTitle())
                + "</rdf:li></rdf:Alt></dc:title>\n" : "")
                + (info.getAuthor() != null ? "   <dc:creator><rdf:Seq><rdf:li>" + xml(info.getAuthor())
                + "</rdf:li></rdf:Seq></dc:creator>\n" : "")
                + (info.getSubject() != null ? "   <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">" + xml(info.getSubject())
                + "</rdf:li></rdf:Alt></dc:description>\n" : "")
                + (info.getKeywords() != null ? "   <pdf:Keywords>" + xml(info.getKeywords()) + "</pdf:Keywords>\n" : "")
                + (info.getCreator() != null ? "   <xmp:CreatorTool>" + xml(info.getCreator()) + "</xmp:CreatorTool>\n" : "")
                + "   <pdf:Producer>NdalamoPDF</pdf:Producer>\n"
                + "   <xmp:CreateDate>" + iso(info.getCreationDate()) + "</xmp:CreateDate>\n"
                + "   <xmp:ModifyDate>" + iso(info.getModificationDate()) + "</xmp:ModifyDate>\n"
                + "  </rdf:Description>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>";
        PDMetadata metadata = new PDMetadata(doc);
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        doc.getDocumentCatalog().setMetadata(metadata);
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String iso(Calendar date) {
        ZonedDateTime when = ZonedDateTime.ofInstant(date.toInstant(), date.getTimeZone().toZoneId());
        return when.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ── Check ────────────────────────────────────────────────────────────

    /**
     * The PDF/A-2b rules this tool can check, on the converted document: fonts embedded,
     * colour profile, PDF/A metadata, no encryption, JavaScript or attachments.
     * Empty if all is well.
     */
    static List<String> check(PDDocument doc) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> notEmbedded = new LinkedHashSet<>();
        for (PDPage page : doc.getPages()) {
            fonts(page.getResources(), seen, font -> {
                // Read the font again from its dictionary: PDFBox keeps the font objects it made before
                // the fonts were embedded, and those still say "not embedded".
                PDFont fresh = PDFontFactory.createFont(font.getCOSObject());
                if (!FontEmbedder.embedded(fresh)) notEmbedded.add(stripTag(String.valueOf(fresh.getName())));
            });
        }
        if (!notEmbedded.isEmpty()) problems.add("fonts not embedded: " + String.join(", ", notEmbedded));
        if (doc.getDocumentCatalog().getOutputIntents().isEmpty()) problems.add("no colour profile");
        PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
        String xmp = metadata == null ? "" : new String(metadata.toByteArray(), StandardCharsets.UTF_8);
        if (!xmp.contains("<pdfaid:part>2</pdfaid:part>")) problems.add("no PDF/A metadata");
        var names = doc.getDocumentCatalog().getNames();
        if (names != null && names.getJavaScript() != null) problems.add("JavaScript");
        if (names != null && names.getEmbeddedFiles() != null) problems.add("attachments");
        return problems;
    }
}
