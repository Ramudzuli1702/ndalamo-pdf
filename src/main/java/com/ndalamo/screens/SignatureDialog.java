package com.ndalamo.screens;

import com.ndalamo.tools.SignatureMaker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Making a signature for Sign PDF, three ways: draw it with the mouse or a pen, type
 * your name (written in the Dancing Script handwriting font), or use a photo or scan
 * of your signature on paper (the paper is made see-through). Black or blue ink.
 * The picture itself is made by SignatureMaker.
 */
public class SignatureDialog extends VBox {

    private static final double PAD_WIDTH = 520, PAD_HEIGHT = 170;
    private static final Color BLACK_INK = Color.web("#16181D"), BLUE_INK = Color.web("#1F3FA8");

    private final MainWindow window;
    private final List<List<double[]>> strokes = new ArrayList<>();
    private final Canvas pad = new Canvas(PAD_WIDTH, PAD_HEIGHT);
    private final TextField name = new TextField();
    private final Label typedPreview = new Label();
    private final ImageView photoPreview = new ImageView();
    private final Label problem = new Label();
    private Color ink = BLACK_INK;
    private byte[] photoSignature;

    public SignatureDialog(MainWindow window, Consumer<byte[]> onCreated) {
        super(14);
        this.window = window;
        getStyleClass().add("dialog-card");
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label("Create your signature");
        title.getStyleClass().add("dialog-title");

        // The three ways, as a row of toggle buttons; each shows its own area below.
        ToggleGroup ways = new ToggleGroup();
        ToggleButton draw = way("Draw", ways), type = way("Type", ways), photo = way("Picture", ways);
        draw.setSelected(true);
        HBox wayRow = new HBox(6, draw, type, photo);

        VBox drawArea = drawArea(), typeArea = typeArea(), photoArea = photoArea();
        StackPane areas = new StackPane(drawArea, typeArea, photoArea);
        typeArea.setVisible(false);
        photoArea.setVisible(false);
        ways.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                old.setSelected(true);                                    // one way is always chosen
                return;
            }
            drawArea.setVisible(now == draw);
            typeArea.setVisible(now == type);
            photoArea.setVisible(now == photo);
            problem.setText("");
        });

        // Ink colour, for drawing and typing.
        ToggleGroup inks = new ToggleGroup();
        ToggleButton black = inkButton("Black", BLACK_INK, inks), blue = inkButton("Blue", BLUE_INK, inks);
        black.setSelected(true);
        inks.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            ink = now == blue ? BLUE_INK : BLACK_INK;
            redrawPad();
            typedPreview.setTextFill(ink);
        });
        Label inkLabel = new Label("Ink");
        inkLabel.getStyleClass().add("small-muted");
        HBox inkRow = new HBox(6, inkLabel, black, blue);
        inkRow.setAlignment(Pos.CENTER_LEFT);

        problem.getStyleClass().add("problem-text");
        problem.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button next = new Button("Next: place it on the page");
        next.getStyleClass().addAll("button-primary", "button-dialog");
        next.setDefaultButton(true);
        next.setOnAction(e -> {
            try {
                java.awt.Color awtInk = new java.awt.Color((float) ink.getRed(), (float) ink.getGreen(), (float) ink.getBlue());
                byte[] png = draw.isSelected() ? SignatureMaker.drawn(strokes, PAD_WIDTH, PAD_HEIGHT, awtInk)
                        : type.isSelected() ? SignatureMaker.typed(name.getText(), awtInk) : photoSignature;
                if (png == null) {
                    problem.setText(draw.isSelected() ? "Draw your signature in the box first."
                            : type.isSelected() ? "Type your name first." : "Choose a photo of your signature first.");
                    return;
                }
                window.closeDialog();
                onCreated.accept(png);
            } catch (IOException ex) {
                problem.setText("The signature couldn't be made: " + ex.getMessage());
            }
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, inkRow, push, cancel, next);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, wayRow, areas, problem, buttons);
    }

    private static ToggleButton way(String label, ToggleGroup group) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.getStyleClass().add("segment-button");
        return button;
    }

    private static ToggleButton inkButton(String label, Color colour, ToggleGroup group) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.getStyleClass().add("segment-button");
        button.setTextFill(colour);
        return button;
    }

    private VBox drawArea() {
        pad.setStyle("-fx-cursor: crosshair;");
        StackPane frame = new StackPane(pad);
        frame.getStyleClass().add("signature-pad");
        frame.setMaxWidth(PAD_WIDTH + 2);
        pad.setOnMousePressed(e -> {
            List<double[]> stroke = new ArrayList<>();
            stroke.add(new double[]{e.getX(), e.getY()});
            strokes.add(stroke);
            redrawPad();
        });
        pad.setOnMouseDragged(e -> {
            if (strokes.isEmpty()) return;
            double x = Math.max(0, Math.min(PAD_WIDTH, e.getX())), y = Math.max(0, Math.min(PAD_HEIGHT, e.getY()));
            strokes.get(strokes.size() - 1).add(new double[]{x, y});
            redrawPad();
        });
        Label hint = new Label("Sign in the box with your mouse, touchpad or pen.");
        hint.getStyleClass().add("dialog-text");
        Button clear = new Button("Clear");
        clear.getStyleClass().add("link");
        clear.setOnAction(e -> {
            strokes.clear();
            redrawPad();
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox row = new HBox(hint, push, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        redrawPad();
        return new VBox(8, frame, row);
    }

    private void redrawPad() {
        GraphicsContext g = pad.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, PAD_WIDTH, PAD_HEIGHT);
        g.setStroke(Color.web("#D5D8DF"));                              // the line to sign on
        g.setLineWidth(1);
        g.strokeLine(24, PAD_HEIGHT - 40, PAD_WIDTH - 24, PAD_HEIGHT - 40);
        g.setStroke(ink);
        g.setLineWidth(2.6);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (List<double[]> stroke : strokes) {
            g.beginPath();
            g.moveTo(stroke.get(0)[0], stroke.get(0)[1]);
            for (double[] p : stroke) g.lineTo(p[0], p[1]);
            g.stroke();
        }
    }

    private VBox typeArea() {
        name.setPromptText("Type your full name");
        name.setAccessibleText("Your name");
        try (InputStream font = getClass().getResourceAsStream("/fonts/DancingScript-Regular.ttf")) {
            Font script = font == null ? null : Font.loadFont(font, 44);
            if (script != null) typedPreview.setFont(script);
        } catch (IOException ignored) {
            // The preview falls back to the normal font; the signature itself is still made in Dancing Script.
        }
        typedPreview.setTextFill(ink);
        typedPreview.textProperty().bind(name.textProperty());
        StackPane preview = new StackPane(typedPreview);
        preview.getStyleClass().add("signature-pad");
        preview.setMinSize(PAD_WIDTH + 2, PAD_HEIGHT - 50);
        preview.setMaxWidth(PAD_WIDTH + 2);
        VBox area = new VBox(8, name, preview);
        area.setMaxWidth(PAD_WIDTH + 2);
        return area;
    }

    private VBox photoArea() {
        Button choose = new Button("Choose a photo or scan…");
        choose.getStyleClass().add("button-secondary");
        Label hint = new Label("Sign a blank sheet of paper and take a photo of it. The paper is made see-through.");
        hint.getStyleClass().add("dialog-text");
        hint.setWrapText(true);
        photoPreview.setPreserveRatio(true);
        photoPreview.setFitWidth(PAD_WIDTH - 20);
        photoPreview.setFitHeight(PAD_HEIGHT - 70);
        StackPane preview = new StackPane(photoPreview);
        preview.getStyleClass().add("signature-pad");
        preview.setMinSize(PAD_WIDTH + 2, PAD_HEIGHT - 50);
        preview.setMaxWidth(PAD_WIDTH + 2);
        choose.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose a photo of your signature");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", "*.jpg", "*.jpeg", "*.png", "*.bmp"));
            File file = chooser.showOpenDialog(window.getScene().getWindow());
            if (file == null) return;
            try {
                var photo = ImageIO.read(file);
                photoSignature = photo == null ? null : SignatureMaker.fromPhoto(photo);
                if (photoSignature == null) {
                    problem.setText(photo == null ? "That file isn't a picture Ndalamo can read."
                            : "No signature could be found in that photo; it looks like blank paper.");
                    photoPreview.setImage(null);
                } else {
                    problem.setText("");
                    photoPreview.setImage(new Image(new ByteArrayInputStream(photoSignature)));
                }
            } catch (IOException ex) {
                problem.setText("That picture couldn't be read. Your file was not changed.");
            }
        });
        VBox area = new VBox(8, new HBox(choose), hint, preview);
        area.setPadding(new Insets(0));
        area.setMaxWidth(PAD_WIDTH + 2);
        return area;
    }
}
