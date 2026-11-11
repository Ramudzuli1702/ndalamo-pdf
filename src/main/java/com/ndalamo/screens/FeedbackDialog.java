package com.ndalamo.screens;

import com.ndalamo.services.FeedbackService;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * "Tell us what you think": five stars and a box for a few words. Offered now and then after a
 * tool has finished (with "Maybe later" and "Never show this again"), and any time from
 * Settings › Help. Submit hands the stars and text to the caller, which queues and sends them
 * (FeedbackService); this class only collects them.
 */
public final class FeedbackDialog extends VBox {

    private static final String STAR = "M12 2.5l2.94 5.96 6.56.95-4.75 4.63 1.12 6.54L12 17.5l-5.87 3.08 1.12-6.54L2.5 9.41l6.56-.95z";
    private static final String[] MEANING = {"", "Poor", "Not great", "Okay", "Good", "Excellent"};

    private final List<SVGPath> stars = new ArrayList<>();
    private final Label meaning = new Label(" ");
    private final Button submit = new Button("Submit");
    private int rating;

    /**
     * @param offered true when Ndalamo offered it (adds "Never show this again", and the second
     *                button says "Maybe later"); false when opened from Settings
     * @param onSubmit gets the stars (1-5) and the text
     * @param onLater "Maybe later" or Cancel
     * @param onNever "Never show this again"
     */
    public FeedbackDialog(MainWindow window, boolean offered, BiConsumer<Integer, String> onSubmit,
                          Runnable onLater, Runnable onNever) {
        super(14);
        getStyleClass().addAll("dialog-card", "feedback-dialog");
        setMaxWidth(520);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label("Tell us what you think");
        title.getStyleClass().add("dialog-title");
        Label ask = new Label("How do you like NdalamoPDF so far? Your rating and a few words help make it better.");
        ask.getStyleClass().add("dialog-text");
        ask.setWrapText(true);

        HBox starRow = new HBox(6);
        starRow.setAlignment(Pos.CENTER_LEFT);
        starRow.getStyleClass().add("feedback-stars");
        starRow.setFocusTraversable(true);
        starRow.setAccessibleRole(AccessibleRole.SLIDER);
        for (int i = 1; i <= 5; i++) {
            int value = i;
            SVGPath star = new SVGPath();
            star.setContent(STAR);
            star.setScaleX(1.5);
            star.setScaleY(1.5);
            star.getStyleClass().add("feedback-star");
            Region hit = new Region();                     // a comfortable click area around each star
            hit.setMinSize(40, 40);
            javafx.scene.layout.StackPane cell = new javafx.scene.layout.StackPane(hit, star);
            cell.setCursor(javafx.scene.Cursor.HAND);
            cell.setOnMouseEntered(e -> paint(value));
            cell.setOnMouseExited(e -> paint(rating));
            cell.setOnMouseClicked(e -> choose(value));
            stars.add(star);
            starRow.getChildren().add(cell);
        }
        meaning.getStyleClass().add("small-muted");
        starRow.getChildren().add(meaning);
        starRow.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.UP) choose(Math.min(5, rating + 1));
            else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.DOWN) choose(Math.max(1, rating - 1));
            else if (e.getCode().isDigitKey() && e.getText().matches("[1-5]")) choose(Integer.parseInt(e.getText()));
            else return;
            e.consume();
        });
        starRow.setAccessibleText("Rating: choose 1 to 5 stars with the arrow keys");

        TextArea words = new TextArea();
        words.setPromptText("What do you like? What should be better? (optional)");
        words.setWrapText(true);
        words.setPrefRowCount(4);
        words.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= FeedbackService.MAX_LENGTH ? change : null));

        Label privacy = new Label("Sends only your stars and words, with NdalamoPDF's version, to the NdalamoPDF team. "
                + "No files or details about your PC. If you're offline, it's sent once you're connected.");
        privacy.getStyleClass().add("small-muted");
        privacy.setWrapText(true);

        submit.getStyleClass().addAll("button-primary", "button-dialog");
        submit.setDisable(true);
        submit.setDefaultButton(true);
        submit.setOnAction(e -> {
            window.closeDialog();
            onSubmit.accept(rating, words.getText());
        });
        Button later = new Button(offered ? "Maybe later" : "Cancel");
        later.getStyleClass().addAll("button-secondary", "button-dialog");
        later.setCancelButton(true);
        later.setOnAction(e -> {
            window.closeDialog();
            onLater.run();
        });
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        if (offered) {
            Hyperlink never = new Hyperlink("Never show this again");
            never.setOnAction(e -> {
                window.closeDialog();
                onNever.run();
            });
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            buttons.getChildren().addAll(never, push);
        }
        buttons.getChildren().addAll(later, submit);

        getChildren().addAll(title, ask, starRow, words, privacy, buttons);
        paint(0);
        javafx.application.Platform.runLater(starRow::requestFocus);
    }

    private void choose(int value) {
        rating = value;
        paint(value);
        submit.setDisable(false);
    }

    /** Fills the first {@code upTo} stars; shows what that many stars means. */
    private void paint(int upTo) {
        for (int i = 0; i < stars.size(); i++) {
            stars.get(i).getStyleClass().removeAll("on");
            if (i < upTo) stars.get(i).getStyleClass().add("on");
        }
        meaning.setText(upTo == 0 ? " " : MEANING[upTo]);
    }

    /** The stars chosen, 0 for none (for the tests). */
    int rating() {
        return rating;
    }
}
