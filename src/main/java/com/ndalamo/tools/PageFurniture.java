package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds the page "furniture" in a PDF: page numbers and the header and footer lines
 * that repeat at the top or bottom of every page. In a Word file these belong in the
 * header and footer, not in the body text, where they'd turn up between paragraphs
 * on every page. Used by PDF to Word.
 *
 * A line counts if it sits in the top or bottom strip of the page and is either a
 * page number ("3", "Page 3", "3 of 10", "– 3 –") or repeats on at least two pages and half the
 * pages (numbers in it may change, e.g. "Annual report 2026 · page 4").
 */
final class PageFurniture {

    /** The top and bottom strips of a page, as a share of its height. */
    private static final float STRIP = 0.1f;

    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)^(page|p\\.|bladsy|siaṱari)?\\s*[-–—(]?\\s*\\d{1,4}\\s*[-–—)]?(\\s*(of|/|van)\\s*\\d{1,4})?$");

    /** What was found: the cleaned pages, and what to put in the Word header and footer. */
    record Result(List<List<TextWord>> pages, String header, String footer, boolean pageNumbers,
                  boolean pageNumbersAtTop, WordLayout.Alignment pageNumberAlignment) {}

    private PageFurniture() {}

    static Result strip(List<List<TextWord>> pages, List<Float> pageWidths, List<Float> pageHeights) {
        // Each page's candidate lines: those in the top or bottom strip.
        List<List<List<TextWord>>> candidates = new ArrayList<>();
        Map<String, Integer> repeats = new HashMap<>();
        for (int n = 0; n < pages.size(); n++) {
            List<List<TextWord>> inStrips = new ArrayList<>();
            for (List<TextWord> line : lines(pages.get(n))) {
                float baseline = line.get(0).baseline(), height = pageHeights.get(n);
                if (baseline < height * STRIP || baseline > height * (1 - STRIP)) {
                    inStrips.add(line);
                    repeats.merge(shape(line, baseline < height / 2), 1, Integer::sum);
                }
            }
            candidates.add(inStrips);
        }

        String header = null, footer = null;
        boolean pageNumbers = false, numbersAtTop = false;
        WordLayout.Alignment numberAlignment = WordLayout.Alignment.CENTRE;
        List<List<TextWord>> cleaned = new ArrayList<>();
        for (int n = 0; n < pages.size(); n++) {
            List<TextWord> keep = new ArrayList<>(pages.get(n));
            for (List<TextWord> line : candidates.get(n)) {
                boolean top = line.get(0).baseline() < pageHeights.get(n) / 2;
                String text = text(line);
                boolean number = PAGE_NUMBER.matcher(text).matches();
                int seen = repeats.get(shape(line, top));
                boolean repeated = seen >= 2 && seen * 2 >= pages.size();      // on two pages or more, and at least half
                if (!number && !repeated) continue;
                keep.removeAll(line);
                if (number) {
                    if (!pageNumbers) {
                        numberAlignment = alignment(line, pageWidths.get(n));
                        numbersAtTop = top;
                    }
                    pageNumbers = true;
                } else if (top && header == null) {
                    header = text;
                } else if (!top && footer == null) {
                    footer = text;
                }
            }
            cleaned.add(keep);
        }
        return new Result(cleaned, header, footer, pageNumbers, numbersAtTop, numberAlignment);
    }

    /** The line with its numbers blanked out, so "page 3" and "page 4" match. Top and bottom kept apart. */
    private static String shape(List<TextWord> line, boolean top) {
        return (top ? "top:" : "bottom:") + text(line).replaceAll("\\d+", "#");
    }

    private static String text(List<TextWord> line) {
        return String.join(" ", line.stream().map(TextWord::text).toList()).strip();
    }

    private static WordLayout.Alignment alignment(List<TextWord> line, float pageWidth) {
        float middle = (line.get(0).left() + line.get(line.size() - 1).right()) / 2;
        if (middle < pageWidth * 0.35f) return WordLayout.Alignment.LEFT;
        if (middle > pageWidth * 0.65f) return WordLayout.Alignment.RIGHT;
        return WordLayout.Alignment.CENTRE;
    }

    /** Words grouped into lines by baseline. */
    private static List<List<TextWord>> lines(List<TextWord> words) {
        List<TextWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
        List<List<TextWord>> lines = new ArrayList<>();
        List<TextWord> line = new ArrayList<>();
        for (TextWord w : sorted) {
            if (!line.isEmpty() && Math.abs(w.baseline() - line.get(0).baseline()) > Math.max(line.get(0).size(), w.size()) * 0.35f) {
                lines.add(line);
                line = new ArrayList<>();
            }
            line.add(w);
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
}
