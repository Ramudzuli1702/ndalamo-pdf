package com.ndalamo.screens;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.List;

/**
 * Cards in equal columns that fill the width, like the mockup's CSS grid
 * (repeat(auto-fill, minmax(190px, 1fr))): as many columns as fit at the minimum width,
 * every card stretched to the column width, and every card in a row as tall as the
 * tallest one there.
 *
 * It reports its height for the width it's given, so the section below always starts
 * after the last row. (A FlowPane worked its height out for one line of cards; on a wide
 * window a wrapped card ran over the next section's heading.)
 */
public final class CardGrid extends Pane {

    private final double minCardWidth, gap;

    public CardGrid(double minCardWidth, double gap) {
        this.minCardWidth = minCardWidth;
        this.gap = gap;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;                    // height depends on width
    }

    private int columns(double width) {
        return Math.max(1, (int) ((width + gap) / (minCardWidth + gap)));
    }

    private double cardWidth(double width, int columns) {
        return Math.floor((width - (columns - 1) * gap) / columns);
    }

    private double innerWidth(double width) {
        Insets in = getInsets();
        double w = width >= 0 ? width : getWidth();
        if (w <= 0) w = minCardWidth * 3 + gap * 2;       // before the first layout: a sensible guess
        return Math.max(minCardWidth, w - in.getLeft() - in.getRight());
    }

    /** The height of each row of cards at this inner width. */
    private double[] rowHeights(double width) {
        List<Node> cards = getManagedChildren();
        int columns = columns(width);
        double cardWidth = cardWidth(width, columns);
        double[] rows = new double[(cards.size() + columns - 1) / columns];
        for (int i = 0; i < cards.size(); i++) {
            rows[i / columns] = Math.max(rows[i / columns], cards.get(i).prefHeight(cardWidth));
        }
        return rows;
    }

    @Override
    protected double computePrefHeight(double width) {
        double[] rows = rowHeights(innerWidth(width));
        double total = 0;
        for (double row : rows) total += row;
        Insets in = getInsets();
        return total + Math.max(0, rows.length - 1) * gap + in.getTop() + in.getBottom();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return minCardWidth + in.getLeft() + in.getRight();  // takes whatever width it's given
    }

    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double width = innerWidth(getWidth());
        int columns = columns(width);
        double cardWidth = cardWidth(width, columns);
        double[] rows = rowHeights(width);
        List<Node> cards = getManagedChildren();
        double y = in.getTop();
        for (int i = 0; i < cards.size(); i++) {
            int row = i / columns, column = i % columns;
            if (column == 0 && row > 0) y += rows[row - 1] + gap;
            cards.get(i).resizeRelocate(in.getLeft() + column * (cardWidth + gap), y, cardWidth, rows[row]);
        }
    }
}
