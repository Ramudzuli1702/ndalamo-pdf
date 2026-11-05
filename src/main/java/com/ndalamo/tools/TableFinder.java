package com.ndalamo.tools;

import com.ndalamo.tools.PageContent.Box;
import com.ndalamo.tools.PageContent.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the tables on a page from its words and border lines (decision 010).
 * Two ways, tried in this order:
 *
 * 1. Ruled tables: horizontal and vertical lines that cross form a grid; every
 *    word goes into the grid cell its middle falls in.
 * 2. Spaced tables (no lines): rows of words with wide gaps, where the gaps line
 *    up from row to row. The gaps become the column edges.
 *
 * Pages without either give no tables. Used by PDF to Excel, later PDF to Word.
 */
final class TableFinder {

    /** A table as rows of cell texts; every row has the same number of cells. */
    record Table(List<List<String>> rows) {
        int columns() { return rows.isEmpty() ? 0 : rows.get(0).size(); }
    }

    /** Lines this close (points) are treated as touching or as the same line. */
    private static final float TOUCHING = 2.5f;

    private TableFinder() {}

    static List<Table> find(List<Box> words, List<Rule> rules) {
        List<Table> ruled = ruledTables(words, rules);
        return ruled.isEmpty() ? spacedTables(words) : ruled;
    }

    // ── 1. Ruled tables ──────────────────────────────────────────────────

    static List<Table> ruledTables(List<Box> words, List<Rule> rules) {
        List<Rule> lines = joinBrokenLines(rules);

        // Group lines that cross or touch: each group is one table's grid.
        int[] group = new int[lines.size()];
        for (int i = 0; i < group.length; i++) group[i] = i;
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(i).horizontal() != lines.get(j).horizontal() && cross(lines.get(i), lines.get(j))) {
                    group[root(group, i)] = root(group, j);
                }
            }
        }

        List<Table> tables = new ArrayList<>();
        List<float[]> tops = new ArrayList<>();                 // to put the tables in top-to-bottom order
        for (int g = 0; g < lines.size(); g++) {
            if (root(group, g) != g) continue;
            List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (root(group, i) != g) continue;
                (lines.get(i).horizontal() ? ys : xs).add(lines.get(i).position());
            }
            List<Float> columnEdges = distinct(xs), rowEdges = distinct(ys);
            if (columnEdges.size() < 2 || rowEdges.size() < 2) continue;

            Table table = fillGrid(words, columnEdges, rowEdges);
            if (table != null) {
                tables.add(table);
                tops.add(new float[]{rowEdges.get(0), tables.size() - 1});
            }
        }
        tops.sort(Comparator.comparingDouble(t -> t[0]));
        List<Table> inOrder = new ArrayList<>();
        for (float[] t : tops) inOrder.add(tables.get((int) t[1]));
        return inOrder;
    }

    /** Puts each word in the cell its middle falls in. Null if the grid holds less than 2×2 cells of text. */
    private static Table fillGrid(List<Box> words, List<Float> columnEdges, List<Float> rowEdges) {
        int rows = rowEdges.size() - 1, columns = columnEdges.size() - 1;
        StringBuilder[][] cells = new StringBuilder[rows][columns];
        for (Box word : words) {
            int row = slot(rowEdges, word.centreY()), column = slot(columnEdges, word.centreX());
            if (row < 0 || column < 0) continue;
            if (cells[row][column] == null) cells[row][column] = new StringBuilder(word.text());
            else cells[row][column].append(' ').append(word.text());
        }
        return tidy(cells);
    }

    /** Which gap between edges a position falls in, or -1 if it's outside them all. */
    private static int slot(List<Float> edges, float position) {
        for (int i = 0; i < edges.size() - 1; i++) {
            if (position >= edges.get(i) && position < edges.get(i + 1)) return i;
        }
        return -1;
    }

    /** Borders are often drawn as many short pieces (one per cell); join pieces on the same line. */
    private static List<Rule> joinBrokenLines(List<Rule> rules) {
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(Rule::horizontal).thenComparingDouble(Rule::position).thenComparingDouble(Rule::from));
        List<Rule> joined = new ArrayList<>();
        for (Rule rule : sorted) {
            Rule last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && last.horizontal() == rule.horizontal()
                    && Math.abs(last.position() - rule.position()) <= TOUCHING && rule.from() <= last.to() + TOUCHING) {
                joined.set(joined.size() - 1, new Rule(last.horizontal(), last.position(), last.from(), Math.max(last.to(), rule.to())));
            } else {
                joined.add(rule);
            }
        }
        return joined;
    }

    private static boolean cross(Rule a, Rule b) {
        Rule horizontal = a.horizontal() ? a : b, vertical = a.horizontal() ? b : a;
        return vertical.position() >= horizontal.from() - TOUCHING && vertical.position() <= horizontal.to() + TOUCHING
                && horizontal.position() >= vertical.from() - TOUCHING && horizontal.position() <= vertical.to() + TOUCHING;
    }

    private static int root(int[] group, int i) {
        while (group[i] != i) i = group[i] = group[group[i]];
        return i;
    }

    /** Sorted positions with near-duplicates (double borders, rounding) merged. */
    private static List<Float> distinct(List<Float> positions) {
        List<Float> sorted = new ArrayList<>(positions);
        sorted.sort(null);
        List<Float> result = new ArrayList<>();
        for (float p : sorted) {
            if (result.isEmpty() || p - result.get(result.size() - 1) > TOUCHING) result.add(p);
        }
        return result;
    }

    // ── 2. Spaced tables ─────────────────────────────────────────────────

    static List<Table> spacedTables(List<Box> words) {
        if (words.isEmpty()) return List.of();
        List<List<Box>> lines = lines(words);
        float letterHeight = medianHeight(words);
        // Words in a sentence are about a third of a letter-height apart; table columns much more.
        float columnGap = Math.max(letterHeight, 5);

        List<Table> tables = new ArrayList<>();
        List<List<Box>> block = new ArrayList<>();
        int multiPartLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<Box> line = lines.get(i);
            boolean farFromPrevious = !block.isEmpty()
                    && line.get(0).top() - bottom(block.get(block.size() - 1)) > letterHeight * 2.5f;
            if (farFromPrevious) {
                addIfTable(tables, block, multiPartLines, columnGap);
                block = new ArrayList<>();
                multiPartLines = 0;
            }
            boolean multiPart = parts(line, columnGap) >= 2;
            // A one-part line can sit inside a table (a wrapped description) if a table line follows it.
            boolean tableLineNext = i + 1 < lines.size() && parts(lines.get(i + 1), columnGap) >= 2;
            if (multiPart || (!block.isEmpty() && tableLineNext)) {
                block.add(line);
                if (multiPart) multiPartLines++;
            } else {
                addIfTable(tables, block, multiPartLines, columnGap);
                block = new ArrayList<>();
                multiPartLines = 0;
            }
        }
        addIfTable(tables, block, multiPartLines, columnGap);
        return tables;
    }

    private static void addIfTable(List<Table> tables, List<List<Box>> block, int multiPartLines, float columnGap) {
        if (multiPartLines < 2) return;
        Table table = splitIntoColumns(block, columnGap);
        if (table != null) tables.add(table);
    }

    /**
     * Finds column edges where (nearly) every line has a gap, then puts each word in
     * the column its middle falls in. "Nearly": one line in five may run across a gap,
     * e.g. a long description, without merging two columns.
     */
    private static Table splitIntoColumns(List<List<Box>> block, float columnGap) {
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (List<Box> line : block) {
            left = Math.min(left, line.get(0).left());
            right = Math.max(right, line.get(line.size() - 1).right());
        }
        int width = (int) Math.ceil(right - left) + 1;
        int[] covered = new int[width];                      // how many lines have a word over each point
        for (List<Box> line : block) {
            boolean[] here = new boolean[width];
            for (Box word : line) {
                for (int x = (int) (word.left() - left); x <= (int) (word.right() - left) && x < width; x++) here[Math.max(0, x)] = true;
            }
            for (int x = 0; x < width; x++) if (here[x]) covered[x]++;
        }
        int allowed = block.size() >= 5 ? block.size() / 5 : 0;

        List<Float> edges = new ArrayList<>();
        edges.add(left - 1);
        int gapStart = -1;
        for (int x = 0; x < width; x++) {
            boolean gap = covered[x] <= allowed;
            if (gap && gapStart < 0) gapStart = x;
            if (!gap && gapStart >= 0) {
                if (x - gapStart >= columnGap * 0.6f) edges.add(left + (gapStart + x) / 2f);
                gapStart = -1;
            }
        }
        edges.add(right + 1);
        if (edges.size() < 3) return null;                    // just one column: not a table

        StringBuilder[][] cells = new StringBuilder[block.size()][edges.size() - 1];
        for (int row = 0; row < block.size(); row++) {
            Box previous = null;
            int column = -1;
            for (Box word : block.get(row)) {
                // Words a normal space apart belong to the same cell, even when a long text spills over
                // into the next column (spreadsheets do that), so only a column gap starts a new cell.
                boolean sameCell = previous != null && word.left() - previous.right() <= columnGap;
                if (!sameCell) column = slot(edges, word.centreX());
                previous = word;
                if (column < 0) continue;
                if (cells[row][column] == null) cells[row][column] = new StringBuilder(word.text());
                else cells[row][column].append(' ').append(word.text());
            }
        }
        return tidy(cells);
    }

    /** Words whose middles are within each other's height belong to one line; lines top to bottom, words left to right. */
    private static List<List<Box>> lines(List<Box> words) {
        List<Box> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Box::centreY));
        List<List<Box>> lines = new ArrayList<>();
        List<Box> line = new ArrayList<>();
        float top = 0, bottom = 0;
        for (Box word : sorted) {
            if (!line.isEmpty() && word.centreY() > bottom) {
                lines.add(line);
                line = new ArrayList<>();
            }
            if (line.isEmpty()) {
                top = word.top();
                bottom = word.bottom();
            } else {
                top = Math.min(top, word.top());
                bottom = Math.max(bottom, word.bottom());
            }
            line.add(word);
        }
        if (!line.isEmpty()) lines.add(line);
        for (List<Box> l : lines) l.sort(Comparator.comparingDouble(Box::left));
        return lines;
    }

    /** How many groups of words a line has, split at gaps wider than {@code columnGap}. */
    private static int parts(List<Box> line, float columnGap) {
        int parts = 1;
        for (int i = 1; i < line.size(); i++) {
            if (line.get(i).left() - line.get(i - 1).right() > columnGap) parts++;
        }
        return parts;
    }

    private static float bottom(List<Box> line) {
        float bottom = -Float.MAX_VALUE;
        for (Box word : line) bottom = Math.max(bottom, word.bottom());
        return bottom;
    }

    private static float medianHeight(List<Box> words) {
        float[] heights = new float[words.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = words.get(i).height();
        java.util.Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    // ── Shared ───────────────────────────────────────────────────────────

    /** Drops rows and columns that are completely empty. Null if less than 2 rows or 2 columns are left. */
    private static Table tidy(StringBuilder[][] cells) {
        int rows = cells.length, columns = rows == 0 ? 0 : cells[0].length;
        List<Integer> keepRows = new ArrayList<>(), keepColumns = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) if (cells[r][c] != null) { keepRows.add(r); break; }
        }
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) if (cells[r][c] != null) { keepColumns.add(c); break; }
        }
        if (keepRows.size() < 2 || keepColumns.size() < 2) return null;
        List<List<String>> table = new ArrayList<>();
        for (int r : keepRows) {
            List<String> row = new ArrayList<>();
            for (int c : keepColumns) row.add(cells[r][c] == null ? "" : cells[r][c].toString());
            table.add(row);
        }
        return new Table(table);
    }
}
