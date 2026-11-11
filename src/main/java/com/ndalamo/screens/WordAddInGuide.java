package com.ndalamo.screens;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.List;

/**
 * A short guide to the Word add-in, step by step, each step with a screenshot of Word with the
 * part it's about ringed in blue: the Ndalamo group on the Home tab, Delete Page, Delete Pages
 * and the right-click menu. Opened from Settings › Word add-in and after installing the add-in.
 * The pictures are in resources/help/word (taken from Word, cropped below its title bar).
 */
public final class WordAddInGuide extends VBox {

    private static final double PICTURE_WIDTH = 760, PICTURE_HEIGHT = 340;   // every picture fits this box

    private record Step(String picture, String title, String text, String described) {}

    private static final List<Step> STEPS = List.of(
            new Step("word-ribbon.png", "Find NdalamoPDF on the Home tab",
                    "Open your document in Word. At the right end of the Home tab is the NdalamoPDF group, with "
                    + "Delete Page and Delete Pages.",
                    "Word's ribbon with the Home tab and the NdalamoPDF group ringed"),
            new Step("word-delete-page.png", "Delete the page you're on",
                    "Click anywhere in the page you want to remove, then click Delete Page. Made a mistake? "
                    + "Press Ctrl+Z and the page comes back.",
                    "A Word page with the text cursor in its heading, and the Delete Page button ringed"),
            new Step("word-delete-pages.png", "Delete several pages at once",
                    "Click Delete Pages, type the pages, like 3, 5-7, and click Delete. Ctrl+Z brings them all "
                    + "back in one go.",
                    "The Delete Pages box asking which pages to delete, with the Delete Pages button ringed"),
            new Step("word-right-click.png", "Or right-click the page",
                    "Right-click anywhere on the page and choose Delete This Page at the bottom of the menu. "
                    + "Don't see NdalamoPDF in Word? Install the add-in in Settings › Word add-in, then restart Word.",
                    "Word's right-click menu with Delete This Page ringed"));

    private final MainWindow window;
    private final ImageView picture = new ImageView();
    private final Label counter = new Label(), title = new Label(), text = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Button back = new Button("Back"), next = new Button("Next");
    private int index;

    private WordAddInGuide(MainWindow window) {
        super(10);
        this.window = window;
        getStyleClass().addAll("tour-card", "word-guide");
        setPadding(new Insets(18, 20, 16, 20));
        setMaxWidth(PICTURE_WIDTH + 40);
        setMaxHeight(Region.USE_PREF_SIZE);
        setFocusTraversable(true);

        picture.setFitWidth(PICTURE_WIDTH);
        picture.setFitHeight(PICTURE_HEIGHT);
        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        StackPane frame = new StackPane(picture);
        frame.getStyleClass().add("word-guide-picture");
        frame.setMinHeight(PICTURE_HEIGHT);               // the card keeps its size from step to step
        frame.setPrefHeight(PICTURE_HEIGHT);
        frame.setAlignment(Pos.CENTER);
        Rectangle round = new Rectangle();                // rounded corners on the screenshot
        round.setArcWidth(14);
        round.setArcHeight(14);
        // Layout bounds, which leave the clip out (the bounds in the parent include it: a loop).
        round.widthProperty().bind(picture.layoutBoundsProperty().map(b -> b.getWidth()));
        round.heightProperty().bind(picture.layoutBoundsProperty().map(b -> b.getHeight()));
        picture.setClip(round);

        counter.getStyleClass().add("tour-counter");
        title.getStyleClass().add("tour-title");
        title.setWrapText(true);
        text.getStyleClass().add("tour-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        progress.getStyleClass().add("tour-progress");
        progress.setMaxWidth(Double.MAX_VALUE);

        Hyperlink close = new Hyperlink("Close");
        close.setOnAction(e -> window.closeDialog());
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        back.getStyleClass().add("button-secondary");
        back.setOnAction(e -> go(index - 1));
        next.getStyleClass().add("button-primary");
        next.setOnAction(e -> go(index + 1));
        HBox buttons = new HBox(8, close, push, back, next);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("Using NdalamoPDF in Word");
        heading.getStyleClass().add("small-muted");
        getChildren().addAll(heading, frame, counter, title, text, progress, buttons);

        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT) go(index + 1);
            else if (e.getCode() == KeyCode.LEFT) go(index - 1);
            else return;
            e.consume();
        });
        go(0);
    }

    /** Opens the guide over the window, at its first step. */
    public static void show(MainWindow window) {
        WordAddInGuide guide = new WordAddInGuide(window);
        window.showDialog(guide);
        javafx.application.Platform.runLater(guide::requestFocus);
    }

    private void go(int to) {
        if (to >= STEPS.size()) {
            window.closeDialog();
            return;
        }
        if (to < 0) return;
        index = to;
        Step step = STEPS.get(index);
        var file = WordAddInGuide.class.getResource("/help/word/" + step.picture());
        picture.setImage(file == null ? null : new Image(file.toExternalForm()));
        picture.setAccessibleText(step.described());
        counter.setText("Step " + (index + 1) + " of " + STEPS.size());
        title.setText(step.title());
        text.setText(step.text());
        progress.setProgress((index + 1) / (double) STEPS.size());
        back.setDisable(index == 0);
        next.setText(index == STEPS.size() - 1 ? "Done" : "Next");
    }

    /** How many steps there are, and the pictures they use (for the tests). */
    static List<String> pictures() {
        return STEPS.stream().map(Step::picture).toList();
    }
}
