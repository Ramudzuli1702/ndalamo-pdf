package com.ndalamo.screens;

import com.ndalamo.models.Signature;
import com.ndalamo.services.PageThumbnails;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Placing a signature on a page: the page is shown large with the signature on it;
 * drag the signature to move it, drag its corner to make it bigger or smaller (it
 * keeps its shape). The arrows go to other pages; it starts on the last page, where
 * signatures usually go. The place is kept as fractions of the page as shown.
 */
public class PlaceSignatureDialog extends VBox {

    private static final double VIEW = 560;          // longest side of the page on screen
    private static final double HANDLE = 10;

    private final PageThumbnails thumbnails;
    private final Path file;
    private final int pageCount;
    private final ImageView pageView = new ImageView();
    private final ImageView signatureView = new ImageView();
    private final StackPane signatureFrame;
    private final Region handle = new Region();
    private final Pane canvas = new Pane();
    private final Label pageLabel = new Label();
    private final double aspect;                     // signature height / width
    private int page;
    private double shownW, shownH;                   // the page's size on screen
    private double left, top, width;                 // the signature, as fractions of the page
    private double dragX, dragY, startLeft, startTop, startWidth;

    public PlaceSignatureDialog(MainWindow window, PageThumbnails thumbnails, Path file, int pageCount, Signature start,
                                byte[] png, Consumer<Signature> onPlaced) {
        super(14);
        this.thumbnails = thumbnails;
        this.file = file;
        this.pageCount = Math.max(1, pageCount);
        getStyleClass().add("dialog-card");
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        Image signature = new Image(new ByteArrayInputStream(png));
        aspect = signature.getHeight() / signature.getWidth();
        signatureView.setImage(signature);
        signatureView.setPreserveRatio(false);
        signatureFrame = new StackPane(signatureView);
        signatureFrame.getStyleClass().add("signature-frame");
        handle.getStyleClass().add("resize-handle");
        handle.setPrefSize(HANDLE, HANDLE);
        handle.setMaxSize(HANDLE, HANDLE);

        // Where it starts: as placed before, or near the bottom right of the last page.
        page = start != null ? Math.min(start.page(), this.pageCount) : this.pageCount;
        width = start != null ? start.width() : 0.3;
        left = start != null ? start.left() : 0.6;
        top = start != null ? start.top() : 0.78;

        Label title = new Label("Place your signature");
        title.getStyleClass().add("dialog-title");
        Label hint = new Label("Drag it where it should go. Drag the blue corner to change its size.");
        hint.getStyleClass().add("dialog-text");

        pageView.setPreserveRatio(true);
        canvas.getChildren().addAll(pageView, signatureFrame, handle);
        canvas.setPrefSize(VIEW, VIEW);
        canvas.setStyle("-fx-background-color: #F4F5F7;");
        signatureFrame.setOnMousePressed(e -> startDrag(e.getSceneX(), e.getSceneY()));
        signatureFrame.setOnMouseDragged(e -> {
            left = clamp(startLeft + (e.getSceneX() - dragX) / shownW, 0, 1 - width);
            top = clamp(startTop + (e.getSceneY() - dragY) / shownH, 0, 1 - height());
            layoutSignature();
        });
        handle.setOnMousePressed(e -> startDrag(e.getSceneX(), e.getSceneY()));
        handle.setOnMouseDragged(e -> {
            double newWidth = startWidth + (e.getSceneX() - dragX) / shownW;
            double maxByHeight = (1 - top) * shownH / (aspect * shownW);
            width = clamp(newWidth, 0.05, Math.min(1 - left, maxByHeight));
            layoutSignature();
        });

        Button previous = new Button("‹");
        previous.getStyleClass().add("button-secondary");
        previous.setAccessibleText("Previous page");
        previous.setOnAction(e -> showPage(page - 1));
        Button next = new Button("›");
        next.getStyleClass().add("button-secondary");
        next.setAccessibleText("Next page");
        next.setOnAction(e -> showPage(page + 1));
        pageLabel.getStyleClass().add("small-muted");
        HBox pages = new HBox(8, previous, pageLabel, next);
        pages.setAlignment(Pos.CENTER_LEFT);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button place = new Button("Place signature");
        place.getStyleClass().addAll("button-primary", "button-dialog");
        place.setDefaultButton(true);
        place.setOnAction(e -> {
            window.closeDialog();
            onPlaced.accept(new Signature(png, page, left, top, width));
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, pages, push, cancel, place);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, hint, new StackPane(canvas), buttons);
        showPage(page);
    }

    private void startDrag(double x, double y) {
        dragX = x;
        dragY = y;
        startLeft = left;
        startTop = top;
        startWidth = width;
    }

    private double height() {
        return width * aspect * shownW / shownH;
    }

    private void showPage(int number) {
        page = Math.max(1, Math.min(pageCount, number));
        pageLabel.setText("Page " + page + " of " + pageCount);
        thumbnails.renderLarge(file, page, 0, (int) VIEW * 2, image -> {
            pageView.setImage(image);
            double scale = Math.min(VIEW / image.getWidth(), VIEW / image.getHeight());
            shownW = image.getWidth() * scale;
            shownH = image.getHeight() * scale;
            pageView.setFitWidth(shownW);
            pageView.setFitHeight(shownH);
            pageView.relocate((VIEW - shownW) / 2, (VIEW - shownH) / 2);
            top = clamp(top, 0, Math.max(0, 1 - height()));
            layoutSignature();
        });
    }

    private void layoutSignature() {
        if (shownW <= 0) return;
        double x = (VIEW - shownW) / 2 + left * shownW, y = (VIEW - shownH) / 2 + top * shownH;
        double w = width * shownW, h = w * aspect;
        signatureView.setFitWidth(w);
        signatureView.setFitHeight(h);
        signatureFrame.setPrefSize(w, h);
        signatureFrame.resizeRelocate(x, y, w, h);
        handle.relocate(x + w - HANDLE / 2, y + h - HANDLE / 2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
