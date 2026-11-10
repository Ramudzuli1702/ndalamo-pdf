package com.ndalamo.screens;

import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * Draws the Ndalamo logo: a page with three rising bars, the tallest sprouting a leaf.
 *
 * JavaFX can't open .svg files, so the logo is redrawn here from the same path
 * data as the files in src/main/resources/brand/ (a 64 × 64 drawing, scaled to
 * whatever size is asked for).
 */
public final class LogoMark {

    private static final double DRAWING_SIZE = 64;
    private static final String PAGE = "M38 6H16a5 5 0 0 0-5 5v42a5 5 0 0 0 5 5h32a5 5 0 0 0 5-5V21z";
    private static final String FOLD = "M38 6v15h15";
    private static final String BARS = "M22 48v-5M31 48v-10M40 48v-14";
    private static final String LEAF = "M40 34c0-6 3-9 9-9 0 6-3 9-9 9z";

    // Simpler two-bar version for tiny icons (brand/ndalamo-favicon-small.svg) —
    // the full logo turns into a smudge at 16–24 px.
    private static final String SMALL_BARS = "M24 48v-6M38 48v-14";
    private static final String SMALL_LEAF = "M38 34c0-7 4-10 11-10 0 7-4 10-11 10z";

    private LogoMark() {}

    /** Dark outline with green bars, for light backgrounds. */
    public static Region color(double size) {
        // The outline follows the theme: near-black on light, near-white on dark (screens are rebuilt on a switch).
        return draw(size, Color.web(Theme.isDark() ? "#E8EAEF" : "#16181D"), Color.web(Theme.LOGO_GREEN), false);
    }

    /** All white, for blue or dark backgrounds (e.g. the home header tile). */
    public static Region white(double size) {
        return draw(size, Color.WHITE, Color.WHITE, false);
    }

    /** White logo on a blue rounded square — the window and taskbar icon. */
    public static Region appIcon(double size) {
        Rectangle tile = new Rectangle(size, size, Color.web(Theme.BRAND_BLUE));
        // Corner radius and logo size match brand/ndalamo-app-icon.svg (56 and 160 out of 256).
        tile.setArcWidth(size * 2 * 56 / 256);
        tile.setArcHeight(size * 2 * 56 / 256);
        boolean tiny = size <= 24;
        StackPane icon = new StackPane(tile, draw(size * 160 / 256, Color.WHITE, Color.WHITE, tiny));
        icon.setAlignment(Pos.CENTER);
        fixSize(icon, size);
        return icon;
    }

    /**
     * The app icon at the sizes Windows uses for the title bar, taskbar and tray. They're the same
     * pictures as the installed program's icon (brand/icons, drawn from appIcon once and kept as
     * files): drawn afresh while the app starts, before its theme is in place, they came out muddled
     * in the title bar. Drawn here only if a file is missing.
     */
    public static Image[] windowIcons() {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        Image[] images = new Image[sizes.length];
        SnapshotParameters transparent = new SnapshotParameters();
        transparent.setFill(Color.TRANSPARENT);
        for (int i = 0; i < sizes.length; i++) {
            var file = LogoMark.class.getResource("/brand/icons/ndalamo-" + sizes[i] + ".png");
            images[i] = file != null ? new Image(file.toExternalForm()) : appIcon(sizes[i]).snapshot(transparent, null);
        }
        return images;
    }

    private static Region draw(double size, Color outline, Color growth, boolean small) {
        // Thin lines vanish when the logo is small, so small logos get thicker strokes.
        double stroke = size <= 32 ? 5 : 4;

        SVGPath leaf = new SVGPath();
        leaf.setContent(small ? SMALL_LEAF : LEAF);
        leaf.setFill(growth);

        Group drawing = small
                ? new Group(line(PAGE, outline, 6), line(SMALL_BARS, growth, 7), leaf)
                : new Group(line(PAGE, outline, stroke), line(FOLD, outline, stroke),
                            line(BARS, growth, stroke + 1), leaf);
        drawing.getTransforms().add(new Scale(size / DRAWING_SIZE, size / DRAWING_SIZE, 0, 0));
        drawing.setManaged(false);

        Pane box = new Pane(drawing);
        fixSize(box, size);
        return box;
    }

    private static SVGPath line(String path, Color color, double width) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.setFill(null);
        svg.setStroke(color);
        svg.setStrokeWidth(width);
        svg.setStrokeLineCap(StrokeLineCap.ROUND);
        svg.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return svg;
    }

    private static void fixSize(Region region, double size) {
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
    }
}
