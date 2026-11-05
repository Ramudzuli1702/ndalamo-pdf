package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OCR PDF: makes scanned pages searchable (SPEC §4). Each page is drawn at 300 dpi
 * and read by Tesseract; every word it finds is written invisibly on top of the
 * page, exactly where it appears. The page looks the same, but its text can now be
 * searched, selected and copied — and later converted to Word or Excel.
 *
 * Pages that already have text are left as they are (unless the user says
 * otherwise), so a mostly digital PDF with a few scanned pages is quick.
 * Everything runs on this PC; nothing is sent anywhere.
 */
public class OcrTool implements PdfTool {

    static final String LANGUAGE = "language";
    static final String ENGLISH = "English";
    static final String AFRIKAANS = "Afrikaans";
    static final String BOTH = "English and Afrikaans";
    static final String SKIP_TEXT_PAGES = "skipTextPages";

    private static final int DPI = 300;
    /** A page with fewer letters than this is treated as a scan, even if it has a stray caption. */
    private static final int ALREADY_HAS_TEXT = 25;

    @Override public String id() { return "ocr"; }
    @Override public String name() { return "OCR PDF"; }
    @Override public String description() { return "Make scans searchable"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "searchable"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(LANGUAGE, "Language of the text", OptionField.Type.CHOICE,
                        List.of(ENGLISH, AFRIKAANS, BOTH), ENGLISH)
                        .describe("", "", "Slower; for documents that mix both"),
                new OptionField(SKIP_TEXT_PAGES, "Skip pages that already have text (faster)",
                        OptionField.Type.CHECKBOX, true));
    }

    @Override
    public String buttonLabel(int files) {
        return "Make " + Format.count(files, "file", "files") + " searchable";
    }

    @Override
    public String workingLabel(int files) {
        return "Reading the text in " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " made searchable";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        Object language = options.get(LANGUAGE);
        String languages = AFRIKAANS.equals(language) ? "afr" : BOTH.equals(language) ? "eng+afr" : "eng";
        boolean skipTextPages = !Boolean.FALSE.equals(options.get(SKIP_TEXT_PAGES));

        OcrEngine engine;
        try {
            engine = new OcrEngine(languages);
        } catch (Error e) {
            throw OcrEngine.cantStart(e);
        }

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                FileResult result = ocrOne(doc, input, i, inputs.size(), engine, skipTextPages, saveTo, progress);
                if (result == null) break;                      // cancelled partway: nothing written for this file
                results.add(result);
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            } catch (Error e) {
                // Tesseract runs as native Windows code; if it can't start, every file would fail the same way.
                throw OcrEngine.cantStart(e);
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Reads every page that needs it and saves the searchable copy. Null if cancelled. */
    private FileResult ocrOne(PDDocument doc, Path input, int index, int fileCount, OcrEngine engine,
                              boolean skipTextPages, SaveTo saveTo, Progress progress) throws IOException {
        int pages = doc.getNumberOfPages();
        PDFRenderer renderer = new PDFRenderer(doc);
        PDFTextStripper stripper = new PDFTextStripper();
        // Loaded once per file: Plex for most text, Noto Sans for letters Plex doesn't have (decision 006).
        PDFont plex = PageCanvas.font(doc, false, "a");
        PDFont noto = PageCanvas.font(doc, false, "ṱ");
        int read = 0, skipped = 0, wordCount = 0;

        for (int p = 0; p < pages; p++) {
            if (progress.isCancelled()) return null;
            String status = "Page " + (p + 1) + " of " + pages;
            progress.file(index, (p + 0.1) / pages * 0.95, status);
            progress.update((index + (double) p / pages) / fileCount, "Reading " + input.getFileName() + " · " + status);

            if (skipTextPages && hasText(stripper, doc, p + 1)) {
                skipped++;
                continue;
            }
            // Grey is enough for reading text, and a third of the memory of colour.
            BufferedImage picture = renderer.renderImageWithDPI(p, DPI, ImageType.GRAY);
            List<Word> words = engine.words(picture);
            if (!words.isEmpty()) {
                addInvisibleText(doc, p, words, plex, noto);
                wordCount += words.size();
            }
            read++;
        }

        Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pages);
        SafeSave.write(output, temp -> doc.save(temp.toFile()));

        String note = read == 0
                ? "Every page already had text, so nothing needed reading · saved a copy"
                : Format.count(read, "page", "pages") + " read · " + Format.count(wordCount, "word", "words") + " found"
                        + (skipped > 0 ? " · " + skipped + " already had text" : "");
        boolean nothingFound = read > 0 && wordCount == 0;
        return new FileResult(input, output, Files.size(input), Files.size(output),
                nothingFound ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS,
                nothingFound ? "No text could be read. Is the scan blank, upside down or very faint?" : note);
    }

    private static boolean hasText(PDFTextStripper stripper, PDDocument doc, int page) throws IOException {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc).replaceAll("\\s", "").length() >= ALREADY_HAS_TEXT;
    }

    /**
     * Writes each word invisibly over the page, stretched to the width of the word in
     * the picture, so selecting text in a PDF reader highlights the right place.
     */
    private static void addInvisibleText(PDDocument doc, int pageIndex, List<Word> words, PDFont plex, PDFont noto)
            throws IOException {
        PageCanvas.drawOn(doc, doc.getPage(pageIndex), (content, width, height) -> {
            content.beginText();
            content.setRenderingMode(RenderingMode.NEITHER);          // invisible: no fill, no outline
            for (Word word : words) {
                String text = word.getText().strip();
                if (text.isEmpty()) continue;
                PDFont font = canWrite(plex, text) ? plex : canWrite(noto, text) ? noto : null;
                if (font == null) continue;                            // a symbol no bundled font has
                Rectangle box = word.getBoundingBox();
                float x = box.x * 72f / DPI;
                float boxWidth = box.width * 72f / DPI;
                float boxHeight = box.height * 72f / DPI;
                float baseline = height - (box.y + box.height) * 72f / DPI + boxHeight * 0.2f;
                float size = Math.max(1, boxHeight * 0.95f);
                float natural = font.getStringWidth(text) / 1000 * size;
                if (natural <= 0) continue;
                content.setFont(font, size);
                content.setHorizontalScaling(100 * boxWidth / natural);
                content.setTextMatrix(Matrix.getTranslateInstance(x, baseline));
                content.showText(text);
            }
            content.endText();
        });
    }

    private static boolean canWrite(PDFont font, String text) {
        try {
            font.encode(text);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
