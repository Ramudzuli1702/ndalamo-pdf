package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a page's words into lines, and lines into paragraphs (SPEC §4.1 step 2).
 * A PDF only knows where each word is drawn; paragraphs have to be worked out from
 * the spacing. A new paragraph starts when:
 * - the gap above a line is clearly bigger than the usual line spacing;
 * - the text size changes (a heading and the text under it);
 * - the line before ended well short of the right edge (the end of a paragraph,
 *   or a line on its own such as an address line);
 * - the line starts further right than the paragraph so far (an indented first line).
 * Words hyphenated across a line break are joined again ("docu- ment" → "document").
 */
final class WordLayout {

    enum Alignment { LEFT, CENTRE, RIGHT, JUSTIFIED }

    /** A paragraph: its words in reading order, where it sits, and the space above it (points). */
    record Paragraph(List<TextWord> words, Alignment alignment, float indent, float firstLineIndent,
                     float spaceBefore, boolean endsSentence) {}

    /** What the page's text block looks like, measured once per page. */
    record Margins(float left, float right) {}

    private record Line(List<TextWord> words, float left, float right, float baseline, float size) {}

    private WordLayout() {}

    static List<Paragraph> paragraphs(List<TextWord> words, Margins margins) {
        List<Line> lines = lines(words);
        if (lines.isEmpty()) return List.of();
        float usualGap = usualLineGap(lines);

        List<Paragraph> paragraphs = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        float spaceBefore = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (!current.isEmpty()) {
                Line previous = current.get(current.size() - 1);
                float gap = line.baseline() - previous.baseline();
                boolean biggerGap = gap > usualGap * 1.3f && gap > line.size() * 1.5f;
                boolean sizeChanged = Math.abs(line.size() - previous.size()) >= 1.5f;
                // The classic test: if this line's first word would have fitted at the end of the line
                // before, the writer pressed Enter there. A line that's full simply wrapped.
                TextWord firstWord = line.words().get(0);
                float roomLeft = margins.right() - previous.right();
                boolean previousEndedShort = roomLeft > (firstWord.right() - firstWord.left()) + previous.size() * 0.6f;
                // Compared with the paragraph's second line onwards, since its first line may itself be indented.
                boolean indented = current.size() > 1 && line.left() > bodyLeft(current) + line.size() * 0.8f;
                if (biggerGap || sizeChanged || previousEndedShort || indented) {
                    paragraphs.add(paragraph(current, margins, spaceBefore));
                    spaceBefore = Math.max(0, gap - line.size() * 1.25f);
                    current = new ArrayList<>();
                }
            }
            current.add(line);
        }
        paragraphs.add(paragraph(current, margins, spaceBefore));
        return paragraphs;
    }

    /** Where the paragraph's lines after the first start. */
    private static float bodyLeft(List<Line> lines) {
        float left = Float.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) left = Math.min(left, lines.get(i).left());
        return left;
    }

    private static Paragraph paragraph(List<Line> lines, Margins margins, float spaceBefore) {
        List<TextWord> words = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<TextWord> lineWords = lines.get(i).words();
            if (i > 0 && !words.isEmpty() && endsWithHyphen(words.get(words.size() - 1)) && startsLowercase(lineWords.get(0))) {
                // Join "docu-" and "ment" into one word, keeping the first part's look.
                TextWord head = words.remove(words.size() - 1);
                TextWord tail = lineWords.get(0);
                words.add(new TextWord(head.text().substring(0, head.text().length() - 1) + tail.text(),
                        head.left(), tail.right(), head.baseline(), head.size(), head.font(), head.bold(), head.italic(), head.colour()));
                words.addAll(lineWords.subList(1, lineWords.size()));
            } else {
                words.addAll(lineWords);
            }
        }

        Line first = lines.get(0);
        float left = lines.size() > 1 ? bodyLeft(lines) : first.left();
        float indent = Math.max(0, left - margins.left());
        float firstLineIndent = first.left() - left;

        Alignment alignment = Alignment.LEFT;
        // Justified: every line but the last reaches the right edge. Uneven text never lines up that exactly.
        boolean fullLines = lines.size() >= 2;
        for (int i = 0; i < lines.size() - 1; i++) {
            if (margins.right() - lines.get(i).right() > 1.5f) fullLines = false;
        }
        if (fullLines) {
            alignment = Alignment.JUSTIFIED;
        } else if (lines.size() <= 3) {
            float spaceLeft = first.left() - margins.left(), spaceRight = margins.right() - first.right();
            boolean allCentred = true;
            for (Line line : lines) {
                float l = line.left() - margins.left(), r = margins.right() - line.right();
                if (Math.abs(l - r) > line.size() * 1.5f || l < line.size() * 2) allCentred = false;
            }
            if (allCentred) {
                alignment = Alignment.CENTRE;
            } else if (spaceRight < first.size() && spaceLeft > (margins.right() - margins.left()) / 3) {
                alignment = Alignment.RIGHT;                          // e.g. a date at the top right of a letter
            }
        }
        if (alignment == Alignment.CENTRE || alignment == Alignment.RIGHT) {
            indent = 0;
            firstLineIndent = 0;
        }
        String lastText = words.get(words.size() - 1).text();
        boolean endsSentence = lastText.matches(".*[.!?:;\"”’)]$");
        return new Paragraph(words, alignment, indent, firstLineIndent, spaceBefore, endsSentence);
    }

    private static boolean endsWithHyphen(TextWord word) {
        String t = word.text();
        return t.length() > 2 && t.endsWith("-") && Character.isLetter(t.charAt(t.length() - 2));
    }

    private static boolean startsLowercase(TextWord word) {
        return !word.text().isEmpty() && Character.isLowerCase(word.text().charAt(0));
    }

    /** Groups words into lines by baseline; lines top to bottom, words left to right. */
    private static List<Line> lines(List<TextWord> words) {
        List<TextWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(TextWord::baseline).thenComparingDouble(TextWord::left));
        List<Line> lines = new ArrayList<>();
        List<TextWord> line = new ArrayList<>();
        float baseline = 0, size = 0;
        for (TextWord word : sorted) {
            // Raised or lowered text (footnote marks) stays on its line: allow a third of the letter size.
            if (!line.isEmpty() && Math.abs(word.baseline() - baseline) > Math.max(size, word.size()) * 0.35f) {
                lines.add(line(line));
                line = new ArrayList<>();
            }
            if (line.isEmpty()) {
                baseline = word.baseline();
                size = word.size();
            }
            line.add(word);
        }
        if (!line.isEmpty()) lines.add(line(line));
        return lines;
    }

    private static Line line(List<TextWord> words) {
        words.sort(Comparator.comparingDouble(TextWord::left));
        // The line's size is the size most of its letters have, so one big initial doesn't count.
        float size = mostCommonSize(words);
        float baseline = words.get(0).baseline();
        for (TextWord w : words) if (Math.abs(w.size() - size) < 0.6f) baseline = w.baseline();
        return new Line(words, words.get(0).left(), words.get(words.size() - 1).right(), baseline, size);
    }

    static float mostCommonSize(List<TextWord> words) {
        java.util.Map<Float, Integer> letters = new java.util.HashMap<>();
        for (TextWord w : words) letters.merge(w.size(), w.text().length(), Integer::sum);
        return letters.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(11f);
    }

    /** The most common distance between one baseline and the next, among lines of body text. */
    private static float usualLineGap(List<Line> lines) {
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            float gap = lines.get(i).baseline() - lines.get(i - 1).baseline();
            if (gap > 0 && gap < lines.get(i).size() * 3) gaps.add(gap);
        }
        if (gaps.isEmpty()) return lines.get(0).size() * 1.2f;
        float[] sorted = new float[gaps.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = gaps.get(i);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
