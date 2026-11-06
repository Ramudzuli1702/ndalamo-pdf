package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.Margins;
import com.ndalamo.tools.WordLayout.Paragraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads two-column pages in column order (SPEC §4.1 step 6). Without this, words at
 * the same height in the left and right columns would be read as one line.
 *
 * A page has two columns when there's a gutter: an empty strip in the middle part of
 * the text with many lines to its left and many to its right (the two columns' lines
 * needn't be at the same heights; paragraph spacing shifts them). Lines that cross the gutter
 * (a title, an abstract, a footer) are read across the whole width; each stretch of
 * two-column text between them is read left column first, then right column.
 */
final class Columns {

    /** The gutter between two columns must be at least this wide (points). */
    private static final float NARROWEST_GUTTER = 9;
    /** At least this many lines on each side of the gutter. */
    private static final int LINES_EACH_SIDE = 4;

    private record Gutter(float left, float right) {}

    private Columns() {}

    /** The page's paragraphs in reading order, whether it has one column or two. */
    static List<Paragraph> paragraphs(List<TextWord> words, Margins margins) {
        Gutter gutter = gutter(words, margins);
        if (gutter == null) return WordLayout.paragraphs(words, margins);

        List<Paragraph> paragraphs = new ArrayList<>();
        List<TextWord> full = new ArrayList<>(), leftColumn = new ArrayList<>(), rightColumn = new ArrayList<>();
        float middle = (gutter.left() + gutter.right()) / 2;
        for (List<TextWord> line : lines(words)) {
            // A title crosses the middle of the gutter; a column's first word may just touch its edge.
            boolean crosses = line.stream().anyMatch(w -> w.left() < middle && w.right() > middle);
            if (crosses) {
                // A full-width line ends the two-column stretch above it.
                flushColumns(paragraphs, leftColumn, rightColumn, margins, gutter);
                full.addAll(line);
            } else {
                if (!full.isEmpty()) {
                    paragraphs.addAll(WordLayout.paragraphs(full, margins));
                    full.clear();
                }
                for (TextWord w : line) (w.centreX() < middle ? leftColumn : rightColumn).add(w);
            }
        }
        flushColumns(paragraphs, leftColumn, rightColumn, margins, gutter);
        if (!full.isEmpty()) paragraphs.addAll(WordLayout.paragraphs(full, margins));
        return paragraphs;
    }

    private static void flushColumns(List<Paragraph> paragraphs, List<TextWord> leftColumn, List<TextWord> rightColumn,
                                     Margins margins, Gutter gutter) {
        // Each column has its own edges, so "this line stopped short" is measured within the column.
        List<Paragraph> left = leftColumn.isEmpty() ? List.of()
                : WordLayout.paragraphs(leftColumn, new Margins(margins.left(), gutter.left()));
        List<Paragraph> right = rightColumn.isEmpty() ? List.of()
                : new ArrayList<>(WordLayout.paragraphs(rightColumn, new Margins(gutter.right(), margins.right())));
        paragraphs.addAll(left);
        if (!left.isEmpty() && !right.isEmpty() && runsOn(left.get(left.size() - 1), right.get(0))) {
            // The left column ends mid-sentence and the right one carries on: one paragraph.
            Paragraph end = paragraphs.remove(paragraphs.size() - 1), rest = right.remove(0);
            List<TextWord> words = new ArrayList<>(end.words());
            words.addAll(rest.words());
            paragraphs.add(new Paragraph(words, end.alignment(), end.indent(), end.firstLineIndent(), end.spaceBefore(),
                    rest.endsSentence(), end.marker(), end.top(), end.bottom()));
        }
        paragraphs.addAll(right);
        leftColumn.clear();
        rightColumn.clear();
    }

    /** A paragraph cut off at the foot of a column that continues at the top of the next. */
    private static boolean runsOn(Paragraph end, Paragraph start) {
        boolean bodyText = start.alignment() == WordLayout.Alignment.LEFT || start.alignment() == WordLayout.Alignment.JUSTIFIED;
        return !end.endsSentence() && start.marker() == null && bodyText && Math.abs(end.size() - start.size()) < 1
                && !start.words().isEmpty() && Character.isLowerCase(start.words().get(0).text().charAt(0));
    }

    /**
     * The widest empty strip in the middle half of the text with several lines on each
     * side of it. A few lines may cross it (titles, footers): one line in six. The right
     * column can be much shorter than the left (the last page of an article).
     */
    private static Gutter gutter(List<TextWord> words, Margins margins) {
        List<List<TextWord>> lines = lines(words);
        if (lines.size() < LINES_EACH_SIDE * 2) return null;
        float width = margins.right() - margins.left();
        int from = (int) (margins.left() + width * 0.25f), to = (int) (margins.left() + width * 0.75f);
        if (to <= from) return null;

        int[] crossing = new int[to - from], leftOf = new int[to - from], rightOf = new int[to - from];
        for (List<TextWord> line : lines) {
            float lineLeft = line.get(0).left(), lineRight = line.get(line.size() - 1).right();
            for (int x = from; x < to; x++) {
                final int at = x;
                if (line.stream().anyMatch(w -> w.left() <= at && w.right() >= at)) {
                    crossing[x - from]++;
                } else {
                    if (lineLeft < at) leftOf[x - from]++;        // a line with words on both sides counts for both
                    if (lineRight > at) rightOf[x - from]++;
                }
            }
        }

        Gutter best = null;
        int start = -1;
        for (int x = from; x <= to; x++) {
            boolean open = x < to && crossing[x - from] * 6 <= lines.size()
                    && leftOf[x - from] >= LINES_EACH_SIDE && rightOf[x - from] >= LINES_EACH_SIDE;
            if (open && start < 0) start = x;
            if (!open && start >= 0) {
                if (x - start >= NARROWEST_GUTTER && (best == null || x - start > best.right() - best.left())) {
                    best = new Gutter(start, x);
                }
                start = -1;
            }
        }
        if (best == null || !textOnBothSides(lines, best, margins)) return null;
        return best;
    }

    /**
     * Columns of text have lines running most of the column's width; a form with short
     * labels on the left and answers on the right doesn't, and shouldn't be read as all
     * the labels first. Each side's typical line must fill at least half its side.
     */
    private static boolean textOnBothSides(List<List<TextWord>> lines, Gutter gutter, Margins margins) {
        List<Float> leftWidths = new ArrayList<>(), rightWidths = new ArrayList<>();
        for (List<TextWord> line : lines) {
            float lMin = Float.MAX_VALUE, lMax = -Float.MAX_VALUE, rMin = Float.MAX_VALUE, rMax = -Float.MAX_VALUE;
            for (TextWord w : line) {
                if (w.right() <= gutter.left()) { lMin = Math.min(lMin, w.left()); lMax = Math.max(lMax, w.right()); }
                if (w.left() >= gutter.right()) { rMin = Math.min(rMin, w.left()); rMax = Math.max(rMax, w.right()); }
            }
            if (lMax > lMin) leftWidths.add(lMax - lMin);
            if (rMax > rMin) rightWidths.add(rMax - rMin);
        }
        return median(leftWidths) >= (gutter.left() - margins.left()) * 0.5f
                && median(rightWidths) >= (margins.right() - gutter.right()) * 0.5f;
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) return 0;
        List<Float> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    /** Words grouped into lines by baseline, top to bottom, each line left to right. */
    private static List<List<TextWord>> lines(List<TextWord> words) {
        List<TextWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
        List<List<TextWord>> lines = new ArrayList<>();
        List<TextWord> line = new ArrayList<>();
        float baseline = 0, size = 0;
        for (TextWord w : sorted) {
            if (!line.isEmpty() && Math.abs(w.baseline() - baseline) > Math.max(size, w.size()) * 0.35f) {
                lines.add(line);
                line = new ArrayList<>();
            }
            if (line.isEmpty()) {
                baseline = w.baseline();
                size = w.size();
            }
            line.add(w);
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
}
