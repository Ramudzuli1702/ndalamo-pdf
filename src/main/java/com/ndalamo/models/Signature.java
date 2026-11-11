package com.ndalamo.models;

/**
 * A signature placed on a page, as Sign PDF uses it: the signature as a PNG picture
 * with a see-through background, and where it goes.
 *
 * @param png    the signature picture (drawn, typed or from a photo), transparent around the ink
 * @param page   the page it goes on, from 1
 * @param left   its left edge, as a fraction of the page's width as shown
 * @param top    its top edge, as a fraction of the page's height as shown
 * @param width   its width, as a fraction of the page's width (the height follows from the picture's shape)
 * @param opacity 0.1 (faint) to 1 (solid ink)
 */
public record Signature(byte[] png, int page, double left, double top, double width, double opacity) {

    /** A solid signature. */
    public Signature(byte[] png, int page, double left, double top, double width) {
        this(png, page, left, top, width, 1);
    }

    public Signature movedTo(int newPage, double newLeft, double newTop, double newWidth) {
        return new Signature(png, newPage, newLeft, newTop, newWidth, opacity);
    }
}
