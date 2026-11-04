package com.ndalamo.screens;

import com.ndalamo.models.WorkspacePage;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.utils.Format;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.function.Consumer;

/**
 * Crop: shows a page large; the user drags a box over the part to keep. The box is
 * turned into fractions of the page as stored (WorkspacePage.Crop), so it stays the
 * same part even if the page is turned afterwards.
 */
public class CropDialog extends VBox {

    private static final double VIEW = 520;    // longest side of the page on screen

    private final Rectangle box = new Rectangle();
    private double startX, startY;
    private double shownW, shownH;

    /**
     * @param pageCount how many pages the crop will apply to (shown on the button)
     * @param onCrop    gets the chosen crop; null means "use the whole page again"
     */
    public CropDialog(MainWindow window, PageThumbnails thumbnails, WorkspacePage page, int pageCount,
                      Consumer<WorkspacePage.Crop> onCrop) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label("Crop " + Format.count(pageCount, "page", "pages"));
        title.getStyleClass().add("dialog-title");
        Label hint = new Label("Drag a box over the part of the page to keep.");
        hint.getStyleClass().add("dialog-text");

        ImageView picture = new ImageView();
        picture.setPreserveRatio(true);
        picture.setFitWidth(VIEW);
        picture.setFitHeight(VIEW);
        Pane canvas = new Pane(picture, box);
        canvas.setPrefSize(VIEW, VIEW);
        canvas.setStyle("-fx-background-color: #F4F5F7; -fx-cursor: crosshair;");
        box.setFill(Color.web("#2F5BEA", 0.12));
        box.setStroke(Color.web("#2F5BEA"));
        box.setStrokeWidth(2);
        box.setVisible(false);

        Button apply = new Button("Crop " + Format.count(pageCount, "page", "pages"));
        apply.getStyleClass().addAll("button-primary", "button-dialog");
        apply.setDisable(true);

        thumbnails.renderLarge(page.source(), page.sourcePage(), page.extraRotation(), (int) VIEW * 2, image -> {
            picture.setImage(image);
            double scale = Math.min(VIEW / image.getWidth(), VIEW / image.getHeight());
            shownW = image.getWidth() * scale;
            shownH = image.getHeight() * scale;
            canvas.setPrefSize(shownW, shownH);
            if (page.crop() != null) {     // show the current crop to start from
                double[] c = page.crop().toShown(page.shownRotation());
                box.setX(c[0] * shownW);
                box.setY(c[1] * shownH);
                box.setWidth((c[2] - c[0]) * shownW);
                box.setHeight((c[3] - c[1]) * shownH);
                box.setVisible(true);
                apply.setDisable(false);
            }
        });

        canvas.setOnMousePressed(e -> {
            startX = clamp(e.getX(), shownW);
            startY = clamp(e.getY(), shownH);
            box.setVisible(true);
            box.setWidth(0);
            box.setHeight(0);
        });
        canvas.setOnMouseDragged(e -> {
            double x = clamp(e.getX(), shownW), y = clamp(e.getY(), shownH);
            box.setX(Math.min(startX, x));
            box.setY(Math.min(startY, y));
            box.setWidth(Math.abs(x - startX));
            box.setHeight(Math.abs(y - startY));
            apply.setDisable(box.getWidth() < 10 || box.getHeight() < 10);   // too small to be meant
        });

        Button reset = new Button("Use the whole page");
        reset.getStyleClass().add("button-secondary");
        reset.setOnAction(e -> {
            window.closeDialog();
            onCrop.accept(null);
        });
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            window.closeDialog();
            onCrop.accept(WorkspacePage.Crop.fromShown(box.getX() / shownW, box.getY() / shownH,
                    (box.getX() + box.getWidth()) / shownW, (box.getY() + box.getHeight()) / shownH,
                    page.shownRotation()));
        });
        HBox buttons = new HBox(8, reset, cancel, apply);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        StackPane framed = new StackPane(canvas);
        framed.setAlignment(Pos.CENTER);
        getChildren().addAll(title, hint, framed, buttons);
    }

    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(value, max));
    }
}
