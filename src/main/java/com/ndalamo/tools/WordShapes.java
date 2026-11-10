package com.ndalamo.tools;

import com.ndalamo.tools.PageShapes.Shape;
import com.ndalamo.tools.PageText.TextWord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a page's boxes and lines (PageShapes) mean for the Word copy:
 * - a background behind whole lines of text (a code block): shading on those paragraphs;
 * - a small background behind a few words (inline code): shading on those words;
 * - a thin upright bar just left of text (a quote): a left border on those paragraphs;
 * - a long thin line between paragraphs (a divider): a line of its own;
 * - inside a table: cell shading and the border colour and thickness.
 * Boxes drawn in pieces (one per code line, as browsers and Ndalamo do) are joined first.
 */
final class WordShapes {

    /** A background box. */
    record Block(float left, float top, float right, float bottom, int rgb) {}

    /** A quote bar. */
    record Bar(float left, float right, float top, float bottom, int rgb) {}

    /** A dividing line across the page. */
    record Divider(float left, float right, float top, float thickness, int rgb) {}

    /** How a table's borders look in the PDF: colour, and thickness in points. */
    record Borders(int rgb, float thickness) {}

    static final WordShapes NONE = new WordShapes(List.of(), List.of(), List.of(), List.of(), List.of());

    private final List<Block> blocks;        // behind whole lines
    private final List<Block> highlights;    // behind a few words
    private final List<Bar> bars;
    private final List<Divider> dividers;
    private final List<Shape> inTables;

    private WordShapes(List<Block> blocks, List<Block> highlights, List<Bar> bars, List<Divider> dividers, List<Shape> inTables) {
        this.blocks = blocks;
        this.highlights = highlights;
        this.bars = bars;
        this.dividers = dividers;
        this.inTables = inTables;
    }

    /**
     * @param tables      the page's tables (their shapes are kept for the table, not used as dividers)
     * @param textWidth   the width between the margins: a background at least half as wide is a block
     */
    static WordShapes of(List<Shape> shapes, List<TableFinder.Table<TextWord>> tables, float textWidth) {
        List<Shape> boxes = new ArrayList<>(), uprights = new ArrayList<>(), flats = new ArrayList<>(), inTables = new ArrayList<>();
        for (Shape s : shapes) {
            if (inAnyTable(s, tables)) {
                // Inside a table, a small box behind a few words is inline code, not the cell's background.
                if (!s.line() && s.height() <= 18 && s.width() > 4.5f) boxes.add(s);
                else inTables.add(s);
            } else if (s.width() <= 4.5f && s.height() > s.width() * 2) {
                uprights.add(s);
            } else if (s.height() <= 3f && s.width() > s.height() * 10) {
                flats.add(s);
            } else if (!s.line()) {
                boxes.add(s);
            }
        }
        List<Block> blocks = new ArrayList<>(), highlights = new ArrayList<>();
        for (Shape box : joinStacked(boxes)) {
            Block block = new Block(box.left(), box.top(), box.right(), box.bottom(), box.rgb());
            boolean inTable = inAnyTable(box, tables);
            // One line high is a highlight however wide (long inline code); a block has padding around its lines.
            if (!inTable && box.height() > 16 && (box.width() >= textWidth * 0.5f || box.height() > 24)) blocks.add(block);
            else highlights.add(block);
        }
        List<Bar> bars = new ArrayList<>();
        for (Shape bar : joinStacked(uprights)) {
            if (bar.height() >= 8) bars.add(new Bar(bar.left(), bar.right(), bar.top(), bar.bottom(), bar.rgb()));
        }
        List<Divider> dividers = new ArrayList<>();
        for (Shape flat : flats) {
            if (flat.width() >= Math.min(100, textWidth * 0.3f)) {
                dividers.add(new Divider(flat.left(), flat.right(), flat.top(), Math.max(0.25f, flat.height()), flat.rgb()));
            }
        }
        dividers.sort(Comparator.comparingDouble(Divider::top));
        return new WordShapes(blocks, highlights, bars, dividers, inTables);
    }

    /** The background block behind a stretch of text, or null. */
    Block blockBehind(float left, float top, float right, float bottom) {
        for (Block b : blocks) {
            if (b.left() <= left + 1 && b.right() >= right - 1 && b.top() <= top + 3 && b.bottom() >= bottom - 3) return b;
        }
        return null;
    }

    /** The colour behind one word (inline code), or -1. */
    int highlightBehind(TextWord word) {
        float top = word.baseline() - word.size() * 0.75f, bottom = word.baseline() + word.size() * 0.2f;
        for (Block h : highlights) {
            if (h.left() <= word.left() + 1.5f && h.right() >= word.right() - 1.5f && h.top() <= top + 2 && h.bottom() >= bottom - 2) {
                return h.rgb();
            }
        }
        return -1;
    }

    /** A quote bar just left of text spanning these lines, or null. */
    Bar barBeside(float textLeft, float top, float bottom) {
        for (Bar bar : bars) {
            float overlap = Math.min(bar.bottom(), bottom) - Math.max(bar.top(), top);
            if (bar.right() <= textLeft + 1 && textLeft - bar.right() <= 40 && overlap >= (bottom - top) * 0.5f) return bar;
        }
        return null;
    }

    List<Divider> dividers() {
        return dividers;
    }

    /** The colour of a table cell's background, found by a point inside the cell; -1 for none. */
    int cellBackground(float x, float y) {
        for (Shape s : inTables) {
            if (!s.line() && s.width() > 4.5f && s.height() > 3 && s.left() <= x && s.right() >= x && s.top() <= y && s.bottom() >= y) {
                return s.rgb();
            }
        }
        return -1;
    }

    /** The table's border colour and thickness (the most common among its lines), or null if it has no lines. */
    Borders borders(TableFinder.Table<TextWord> table) {
        Map<Integer, Integer> colours = new HashMap<>();
        List<Float> thickness = new ArrayList<>();
        for (Shape s : inTables) {
            boolean thin = s.width() <= 3 || s.height() <= 3;
            if (!thin || !within(s, table)) continue;
            colours.merge(s.rgb(), 1, Integer::sum);
            thickness.add(Math.min(s.width(), s.height()));
        }
        if (colours.isEmpty()) return null;
        int rgb = colours.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        thickness.sort(Float::compare);
        return new Borders(rgb, thickness.get(thickness.size() / 2));
    }

    /** The heights of a ruled table's rows, from its horizontal lines; empty if they don't fit the rows. */
    List<Float> rowHeights(TableFinder.Table<TextWord> table) {
        List<Float> ys = new ArrayList<>();
        for (Shape s : inTables) {
            if (s.height() <= 3 && s.width() > (table.right() - table.left()) * 0.5f && within(s, table)) {
                float y = (s.top() + s.bottom()) / 2;
                if (ys.stream().noneMatch(v -> Math.abs(v - y) < 1.5f)) ys.add(y);
            }
        }
        ys.sort(Float::compare);
        List<Float> heights = new ArrayList<>();
        for (int i = 1; i < ys.size(); i++) heights.add(ys.get(i) - ys.get(i - 1));
        return heights.size() == table.cells().size() ? heights : List.of();
    }

    private static boolean within(Shape s, TableFinder.Table<TextWord> t) {
        float cx = (s.left() + s.right()) / 2, cy = (s.top() + s.bottom()) / 2;
        return cx >= t.left() - 3 && cx <= t.right() + 3 && cy >= t.top() - 3 && cy <= t.bottom() + 3;
    }

    private static boolean inAnyTable(Shape s, List<TableFinder.Table<TextWord>> tables) {
        for (TableFinder.Table<TextWord> t : tables) if (within(s, t)) return true;
        return false;
    }

    /** Joins boxes of one colour and width stacked with no gap (a code block drawn line by line) into one. */
    private static List<Shape> joinStacked(List<Shape> shapes) {
        List<Shape> sorted = new ArrayList<>(shapes);
        sorted.sort(Comparator.comparingDouble(Shape::top));
        List<Shape> joined = new ArrayList<>();
        for (Shape s : sorted) {
            boolean merged = false;
            for (int i = joined.size() - 1; i >= 0 && !merged; i--) {
                Shape j = joined.get(i);
                if (j.rgb() == s.rgb() && Math.abs(j.left() - s.left()) < 1 && Math.abs(j.right() - s.right()) < 1
                    && s.top() - j.bottom() < 1.5f && s.top() >= j.top() - 0.5f) {
                    joined.set(i, new Shape(j.left(), j.top(), j.right(), Math.max(j.bottom(), s.bottom()), j.rgb(), j.line() && s.line()));
                    merged = true;
                }
            }
            if (!merged) joined.add(s);
        }
        return joined;
    }
}
