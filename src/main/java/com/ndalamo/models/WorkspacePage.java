package com.ndalamo.models;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One page in the Page workspace: which file and page it comes from, plus the
 * changes made to it. Nothing is applied to the PDF until the user saves, so a
 * page here is just a recipe — and undo is just going back to an older list.
 *
 * Immutable: a change makes a new page with the same id, so the page stays
 * selected after being turned or cropped.
 *
 * @param id            unique within this run of the app, for selection and dragging
 * @param source        the file it comes from; null for an inserted blank page
 * @param sourcePage    page number in that file, from 1
 * @param baseRotation  how the page was already turned in its file (0, 90, 180, 270)
 * @param extraRotation how far the user has turned it, clockwise
 * @param crop          the part of the page to keep, or null for the whole page
 */
public record WorkspacePage(long id, Path source, int sourcePage, int baseRotation, int extraRotation, Crop crop) {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    /** A page from a file, as it is. */
    public static WorkspacePage of(Path source, int sourcePage, int baseRotation) {
        return new WorkspacePage(NEXT_ID.getAndIncrement(), source, sourcePage, baseRotation, 0, null);
    }

    /** An empty page, the same size as the page before it when saved. */
    public static WorkspacePage blank() {
        return new WorkspacePage(NEXT_ID.getAndIncrement(), null, 0, 0, 0, null);
    }

    public boolean isBlank() {
        return source == null;
    }

    /** The same page, turned a further {@code degrees} clockwise (90, 180 or -90). */
    public WorkspacePage turned(int degrees) {
        return new WorkspacePage(id, source, sourcePage, baseRotation, Math.floorMod(extraRotation + degrees, 360), crop);
    }

    public WorkspacePage cropped(Crop newCrop) {
        return new WorkspacePage(id, source, sourcePage, baseRotation, extraRotation, newCrop);
    }

    /** A copy with a new id — e.g. when the same page is added again from its file. */
    public WorkspacePage copy() {
        return new WorkspacePage(NEXT_ID.getAndIncrement(), source, sourcePage, baseRotation, extraRotation, crop);
    }

    /** Total turn as shown on screen: the file's own turn plus the user's. */
    public int shownRotation() {
        return Math.floorMod(baseRotation + extraRotation, 360);
    }

    /**
     * The part of a page to keep, as fractions (0–1) of the page as stored in the
     * file — measured from its left and bottom edges, before any turning. Storing it
     * this way means turning a page after cropping it keeps the same part.
     */
    public record Crop(double left, double bottom, double right, double top) {

        /**
         * From a box drawn on the page as the user sees it: fractions from the left
         * and top of the shown (turned) page.
         *
         * @param rotation how the page is shown, clockwise (0, 90, 180, 270)
         */
        public static Crop fromShown(double left, double top, double right, double bottom, int rotation) {
            double[] a = toStored(left, top, rotation);
            double[] b = toStored(right, bottom, rotation);
            return new Crop(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
        }

        /** The same box as fractions of the shown page: {left, top, right, bottom}. */
        public double[] toShown(int rotation) {
            double[] a = fromStored(left, bottom, rotation);
            double[] b = fromStored(right, top, rotation);
            return new double[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
        }

        // A point on the shown page (x from left, y from top) → the stored page (x from left, y from bottom).
        private static double[] toStored(double x, double yTop, int rotation) {
            return switch (Math.floorMod(rotation, 360)) {
                case 90 -> new double[] {yTop, x};           // the stored page's left edge is shown at the top
                case 180 -> new double[] {1 - x, yTop};
                case 270 -> new double[] {1 - yTop, 1 - x};
                default -> new double[] {x, 1 - yTop};
            };
        }

        // The opposite of toStored.
        private static double[] fromStored(double x, double yBottom, int rotation) {
            return switch (Math.floorMod(rotation, 360)) {
                case 90 -> new double[] {yBottom, x};
                case 180 -> new double[] {1 - x, yBottom};
                case 270 -> new double[] {1 - yBottom, 1 - x};
                default -> new double[] {x, 1 - yBottom};
            };
        }
    }
}
