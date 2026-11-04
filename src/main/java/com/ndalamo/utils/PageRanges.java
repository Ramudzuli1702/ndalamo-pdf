package com.ndalamo.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Reads the page lists people type, like "1-3, 5, 8-10" or "8-" (page 8 to the end).
 * Pages are numbered from 1, as people count them. Problems come back as plain
 * sentences the screen can show as they are.
 */
public final class PageRanges {

    /** Pages from..to, both included. */
    public record Range(int from, int to) {
        public int size() { return to - from + 1; }

        /** "page 5" or "pages 1-3", used in output names and notes. */
        public String label() { return from == to ? "page " + from : "pages " + from + "-" + to; }
    }

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(?:-\\s*(\\d*))?");

    private PageRanges() {}

    /** A problem with how the text is written, or null if it looks fine. Doesn't need the page count. */
    public static String check(String text) {
        if (text == null || text.isBlank()) return "Type the pages, for example 1-3, 5, 8-10.";
        for (String part : text.split("[,;]")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            Matcher m = PART.matcher(p);
            if (!m.matches()) return "“" + p + "” isn't a page or range. Use numbers like 1-3, 5, 8-10.";
            int from = Integer.parseInt(m.group(1));
            if (from == 0) return "Pages start at 1, so “" + p + "” doesn't match a page.";
            String to = m.group(2);
            if (to != null && !to.isEmpty() && Integer.parseInt(to) < from) {
                return "“" + p + "” goes backwards. Write the smaller page first, like " + to + "-" + from + ".";
            }
        }
        return null;
    }

    /**
     * The ranges in the order typed.
     *
     * @throws IllegalArgumentException with a plain message if the text is wrong or a page doesn't exist
     */
    public static List<Range> parse(String text, int pageCount) {
        String problem = check(text);
        if (problem != null) throw new IllegalArgumentException(problem);

        List<Range> ranges = new ArrayList<>();
        for (String part : text.split("[,;]")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            Matcher m = PART.matcher(p);
            m.matches();
            int from = Integer.parseInt(m.group(1));
            String toText = m.group(2);
            int to = toText == null ? from : toText.isEmpty() ? pageCount : Integer.parseInt(toText);
            if (from > pageCount || to > pageCount) {
                throw new IllegalArgumentException("This file has only " + Format.count(pageCount, "page", "pages")
                        + ", so “" + p + "” doesn't fit.");
            }
            ranges.add(new Range(from, to));
        }
        return ranges;
    }

    /** Writes pages back as text people read, e.g. [1, 2, 3, 5] becomes "1-3, 5". */
    public static String format(Collection<Integer> pages) {
        List<Integer> sorted = pages.stream().distinct().sorted().toList();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int from = sorted.get(i);
            int to = from;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == to + 1) to = sorted.get(++i);
            if (!text.isEmpty()) text.append(", ");
            text.append(from == to ? String.valueOf(from) : from + "-" + to);
            i++;
        }
        return text.toString();
    }

    /** Every page number the ranges cover, each once, in page order. */
    public static List<Integer> pages(List<Range> ranges) {
        return ranges.stream()
                .flatMap(r -> IntStream.rangeClosed(r.from(), r.to()).boxed())
                .distinct()
                .sorted()
                .toList();
    }
}
