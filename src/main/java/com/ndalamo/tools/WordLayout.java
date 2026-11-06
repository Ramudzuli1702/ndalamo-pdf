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
 * - the line starts further right than the paragraph so far (an indented first line);
 * - the line starts with a bullet or a list number ("•", "3.", "b)", "(iv)");
 * - the line, or the one before, has a tab-like gap in it (a form: "Name:   Thabo").
 * Words hyphenated across a line break are joined again ("docu- ment" → "document").
 */
final class WordLayout {

    enum Alignment { LEFT, CENTRE, RIGHT, JUSTIFIED }

    /**
     * A paragraph: its words in reading order, where it sits, and the space above it
     * (points). For a list item, {@code marker} is its bullet or number, and {@code words}
     * leave it out. {@code top} and {@code bottom} place it on the page, so pictures can
     * go in between paragraphs.
     */
    record Paragraph(List<TextWord> words, Alignment alignment, float indent, float firstLineIndent,
                     float spaceBefore, boolean endsSentence, ListMarker marker, float top, float bottom) {

        /** The paragraph's text size: the size most of its letters have. */
        float size() {
            return mostCommonSize(words);
        }

        boolean allBold() {
            return words.stream().allMatch(TextWord::bold);
        }
    }

    /**
     * A list item's bullet or number. {@code format} is Word's name for the numbering
     * ("bullet", "decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman") and
     * {@code pattern} how it's written, e.g. "%1." or "(%1)".
     */
    record ListMarker(String format, String pattern, int number, float markerLeft, float textLeft, TextWord word) {
        boolean bullet() {
            return format.equals("bullet");
        }
    }

    /** Characters PDFs use as bullets, including the private-use ones Word writes for its Symbol bullets. */
    private static final String BULLETS = "•◦▪▫■□●○‣⁃–—-*➢➤►✓✔"
            + "\uF0B7\uF0A7\uF076\uF0D8\uF0FC";     // Symbol and Wingdings bullets from Word
    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("^(\\()?(\\d{1,3}|[a-zA-Z]|[ivxlcIVXLC]{1,6})([.)])?$");

    /** What the page's text block looks like, measured once per page. */
    record Margins(float left, float right) {}

    private record Line(List<TextWord> words, float left, float right, float baseline, float size) {}

    private WordLayout() {}

    static List<Paragraph> paragraphs(List<TextWord> words, Margins margins) {
        List<Line> lines = lines(words);
        if (lines.isEmpty()) return List.of();
        float usualGap = usualLineGap(lines);
        float edge = lineEdge(lines, margins);

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
                float roomLeft = edge - previous.right();
                boolean previousEndedShort = roomLeft > (firstWord.right() - firstWord.left()) + previous.size() * 0.6f;
                // Compared with the paragraph's second line onwards, since its first line may itself be indented.
                boolean indented = current.size() > 1 && line.left() > bodyLeft(current) + line.size() * 0.8f;
                boolean listItem = marker(line.words()) != null;
                // A line with a tab-like gap inside it ("Name:      Thabo") is a form or tabbed line, not prose.
                boolean tabbed = hasTabGap(previous) || hasTabGap(line);
                if (biggerGap || sizeChanged || previousEndedShort || indented || listItem || tabbed) {
                    paragraphs.add(paragraph(current, margins, edge, spaceBefore));
                    spaceBefore = Math.max(0, gap - line.size() * 1.25f);
                    current = new ArrayList<>();
                }
            }
            current.add(line);
        }
        paragraphs.add(paragraph(current, margins, edge, spaceBefore));
        return paragraphs;
    }

    /**
     * Where full lines of text end here: the right edge that 80% of lines stay within.
     * Usually that's the page margin, but a narrow column ends well before it, and one
     * wide title shouldn't count. Never past the margin.
     */
    private static float lineEdge(List<Line> lines, Margins margins) {
        float[] rights = new float[lines.size()];
        for (int i = 0; i < rights.length; i++) rights[i] = lines.get(i).right();
        Arrays.sort(rights);
        float edge = rights[Math.min(rights.length - 1, (int) Math.ceil(rights.length * 0.8) - 1)];
        return Math.min(margins.right(), edge + 1);
    }

    /** A gap between two words wider than two letter-heights: far more than any space in running text. */
    private static boolean hasTabGap(Line line) {
        List<TextWord> words = line.words();
        for (int i = 1; i < words.size(); i++) {
            if (words.get(i).left() - words.get(i - 1).right() > line.size() * 2) return true;
        }
        return false;
    }

    /** Where the paragraph's lines after the first start. */
    private static float bodyLeft(List<Line> lines) {
        float left = Float.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) left = Math.min(left, lines.get(i).left());
        return left;
    }

    private static Paragraph paragraph(List<Line> lines, Margins margins, float edge, float spaceBefore) {
        List<TextWord> words = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<TextWord> lineWords = lines.get(i).words();
            if (i > 0 && !words.isEmpty() && endsWithHyphen(words.get(words.size() - 1)) && startsLowercase(lineWords.get(0))) {
                // Join "docu-" and "ment" into one word, keeping the first part's look.
                TextWord head = words.remove(words.size() - 1);
                TextWord tail = lineWords.get(0);
                words.add(head.withText(head.text().substring(0, head.text().length() - 1) + tail.text(), tail.right()));
                words.addAll(lineWords.subList(1, lineWords.size()));
            } else {
                words.addAll(lineWords);
            }
        }

        Line first = lines.get(0);
        float left = lines.size() > 1 ? bodyLeft(lines) : first.left();
        float indent = Math.max(0, left - margins.left());
        float firstLineIndent = first.left() - left;

        ListMarker marker = marker(first.words());
        if (marker != null) {
            words.remove(0);
            // The text lines up after the marker, and the marker hangs out to the left of it.
            indent = Math.max(0, marker.textLeft() - margins.left());
            firstLineIndent = marker.markerLeft() - marker.textLeft();
        }

        Alignment alignment = Alignment.LEFT;
        // Justified: every line but the last reaches the right edge. Uneven text never lines up that exactly.
        boolean fullLines = lines.size() >= 2;
        for (int i = 0; i < lines.size() - 1; i++) {
            if (edge - lines.get(i).right() > 1.5f) fullLines = false;
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
        if (marker != null) alignment = alignment == Alignment.JUSTIFIED ? Alignment.JUSTIFIED : Alignment.LEFT;
        if (alignment == Alignment.CENTRE || alignment == Alignment.RIGHT) {
            indent = 0;
            firstLineIndent = 0;
        }
        String lastText = words.get(words.size() - 1).text();
        boolean endsSentence = lastText.matches(".*[.!?:;\"”’)]$");
        float top = first.baseline() - first.size() * 0.8f;          // about where the tallest letters reach
        float bottom = lines.get(lines.size() - 1).baseline() + lines.get(lines.size() - 1).size() * 0.25f;
        return new Paragraph(words, alignment, indent, firstLineIndent, spaceBefore, endsSentence, marker, top, bottom);
    }

    /**
     * The line's bullet or list number, or null. The marker must be followed by more
     * text on the line. A dash or "o" only counts with a clear gap after it, since
     * sentences can start with those.
     */
    static ListMarker marker(List<TextWord> line) {
        if (line.size() < 2) return null;
        TextWord first = line.get(0), next = line.get(1);
        String t = first.text();
        float gap = next.left() - first.right();
        boolean clearGap = gap > first.size() * 0.6f;

        if (t.length() == 1 && BULLETS.indexOf(t.charAt(0)) >= 0) {
            boolean needsGap = "–—-*".indexOf(t.charAt(0)) >= 0;
            if (!needsGap || clearGap) return new ListMarker("bullet", t, 0, first.left(), next.left(), first);
        }
        if (t.equals("o") && clearGap && first.font().contains("Courier")) {
            return new ListMarker("bullet", t, 0, first.left(), next.left(), first);
        }
        java.util.regex.Matcher m = NUMBERED.matcher(t);
        if (!m.matches()) return null;
        boolean open = m.group(1) != null;
        String closing = m.group(3);
        if (closing == null || (open && !closing.equals(")"))) return null;   // "3" alone, or "(3." aren't markers
        String value = m.group(2);
        String pattern = (open ? "(" : "") + "%1" + closing;
        if (value.matches("\\d+")) return new ListMarker("decimal", pattern, Integer.parseInt(value), first.left(), next.left(), first);
        if (value.matches("[ivxlc]+")) return new ListMarker("lowerRoman", pattern, roman(value), first.left(), next.left(), first);
        if (value.matches("[IVXLC]+")) return new ListMarker("upperRoman", pattern, roman(value), first.left(), next.left(), first);
        if (value.length() == 1 && Character.isLowerCase(value.charAt(0))) {
            return new ListMarker("lowerLetter", pattern, value.charAt(0) - 'a' + 1, first.left(), next.left(), first);
        }
        if (value.length() == 1) return new ListMarker("upperLetter", pattern, value.charAt(0) - 'A' + 1, first.left(), next.left(), first);
        return null;
    }

    private static int roman(String numeral) {
        String s = numeral.toLowerCase();
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            int value = romanDigit(s.charAt(i));
            if (i + 1 < s.length() && value < romanDigit(s.charAt(i + 1))) total -= value;
            else total += value;
        }
        return total;
    }

    private static int romanDigit(char c) {
        return switch (c) {
            case 'i' -> 1;
            case 'v' -> 5;
            case 'x' -> 10;
            case 'l' -> 50;
            default -> 100;
        };
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
