package com.ndalamo.screens;

import com.ndalamo.models.Signature;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Placing a signature on a page: the page is shown large with the signature on it.
 * Drag the signature to move it; drag any of its four corners to make it bigger or
 * smaller (it keeps its shape, and the opposite corner stays put). The opacity slider
 * makes it lighter, for a signature over printed lines or a stamp-like look. "Crop…"
 * trims the signature picture itself (extra space around a photo of a signature, a stray
 * mark). The arrows go to other pages; it starts on the last page, where signatures
 * usually go. The place is kept as fractions of the page as shown.
 */
public class PlaceSignatureDialog extends VBox {

    private static final double VIEW = 560;          // longest side of the page on screen
    private static final double HANDLE = 10;
    private static final double CROP_W = 560, CROP_H = 320;   // the crop view's box

    private final PageThumbnails thumbnails;
    private final Path file;
    private final int pageCount;
    private final ImageView pageView = new ImageView();
    private final ImageView signatureView = new ImageView();
    private final StackPane signatureFrame;
    private final Region[] handles = new Region[4];  // top-left, top-right, bottom-right, bottom-left
    private final Pane canvas = new Pane();
    private final Label pageLabel = new Label();
    private final VBox placeView = new VBox(14);
    private final VBox cropView = new VBox(14);
    private byte[] png;
    private double aspect;                           // signature height / width
    private double opacity;
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
        this.png = png;
        getStyleClass().add("dialog-card");
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        Image signature = new Image(new ByteArrayInputStream(png));
        aspect = signature.getHeight() / signature.getWidth();
        signatureView.setImage(signature);
        signatureView.setPreserveRatio(false);
        signatureFrame = new StackPane(signatureView);
        signatureFrame.getStyleClass().add("signature-frame");

        // Where it starts: as placed before, or near the bottom right of the last page.
        page = start != null ? Math.min(start.page(), this.pageCount) : this.pageCount;
        width = start != null ? start.width() : 0.3;
        left = start != null ? start.left() : 0.6;
        top = start != null ? start.top() : 0.78;
        opacity = start != null ? start.opacity() : 1;
        signatureView.setOpacity(opacity);

        Label title = new Label("Place your signature");
        title.getStyleClass().add("dialog-title");
        Label hint = new Label("Drag it where it should go. Drag any blue corner to change its size.");
        hint.getStyleClass().add("dialog-text");

        pageView.setPreserveRatio(true);
        canvas.getChildren().addAll(pageView, signatureFrame);
        canvas.setPrefSize(VIEW, VIEW);
        canvas.setStyle("-fx-background-color: " + Theme.BG + ";");
        signatureFrame.setOnMousePressed(e -> startDrag(e.getSceneX(), e.getSceneY()));
        signatureFrame.setOnMouseDragged(e -> {
            if (shownW <= 0) return;
            left = clamp(startLeft + (e.getSceneX() - dragX) / shownW, 0, 1 - width);
            top = clamp(startTop + (e.getSceneY() - dragY) / shownH, 0, 1 - height());
            layoutSignature();
        });
        Cursor[] cursors = {Cursor.NW_RESIZE, Cursor.NE_RESIZE, Cursor.SE_RESIZE, Cursor.SW_RESIZE};
        for (int i = 0; i < 4; i++) {
            Region handle = new Region();
            handle.getStyleClass().add("resize-handle");
            handle.setPrefSize(HANDLE, HANDLE);
            handle.setMaxSize(HANDLE, HANDLE);
            handle.setCursor(cursors[i]);
            int corner = i;
            handle.setOnMousePressed(e -> {
                e.consume();
                startDrag(e.getSceneX(), e.getSceneY());
            });
            handle.setOnMouseDragged(e -> {
                e.consume();
                resizeFrom(corner, e.getSceneX() - dragX);
            });
            handles[i] = handle;
            canvas.getChildren().add(handle);
        }

        Slider fade = new Slider(10, 100, Math.round(opacity * 100));
        fade.setPrefWidth(160);
        fade.setAccessibleText("Opacity");
        Label fadeValue = new Label(Math.round(opacity * 100) + "%");
        fadeValue.getStyleClass().add("small-muted");
        fadeValue.setMinWidth(36);
        fade.valueProperty().addListener((o, was, now) -> {
            opacity = Math.round(now.doubleValue()) / 100.0;
            signatureView.setOpacity(opacity);
            fadeValue.setText(Math.round(opacity * 100) + "%");
        });
        Label fadeLabel = new Label("Opacity");
        fadeLabel.getStyleClass().add("small-heading");
        Button crop = new Button("Crop…");
        crop.getStyleClass().add("button-secondary");
        crop.setOnAction(e -> showCrop());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox looks = new HBox(10, fadeLabel, fade, fadeValue, gap, crop);
        looks.setAlignment(Pos.CENTER_LEFT);

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
            onPlaced.accept(new Signature(this.png, page, left, top, width, opacity));
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, pages, push, cancel, place);
        buttons.setAlignment(Pos.CENTER_LEFT);

        placeView.getChildren().addAll(title, hint, new StackPane(canvas), looks, buttons);
        cropView.setVisible(false);
        cropView.setManaged(false);
        getChildren().addAll(placeView, cropView);
        showPage(page);
    }

    private void startDrag(double x, double y) {
        dragX = x;
        dragY = y;
        startLeft = left;
        startTop = top;
        startWidth = width;
    }

    /**
     * Resizes by dragging one corner sideways by {@code dx} screen pixels: the opposite
     * corner stays where it is and the signature keeps its shape.
     */
    private void resizeFrom(int corner, double dx) {
        if (shownW <= 0) return;                      // the page isn't drawn yet
        boolean leftSide = corner == 0 || corner == 3, topSide = corner == 0 || corner == 1;
        double right = startLeft + startWidth;
        double bottom = startTop + startWidth * aspect * shownW / shownH;
        double wanted = startWidth + (leftSide ? -dx : dx) / shownW;
        // Room to grow towards the dragged corner, across and down/up the page.
        double roomAcross = leftSide ? right : 1 - startLeft;
        double roomDown = (topSide ? bottom : 1 - startTop) * shownH / (aspect * shownW);
        width = clamp(wanted, 0.05, Math.min(roomAcross, roomDown));
        left = leftSide ? right - width : startLeft;
        top = topSide ? bottom - height() : startTop;
        layoutSignature();
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
        double[][] corners = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        for (int i = 0; i < 4; i++) handles[i].relocate(corners[i][0] - HANDLE / 2, corners[i][1] - HANDLE / 2);
    }

    // ── Cropping the signature picture ───────────────────────────────────

    /** Swaps the page view for the signature on its own with a crop frame; Apply trims the picture. */
    private void showCrop() {
        BufferedImage picture;
        try {
            picture = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return;
        }
        if (picture == null) return;
        Image image = new Image(new ByteArrayInputStream(png));
        double scale = Math.min(CROP_W / image.getWidth(), CROP_H / image.getHeight());
        double iw = image.getWidth() * scale, ih = image.getHeight() * scale;
        double ox = (CROP_W - iw) / 2, oy = (CROP_H - ih) / 2;

        ImageView view = new ImageView(image);
        view.setFitWidth(iw);
        view.setFitHeight(ih);
        view.relocate(ox, oy);
        Rectangle paper = new Rectangle(ox, oy, iw, ih);
        paper.setFill(Color.WHITE);
        Pane box = new Pane(paper, view);
        box.setPrefSize(CROP_W, CROP_H);
        box.setMinSize(CROP_W, CROP_H);
        box.setStyle("-fx-background-color: " + Theme.BG + ";");

        // The kept part, as fractions of the picture: starts as all of it.
        double[] keep = {0, 0, 1, 1};
        Rectangle frame = new Rectangle();
        frame.setFill(Color.TRANSPARENT);
        frame.setStroke(Color.web("#2F5BEA"));
        frame.setStrokeWidth(1.5);
        frame.getStrokeDashArray().addAll(5.0, 4.0);
        frame.setCursor(Cursor.MOVE);
        Rectangle[] shade = new Rectangle[4];                    // dims what will be cut off
        for (int i = 0; i < 4; i++) {
            shade[i] = new Rectangle();
            shade[i].setFill(Color.web("#0B1220", 0.45));
            shade[i].setMouseTransparent(true);
        }
        box.getChildren().addAll(shade);
        box.getChildren().add(frame);
        Region[] grips = new Region[4];
        Runnable layout = () -> {
            double x1 = ox + keep[0] * iw, y1 = oy + keep[1] * ih, x2 = ox + keep[2] * iw, y2 = oy + keep[3] * ih;
            frame.setX(x1);
            frame.setY(y1);
            frame.setWidth(x2 - x1);
            frame.setHeight(y2 - y1);
            setRect(shade[0], ox, oy, iw, y1 - oy);
            setRect(shade[1], ox, y2, iw, oy + ih - y2);
            setRect(shade[2], ox, y1, x1 - ox, y2 - y1);
            setRect(shade[3], x2, y1, ox + iw - x2, y2 - y1);
            double[][] corners = {{x1, y1}, {x2, y1}, {x2, y2}, {x1, y2}};
            for (int i = 0; i < 4; i++) grips[i].relocate(corners[i][0] - HANDLE / 2, corners[i][1] - HANDLE / 2);
        };
        double[] start = new double[6];
        Cursor[] cursors = {Cursor.NW_RESIZE, Cursor.NE_RESIZE, Cursor.SE_RESIZE, Cursor.SW_RESIZE};
        double min = 0.04;
        for (int i = 0; i < 4; i++) {
            Region grip = new Region();
            grip.getStyleClass().add("resize-handle");
            grip.setPrefSize(HANDLE, HANDLE);
            grip.setMaxSize(HANDLE, HANDLE);
            grip.setCursor(cursors[i]);
            int corner = i;
            grip.setOnMousePressed(e -> {
                e.consume();
                start[0] = e.getSceneX();
                start[1] = e.getSceneY();
                System.arraycopy(keep, 0, start, 2, 4);
            });
            grip.setOnMouseDragged(e -> {
                e.consume();
                double dx = (e.getSceneX() - start[0]) / iw, dy = (e.getSceneY() - start[1]) / ih;
                if (corner == 0 || corner == 3) keep[0] = clamp(start[2] + dx, 0, keep[2] - min);
                else keep[2] = clamp(start[4] + dx, keep[0] + min, 1);
                if (corner == 0 || corner == 1) keep[1] = clamp(start[3] + dy, 0, keep[3] - min);
                else keep[3] = clamp(start[5] + dy, keep[1] + min, 1);
                layout.run();
            });
            grips[i] = grip;
            box.getChildren().add(grip);
        }
        frame.setOnMousePressed(e -> {
            start[0] = e.getSceneX();
            start[1] = e.getSceneY();
            System.arraycopy(keep, 0, start, 2, 4);
        });
        frame.setOnMouseDragged(e -> {
            double w = start[4] - start[2], h = start[5] - start[3];
            double nx = clamp(start[2] + (e.getSceneX() - start[0]) / iw, 0, 1 - w);
            double ny = clamp(start[3] + (e.getSceneY() - start[1]) / ih, 0, 1 - h);
            keep[0] = nx;
            keep[1] = ny;
            keep[2] = nx + w;
            keep[3] = ny + h;
            layout.run();
        });
        layout.run();

        Label title = new Label("Crop your signature");
        title.getStyleClass().add("dialog-title");
        Label hint = new Label("Drag the corners so the frame holds only the signature. The dimmed part is cut off.");
        hint.getStyleClass().add("dialog-text");
        Button reset = new Button("Reset");
        reset.getStyleClass().add("button-secondary");
        reset.setOnAction(e -> {
            keep[0] = 0;
            keep[1] = 0;
            keep[2] = 1;
            keep[3] = 1;
            layout.run();
        });
        Button back = new Button("Cancel");
        back.getStyleClass().add("button-secondary");
        back.setOnAction(e -> showPlace());
        Button apply = new Button("Apply crop");
        apply.getStyleClass().addAll("button-primary", "button-dialog");
        apply.setOnAction(e -> {
            applyCrop(picture, keep);
            showPlace();
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, reset, push, back, apply);
        buttons.setAlignment(Pos.CENTER_LEFT);

        cropView.getChildren().setAll(title, hint, new StackPane(box), buttons);
        placeView.setVisible(false);
        placeView.setManaged(false);
        cropView.setVisible(true);
        cropView.setManaged(true);
    }

    private void showPlace() {
        cropView.setVisible(false);
        cropView.setManaged(false);
        placeView.setVisible(true);
        placeView.setManaged(true);
    }

    /** Trims the picture to the kept part; the signature keeps its width on the page. */
    private void applyCrop(BufferedImage picture, double[] keep) {
        int x = (int) Math.floor(keep[0] * picture.getWidth()), y = (int) Math.floor(keep[1] * picture.getHeight());
        int w = Math.max(1, (int) Math.ceil(keep[2] * picture.getWidth()) - x);
        int h = Math.max(1, (int) Math.ceil(keep[3] * picture.getHeight()) - y);
        w = Math.min(w, picture.getWidth() - x);
        h = Math.min(h, picture.getHeight() - y);
        if (x == 0 && y == 0 && w == picture.getWidth() && h == picture.getHeight()) return;
        BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = cropped.createGraphics();
        g.drawImage(picture.getSubimage(x, y, w, h), 0, 0, null);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", out);
            png = out.toByteArray();
        } catch (IOException e) {
            return;                                  // keep the uncropped signature
        }
        signatureView.setImage(new Image(new ByteArrayInputStream(png)));
        aspect = (double) h / w;
        top = clamp(top, 0, Math.max(0, 1 - height()));
        layoutSignature();
    }

    private static void setRect(Rectangle r, double x, double y, double w, double h) {
        r.setX(x);
        r.setY(y);
        r.setWidth(Math.max(0, w));
        r.setHeight(Math.max(0, h));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
