package com.ndalamo.models;

import java.util.List;

/**
 * The changes made in the Edit PDF editor, ready for EditTool to apply. Positions are
 * fractions of the page as shown (0,0 top left; 1,1 bottom right), like Signature, so
 * they don't depend on the size the page was drawn at.
 *
 * @param removals  areas whose letters are taken out of the page
 * @param additions text typed onto pages
 */
public record PdfEdits(List<Removal> removals, List<Addition> additions) {

    /** Letters whose middle lies inside this area of the page are removed. */
    public record Removal(int page, double left, double top, double right, double bottom) {
        public boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    /**
     * Text added at a point: {@code left, top} is where the first line's top-left corner goes.
     *
     * @param size  in points
     * @param color e.g. "#1F2430"
     * @param font  one of {@link #FONTS}
     */
    public record Addition(int page, double left, double top, String text, float size, boolean bold, String color,
                           String font) {}

    /** The fonts text can be added in, all bundled with the app. */
    public static final List<String> FONTS = List.of("Sans", "Serif", "Mono", "Handwriting");

    public boolean isEmpty() {
        return removals.isEmpty() && additions.isEmpty();
    }

    /** "3 removals, 1 addition" for the tool screen. */
    public String describe() {
        String r = removals.size() == 1 ? "1 removal" : removals.size() + " removals";
        String a = additions.size() == 1 ? "1 addition" : additions.size() + " additions";
        if (removals.isEmpty()) return a;
        if (additions.isEmpty()) return r;
        return r + ", " + a;
    }
}
