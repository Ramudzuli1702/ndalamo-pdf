package com.ndalamo.utils;

import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * Simple line icons (2 px stroke, SPEC §6.1), using the same path data as the
 * mockups in docs/ui/screens. Drawn with SVGPath, so no icon library is needed.
 *
 * Usage: {@code Icons.search(16, Theme.TEXT_MUTED)}
 */
public final class Icons {

    private static final double DRAWING_SIZE = 24;

    private Icons() {}

    public static Region search(double size, String color) {
        return draw(size, color, "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0z", "M20 20l-3.5-3.5");
    }

    public static Region settings(double size, String color) {
        return draw(size, color, "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
                "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1");
    }

    public static Region upload(double size, String color) {
        return draw(size, color, "M12 16V4", "M7 9l5-5 5 5", "M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3");
    }

    public static Region lock(double size, String color) {
        return draw(size, color, "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z",
                "M8 11V7a4 4 0 0 1 8 0v4");
    }

    public static Region document(double size, String color) {
        return draw(size, color, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5");
    }

    public static Region back(double size, String color) {
        return draw(size, color, "M15 18l-6-6 6-6");
    }

    private static Region draw(double size, String color, String... paths) {
        Group drawing = new Group();
        for (String path : paths) {
            SVGPath svg = new SVGPath();
            svg.setContent(path);
            svg.setFill(null);
            svg.setStroke(Color.web(color));
            svg.setStrokeWidth(2);
            svg.setStrokeLineCap(StrokeLineCap.ROUND);
            svg.setStrokeLineJoin(StrokeLineJoin.ROUND);
            drawing.getChildren().add(svg);
        }
        drawing.getTransforms().add(new Scale(size / DRAWING_SIZE, size / DRAWING_SIZE, 0, 0));
        drawing.setManaged(false);

        Pane box = new Pane(drawing);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);   // clicks go to the button the icon sits on
        return box;
    }
}
