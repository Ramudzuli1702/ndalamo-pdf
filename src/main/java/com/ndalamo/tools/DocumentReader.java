package com.ndalamo.tools;

import com.ndalamo.models.Progress;
import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.Paragraph;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a PDF as a document: for each page, its paragraphs, pictures and tables in
 * reading order, plus which paragraphs are headings and how deep each list item is.
 * PDF to Word and PDF to Markdown both write from this (SPEC §4.1).
 *
 * Steps, in order: words with their look (PageText), scans read with OCR (ScanText),
 * underlines, pictures (PageImages), page numbers and repeated headers/footers taken
 * out (PageFurniture), margins, tables (TableFinder), two-column pages (Columns),
 * paragraphs (WordLayout), then headings and list levels (WordStyles).
 * Only reads the PDF.
 */
final class DocumentReader {

    /** Fewer letters than this and the page is probably a scan. */
    static final int HAS_TEXT = 25;

    /** What to read. {@code ocrLanguages} null means leave scans unread. */
    record Settings(boolean pictures, String ocrLanguages) {}

    /** One thing on a page: a paragraph, a picture or a table (exactly one is set). */
    record Item(Paragraph paragraph, PageImages.Picture picture, TableFinder.Table<TextWord> table) {
        float top() {
            return paragraph != null ? paragraph.top() : picture != null ? picture.top() : table.top();
        }

        float bottom() {
            return paragraph != null ? paragraph.bottom() : picture != null ? picture.bottom() : table.bottom();
        }
    }

    /** The whole document as read. */
    record Reading(List<List<Item>> pages, Map<Paragraph, Integer> headings, Map<Paragraph, Integer> listLevels,
                   List<Paragraph> paragraphs, PageFurniture.Result furniture, WordLayout.Margins margins,
                   float pageWidth, float pageHeight, float top, int scans, int scansRead, int tables, int pictures,
                   List<List<Link>> links) {}

    /** A web link on a page: where it is (points from the top-left) and where it goes. */
    record Link(float left, float top, float right, float bottom, String uri) {
        boolean covers(TextWord w) {
            float x = (w.left() + w.right()) / 2, y = w.baseline() - w.size() * 0.3f;
            return x >= left && x <= right && y >= top - 1 && y <= bottom + 1;
        }
    }

    /** The web links on a page. Turned pages give none, as their words are measured the turned way. */
    static List<Link> links(PDPage page) {
        List<Link> links = new ArrayList<>();
        if (page.getRotation() % 360 != 0) return links;
        PDRectangle crop = page.getCropBox();
        try {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI action
                        && action.getURI() != null && !action.getURI().isBlank()) {
                    PDRectangle r = link.getRectangle();
                    if (r == null) continue;
                    links.add(new Link(r.getLowerLeftX() - crop.getLowerLeftX(), crop.getUpperRightY() - r.getUpperRightY(),
                            r.getUpperRightX() - crop.getLowerLeftX(), crop.getUpperRightY() - r.getLowerLeftY(),
                            action.getURI().strip()));
                }
            }
        } catch (IOException e) {
            // Unreadable annotations: the words come through without their links.
        }
        return links;
    }

    private DocumentReader() {}

    /**
     * Reads the chosen pages (1-based). Null if cancelled. {@code engine} holds the OCR
     * engine between files, started only when a scan turns up.
     */
    static Reading read(PDDocument doc, Path input, List<Integer> pages, Settings settings, OcrEngine[] engine,
                        int index, int fileCount, Progress progress) throws IOException {
        // Read every page first: the margins are worked out from all of them together.
        List<List<TextWord>> pageWords = new ArrayList<>();
        List<List<PageImages.Picture>> pagePictures = new ArrayList<>();
        List<List<PageContent.Rule>> pageRules = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);
        int scans = 0, scansRead = 0;
        for (int n = 0; n < pages.size(); n++) {
            if (progress.isCancelled()) return null;
            int page = pages.get(n);
            String status = "Page " + page + " of " + doc.getNumberOfPages();
            progress.file(index, (n + 0.1) / pages.size() * 0.8, status);
            progress.update((index + (double) n / pages.size()) / fileCount, "Reading " + input.getFileName() + " · " + status);
            List<TextWord> words = PageText.words(doc, page - 1);
            if (words.stream().mapToInt(w -> w.text().length()).sum() < HAS_TEXT) {
                // Probably a scan: read it with OCR, then lay it out like any other page.
                if (settings.ocrLanguages() == null) {
                    scans++;
                } else {
                    progress.file(index, (n + 0.3) / pages.size() * 0.8, status + " · reading the scan");
                    try {
                        if (engine[0] == null) engine[0] = new OcrEngine(settings.ocrLanguages());
                        words = ScanText.words(engine[0], renderer, page - 1);
                    } catch (Error e) {
                        throw OcrEngine.cantStart(e);
                    }
                    scansRead++;
                }
            }
            List<PageContent.Rule> rules = PageContent.rules(doc.getPage(page - 1));
            pageRules.add(rules);
            pageWords.add(PageText.markUnderlines(words, rules));
            List<PageImages.Picture> pictures = settings.pictures() ? PageImages.pictures(doc.getPage(page - 1)) : List.of();
            if (settings.pictures()) {
                // Icons and other drawings made of lines and curves come along as pictures too.
                List<PageImages.Picture> drawings = PageDrawings.pictures(doc, page - 1, words, pictures);
                if (!drawings.isEmpty()) {
                    pictures = new ArrayList<>(pictures);
                    pictures.addAll(drawings);
                }
            }
            pagePictures.add(pictures);
        }

        // Page numbers and lines repeated at the top or bottom of every page belong in a header or
        // footer, not between paragraphs.
        List<Float> widths = new ArrayList<>(), heights = new ArrayList<>();
        for (int page : pages) {
            PDRectangle box = doc.getPage(page - 1).getCropBox();
            boolean sideways = doc.getPage(page - 1).getRotation() % 180 != 0;
            widths.add(sideways ? box.getHeight() : box.getWidth());
            heights.add(sideways ? box.getWidth() : box.getHeight());
        }
        PageFurniture.Result furniture = PageFurniture.strip(pageWords, widths, heights);
        for (int n = 0; n < pageWords.size(); n++) pageWords.set(n, furniture.pages().get(n));

        float left = Float.MAX_VALUE, right = 0, top = Float.MAX_VALUE;
        for (List<TextWord> words : pageWords) {
            for (TextWord w : words) {
                left = Math.min(left, w.left());
                right = Math.max(right, w.right());
                top = Math.min(top, w.baseline() - w.size());
            }
        }
        float pageWidth = widths.get(0), pageHeight = heights.get(0);
        if (left == Float.MAX_VALUE) {                       // no text at all
            left = 72;
            right = pageWidth - 72;
            top = 72;
        }
        int pictureCount = 0;
        for (List<PageImages.Picture> pictures : pagePictures) {
            for (PageImages.Picture picture : pictures) {
                right = Math.max(right, picture.left() + picture.width());
                top = Math.min(top, picture.top());                  // a logo above the first line
                pictureCount++;
            }
        }
        // If no line gets near the right side (a page of short lines), assume the right margin matches the left,
        // as it does on nearly every page; otherwise every line would look like it reaches the edge.
        if (right < pageWidth - left - 36) right = pageWidth - left;
        WordLayout.Margins margins = new WordLayout.Margins(left, right);

        List<List<Paragraph>> pageParagraphs = new ArrayList<>();
        List<Columns.Page> pageLayouts = new ArrayList<>();
        List<Paragraph> allParagraphs = new ArrayList<>();
        List<List<TableFinder.Table<TextWord>>> pageTables = new ArrayList<>();
        int tableCount = 0;
        for (int n = 0; n < pageWords.size(); n++) {
            List<TableFinder.Table<TextWord>> tables = tables(pageWords.get(n), pageRules.get(n));
            pageTables.add(tables);
            tableCount += tables.size();
            // Words in a table are written in the table, not as paragraphs.
            Set<TextWord> inTables = Collections.newSetFromMap(new IdentityHashMap<>());
            for (TableFinder.Table<TextWord> table : tables) table.cells().forEach(row -> row.forEach(inTables::addAll));
            List<TextWord> words = pageWords.get(n).stream().filter(w -> !inTables.contains(w)).toList();
            Columns.Page layout = Columns.read(words, margins);                    // two-column pages in column order
            pageLayouts.add(layout);
            pageParagraphs.add(layout.paragraphs());
            allParagraphs.addAll(layout.paragraphs());
        }
        Map<Paragraph, Integer> headings = WordStyles.headings(allParagraphs, WordStyles.bodySize(pageWords));
        Map<Paragraph, Integer> listLevels = WordStyles.listLevels(allParagraphs, headings);

        // Each page as one list in reading order: pictures and tables slot in between the paragraphs.
        List<List<Item>> ordered = new ArrayList<>();
        for (int n = 0; n < pageWords.size(); n++) {
            Columns.Page layout = pageLayouts.get(n);
            List<Item> waiting = new ArrayList<>();
            for (PageImages.Picture picture : pagePictures.get(n)) waiting.add(new Item(null, picture, null));
            for (TableFinder.Table<TextWord> table : pageTables.get(n)) waiting.add(new Item(null, null, table));
            waiting.sort(Comparator.comparingDouble(item -> order(item, layout)));
            List<Item> items = new ArrayList<>();
            for (Paragraph paragraph : pageParagraphs.get(n)) {
                while (!waiting.isEmpty() && comesBefore(waiting.get(0), paragraph, layout)) items.add(waiting.remove(0));
                items.add(new Item(paragraph, null, null));
            }
            items.addAll(waiting);
            ordered.add(items);
        }
        return new Reading(ordered, headings, listLevels, allParagraphs, furniture, margins, pageWidth, pageHeight, top,
                scans, scansRead, tableCount, pictureCount, pages.stream().map(p -> links(doc.getPage(p - 1))).toList());
    }

    /**
     * The tables on a page. Tables with drawn borders always count. Tables made of
     * spacing alone need 3 rows and 3 columns: two columns of text is a two-column
     * layout, not a table, and a few aligned lines are usually a form or address.
     */
    private static List<TableFinder.Table<TextWord>> tables(List<TextWord> words, List<PageContent.Rule> rules) {
        return TableFinder.find(words, rules).stream()
                .filter(t -> t.ruled() || (t.cells().size() >= 3 && t.columns() >= 3))
                .toList();
    }

    private static float left(Item item) {
        return item.picture() != null ? item.picture().left() : item.table().left();
    }

    private static float right(Item item) {
        return item.picture() != null ? item.picture().left() + item.picture().width() : item.table().right();
    }

    private static float order(Item item, Columns.Page layout) {
        return layout.order(left(item), right(item), item.top());
    }

    /** In reading order, does the picture or table go before this paragraph? On two-column pages, by column. */
    private static boolean comesBefore(Item item, Paragraph paragraph, Columns.Page layout) {
        return layout.comesBefore(left(item), right(item), item.top(), paragraph);
    }
}
