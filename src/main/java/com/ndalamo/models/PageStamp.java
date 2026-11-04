package com.ndalamo.models;

/**
 * Something drawn on top of a page — a page number or a watermark — described so
 * the page preview can show it before the tool runs.
 *
 * @param text       what's written, e.g. "Page 3 of 9" or "CONFIDENTIAL"
 * @param vertical   "top", "middle" or "bottom"
 * @param horizontal "left", "centre" or "right"
 * @param angle      degrees anticlockwise, e.g. 45 for a diagonal watermark
 * @param opacity    0–1
 * @param size       text height as a fraction of the page width (so it scales with the thumbnail)
 * @param colour     hex colour, e.g. "#808080"
 * @param tiled      repeat across the whole page
 */
public record PageStamp(String text, String vertical, String horizontal, double angle, double opacity,
                        double size, String colour, boolean tiled) {}
