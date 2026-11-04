package com.ndalamo.screens;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Step 2: the tool is working (mockup: Progress.dc.html). Shows a progress bar,
 * what the tool is doing, a rough time left, and Cancel / Run in background.
 * There is no Home button on purpose — "Run in background" is the way out.
 */
public class ProgressScreen extends BorderPane {

    private final ProgressBar bar = new ProgressBar(0);
    private final Label status = new Label("Starting…");
    private final Button cancel = new Button("Cancel");
    private final long startedAt = System.nanoTime();

    public ProgressScreen(String toolName, String heading, Runnable onCancel, Runnable onBackground) {
        Label title = new Label(toolName);
        title.getStyleClass().add("screen-title");
        Label step = new Label("Step 2 of 2 · Working");
        step.getStyleClass().add("step-label");
        HBox header = new HBox(12, LogoMark.color(28), title, step);
        header.getStyleClass().add("screen-header");
        header.setAlignment(Pos.CENTER_LEFT);
        setTop(header);

        Label headingLabel = new Label(heading);
        headingLabel.getStyleClass().add("page-title");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("big-bar");
        status.getStyleClass().add("small-muted");
        status.setWrapText(true);

        cancel.getStyleClass().addAll("button-secondary", "button-tall");
        cancel.setOnAction(e -> onCancel.run());
        Button background = new Button("Run in background");
        background.getStyleClass().addAll("button-secondary", "button-tall");
        background.setOnAction(e -> onBackground.run());

        VBox content = new VBox(24, new VBox(10, headingLabel, bar, status), new HBox(10, cancel, background));
        content.setMaxWidth(760);
        content.setPadding(new Insets(48, 24, 48, 24));
        BorderPane.setAlignment(content, Pos.TOP_CENTER);
        setCenter(content);
    }

    /** Called about ten times a second with the tool's progress. */
    public void update(double fraction, String toolStatus) {
        bar.setProgress(fraction);
        String timeLeft = timeLeft(fraction);
        status.setText(toolStatus + (timeLeft.isEmpty() ? "" : " · " + timeLeft) + " · you can keep using the app");
    }

    public void showCancelling() {
        cancel.setText("Cancelling…");
        cancel.setDisable(true);
    }

    /** "about 20 seconds left", worked out from how long the first part took. Empty until there's enough to go on. */
    private String timeLeft(double fraction) {
        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        if (fraction < 0.05 || fraction >= 1 || elapsed < 1.5) return "";
        long seconds = Math.round(elapsed * (1 - fraction) / fraction);
        if (seconds < 5) return "almost done";
        if (seconds < 90) return "about " + (Math.round(seconds / 5.0) * 5) + " seconds left";
        return "about " + Math.round(seconds / 60.0) + " minutes left";
    }
}
