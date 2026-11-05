package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.Paragraph;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF to Word: turns a PDF into an editable .docx (SPEC §4.1). Built in steps.
 * So far: paragraphs, alignment, indents, each word's font, size, bold, italic,
 * underline and colour (12a), and headings and lists (12b, see WordStyles).
 * Pictures, tables, columns and scans come in later steps. The goal is a clean document to
 * edit, not an exact copy of the page. The PDF is only read.
 */
public class PdfToWordTool implements PdfTool {

    static final String PAGE_BREAKS = "pageBreaks";
    static final String PAGES = "pages";

    /** Fewer letters than this and the page is probably a scan; OCR for PDF to Word comes in step 12f. */
    private static final int HAS_TEXT = 25;

    @Override public String id() { return "pdf-to-word"; }
    @Override public String name() { return "PDF to Word"; }
    @Override public String description() { return "Editable .docx"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "editable"; }
    @Override public String outputExtension() { return "docx"; }
    @Override public Preview preview() { return Preview.PICK; }
    @Override public String pagesOption() { return PAGES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(PAGE_BREAKS, "Start each PDF page on a new page", OptionField.Type.CHECKBOX, true),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3, 5").advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        String pages = (String) options.get(PAGES);
        return pages == null || pages.isBlank() ? null : PageRanges.check(pages);
    }

    @Override
    public List<Integer> previewPages(Map<String, Object> options, int pageCount) {
        String pages = (String) options.get(PAGES);
        if (pages == null || pages.isBlank()) return List.of();
        try {
            return PageRanges.pages(PageRanges.parse(pages, pageCount));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @Override
    public String buttonLabel(int files) {
        return "Convert " + Format.count(files, "file", "files") + " to Word";
    }

    @Override
    public String workingLabel(int files) {
        return "Converting " + Format.count(files, "file", "files") + " to Word…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to Word";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean pageBreaks = !Boolean.FALSE.equals(options.get(PAGE_BREAKS));
        String pageChoice = (String) options.get(PAGES);

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.02, "Opening");
            try (PDDocument doc = PdfFiles.open(input)) {
                List<Integer> pages = pageChoice == null || pageChoice.isBlank()
                        ? PageRanges.pages(List.of(new PageRanges.Range(1, doc.getNumberOfPages())))
                        : PageRanges.pages(PageRanges.parse(pageChoice, doc.getNumberOfPages()));
                FileResult result = convertOne(doc, input, pages, i, inputs.size(), pageBreaks, saveTo, progress);
                if (result == null) break;                // cancelled partway: nothing written for this file
                results.add(result);
                progress.file(i, 1, "Done");
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage()));     // e.g. "page 9" in a 5-page file
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Reads the chosen pages and writes the Word file. Null if cancelled. */
    private FileResult convertOne(PDDocument doc, Path input, List<Integer> pages, int index, int fileCount,
                                  boolean pageBreaks, SaveTo saveTo, Progress progress) throws IOException {
        // Read every page first: the margins are worked out from all of them together.
        List<List<TextWord>> pageWords = new ArrayList<>();
        for (int n = 0; n < pages.size(); n++) {
            if (progress.isCancelled()) return null;
            int page = pages.get(n);
            String status = "Page " + page + " of " + doc.getNumberOfPages();
            progress.file(index, (n + 0.1) / pages.size() * 0.8, status);
            progress.update((index + (double) n / pages.size()) / fileCount, "Reading " + input.getFileName() + " · " + status);
            List<TextWord> words = PageText.words(doc, page - 1);
            pageWords.add(PageText.markUnderlines(words, PageContent.rules(doc.getPage(page - 1))));
        }

        float left = Float.MAX_VALUE, right = 0, top = Float.MAX_VALUE;
        for (List<TextWord> words : pageWords) {
            for (TextWord w : words) {
                left = Math.min(left, w.left());
                right = Math.max(right, w.right());
                top = Math.min(top, w.baseline() - w.size());
            }
        }
        PDRectangle size = doc.getPage(pages.get(0) - 1).getCropBox();
        boolean turned = doc.getPage(pages.get(0) - 1).getRotation() % 180 != 0;
        float pageWidth = turned ? size.getHeight() : size.getWidth(), pageHeight = turned ? size.getWidth() : size.getHeight();
        if (left == Float.MAX_VALUE) {                       // no text at all
            left = 72;
            right = pageWidth - 72;
            top = 72;
        }
        WordLayout.Margins margins = new WordLayout.Margins(left, right);

        int paragraphCount = 0, scans = 0;
        long sizeBefore = Files.size(input);
        Path output = saveTo.outputFor(input, outputSuffix(), outputExtension(), doc.getNumberOfPages());
        List<List<Paragraph>> pageParagraphs = new ArrayList<>();
        List<Paragraph> allParagraphs = new ArrayList<>();
        for (List<TextWord> words : pageWords) {
            List<Paragraph> paragraphs = WordLayout.paragraphs(words, margins);
            pageParagraphs.add(paragraphs);
            allParagraphs.addAll(paragraphs);
        }
        Map<Paragraph, Integer> headings = WordStyles.headings(allParagraphs, WordStyles.bodySize(pageWords));

        try (XWPFDocument word = new XWPFDocument()) {
            setPage(word, pageWidth, pageHeight, left, pageWidth - right, top);
            if (!headings.isEmpty()) WordStyles.addHeadingStyles(word);
            Map<Paragraph, WordStyles.ListPlace> lists = WordStyles.lists(word, allParagraphs, headings);
            XWPFParagraph last = null;
            Paragraph lastParagraph = null;
            for (int n = 0; n < pageWords.size(); n++) {
                if (progress.isCancelled()) return null;
                List<TextWord> words = pageWords.get(n);
                int letters = 0;
                for (TextWord w : words) letters += w.text().length();
                if (letters < HAS_TEXT) scans++;

                List<Paragraph> paragraphs = pageParagraphs.get(n);
                for (int p = 0; p < paragraphs.size(); p++) {
                    Paragraph paragraph = paragraphs.get(p);
                    boolean firstOnPage = p == 0 && n > 0;
                    // Without page breaks, a sentence that runs over onto the next page stays one paragraph.
                    if (firstOnPage && !pageBreaks && last != null && !lastParagraph.endsSentence()
                            && flowing(lastParagraph) && flowing(paragraph) && paragraph.marker() == null
                            && !headings.containsKey(paragraph) && !headings.containsKey(lastParagraph)) {
                        addRuns(last, paragraph.words(), true);
                        lastParagraph = paragraph;
                        continue;
                    }
                    last = write(word, paragraph, headings.get(paragraph), lists.get(paragraph));
                    if (firstOnPage && pageBreaks) last.setPageBreak(true);
                    lastParagraph = paragraph;
                    paragraphCount++;
                }
                if (paragraphs.isEmpty() && n > 0 && pageBreaks) {
                    last = word.createParagraph();                   // keep an empty page where the PDF had one
                    last.setPageBreak(true);
                }
            }
            if (word.getParagraphs().isEmpty()) word.createParagraph();
            SafeSave.write(output, temp -> {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    word.write(out);
                }
            });
        }

        String note = Format.count(pages.size(), "page", "pages") + " · " + Format.count(paragraphCount, "paragraph", "paragraphs");
        if (scans > 0) {
            note += " · " + Format.count(scans, "page looks", "pages look") + " scanned and came out empty"
                    + " — run OCR PDF on it first";
        }
        return new FileResult(input, output, sizeBefore, Files.size(output),
                scans == pages.size() ? FileResult.Status.PARTIAL : FileResult.Status.SUCCESS, note);
    }

    /** Left-aligned or justified body text, which can run on from one page to the next. */
    private static boolean flowing(Paragraph paragraph) {
        return paragraph.alignment() == WordLayout.Alignment.LEFT || paragraph.alignment() == WordLayout.Alignment.JUSTIFIED;
    }

    private static XWPFParagraph write(XWPFDocument word, Paragraph paragraph, Integer headingLevel,
                                       WordStyles.ListPlace listPlace) {
        XWPFParagraph p = word.createParagraph();
        if (headingLevel != null) p.setStyle("Heading" + headingLevel);
        if (listPlace != null) {
            p.setNumID(listPlace.numId());
            p.setNumILvl(BigInteger.valueOf(listPlace.level()));
        }
        p.setAlignment(switch (paragraph.alignment()) {
            case CENTRE -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
            case JUSTIFIED -> ParagraphAlignment.BOTH;
            case LEFT -> ParagraphAlignment.LEFT;
        });
        p.setSpacingAfter(0);
        p.setSpacingBefore(twips(Math.min(paragraph.spaceBefore(), 48)));
        if (paragraph.indent() > 2) p.setIndentationLeft(twips(paragraph.indent()));
        if (paragraph.firstLineIndent() > 2) p.setIndentationFirstLine(twips(paragraph.firstLineIndent()));
        else if (paragraph.firstLineIndent() < -2) p.setIndentationHanging(twips(-paragraph.firstLineIndent()));
        List<TextWord> words = paragraph.words();
        if (paragraph.marker() != null && listPlace == null) {
            // A numbered heading ("1. Contributions"): the number stays as text in the heading.
            words = new ArrayList<>(words);
            words.add(0, paragraph.marker().word());
        }
        addRuns(p, words, false);
        return p;
    }

    /** Adds the words as runs: one run per stretch of words that look the same. */
    private static void addRuns(XWPFParagraph p, List<TextWord> words, boolean afterExistingText) {
        StringBuilder text = new StringBuilder();
        TextWord look = null;
        boolean spaceFirst = afterExistingText;
        for (TextWord w : words) {
            if (look != null && !w.looksLike(look)) {
                // The space between two runs goes with whichever isn't underlined, so no underlined gap shows.
                if (!look.underline()) text.append(' ');
                addRun(p, text.toString(), look);
                text.setLength(0);
                spaceFirst = look.underline();
            }
            if (text.length() > 0 || spaceFirst) text.append(' ');
            spaceFirst = false;
            text.append(w.text());
            look = w;
        }
        if (look != null) addRun(p, text.toString(), look);
    }

    private static void addRun(XWPFParagraph p, String text, TextWord look) {
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontFamily(look.font());
        run.setFontSize(look.size());
        if (look.bold()) run.setBold(true);
        if (look.italic()) run.setItalic(true);
        if (look.colour() != 0) run.setColor(String.format("%06X", look.colour()));
        if (look.underline()) run.setUnderline(UnderlinePatterns.SINGLE);
    }

    /** Page size and margins like the PDF's, so lines break in about the same places. */
    private static void setPage(XWPFDocument word, float width, float height, float left, float right, float top) {
        CTSectPr section = word.getDocument().getBody().isSetSectPr()
                ? word.getDocument().getBody().getSectPr() : word.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(twips(width)));
        size.setH(BigInteger.valueOf(twips(height)));
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setLeft(BigInteger.valueOf(twips(clamp(left))));
        margins.setRight(BigInteger.valueOf(twips(clamp(right))));
        margins.setTop(BigInteger.valueOf(twips(clamp(top))));
        margins.setBottom(BigInteger.valueOf(twips(clamp(top))));
    }

    /** Margins between half an inch and two inches, whatever the PDF had. */
    private static float clamp(float points) {
        return Math.max(36, Math.min(144, points));
    }

    /** Word measures in twentieths of a point. */
    private static int twips(float points) {
        return Math.round(points * 20);
    }
}
