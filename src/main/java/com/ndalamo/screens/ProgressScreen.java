package com.ndalamo.screens;

import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 2: the tool is working (mockup: Progress.dc.html). An overall bar, a line
 * like "1 of 3 done · about 20 seconds left", and — for tools that work file by
 * file — a card per file with its own status and bar. Cancel / Run in background
 * underneath. There is no Home button on purpose: "Run in background" is the way out.
 */
public class ProgressScreen extends BorderPane {

    private final ProgressBar bar = new ProgressBar(0);
    private final Label status = new Label("Starting…");
    private final Button cancel = new Button("Cancel");
    private final long startedAt = System.nanoTime();

    private final List<Label> fileStatus = new ArrayList<>();
    private final List<ProgressBar> fileBars = new ArrayList<>();
    private final boolean[] fileDone;

    /**
     * @param files the inputs, for one card each; pass an empty list for tools that make one
     *              output from everything (Merge), where per-file cards would mean nothing
     */
    public ProgressScreen(String toolName, String heading, List<Path> files, Runnable onCancel, Runnable onBackground) {
        Label title = new Label(toolName);
        title.getStyleClass().add("screen-title");
        Label step = new Label("Step 2 of 2 · Working");
        step.getStyleClass().add("step-label");
        HBox header = new HBox(12, Header.brand(), title, step);
        header.getStyleClass().add("screen-header");
        header.setAlignment(Pos.CENTER_LEFT);
        setTop(header);

        Label headingLabel = new Label(heading);
        headingLabel.getStyleClass().add("page-title");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("big-bar");
        status.getStyleClass().add("small-muted");
        status.setWrapText(true);

        VBox cards = new VBox(12);
        fileDone = new boolean[files.size()];
        for (Path file : files) cards.getChildren().add(fileCard(file));

        cancel.getStyleClass().addAll("button-secondary", "button-tall");
        cancel.setOnAction(e -> onCancel.run());
        Button background = new Button("Run in background");
        background.getStyleClass().addAll("button-secondary", "button-tall");
        background.setOnAction(e -> onBackground.run());

        VBox content = new VBox(24, new VBox(10, headingLabel, bar, status), cards, new HBox(10, cancel, background));
        content.setMaxWidth(760);
        content.setPadding(new Insets(48, 24, 48, 24));
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
    }

    private VBox fileCard(Path file) {
        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("file-name");
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label state = new Label("Waiting");
        state.getStyleClass().add("file-status");
        state.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        ProgressBar fileBar = new ProgressBar(0);
        fileBar.setMaxWidth(Double.MAX_VALUE);
        fileBar.getStyleClass().add("small-bar");

        fileStatus.add(state);
        fileBars.add(fileBar);
        VBox card = new VBox(8, new HBox(8, name, state), fileBar);
        card.getStyleClass().add("progress-card");
        return card;
    }

    /** The whole job, about ten times a second. */
    public void update(double fraction, String toolStatus) {
        bar.setProgress(fraction);
        List<String> parts = new ArrayList<>();
        if (fileDone.length > 1) parts.add(doneCount() + " of " + fileDone.length + " done");
        else parts.add(toolStatus);
        String timeLeft = timeLeft(fraction);
        if (!timeLeft.isEmpty()) parts.add(timeLeft);
        parts.add("you can keep using the app");
        status.setText(String.join(" · ", parts));
    }

    /** One file's card: e.g. "Compressing images · 62%", then "Done · 4.1 → 1.8 MB" with a green bar. */
    public void updateFile(int index, double fraction, String fileStatusText) {
        if (index < 0 || index >= fileDone.length) return;
        boolean done = fraction >= 1;
        fileDone[index] = done;
        fileBars.get(index).setProgress(fraction);
        Label state = fileStatus.get(index);
        state.setText(fileStatusText);
        state.setStyle("-fx-text-fill: " + (done ? Theme.SUCCESS : Theme.TEXT) + ";");
        if (done) fileBars.get(index).getStyleClass().add("bar-done");
    }

    public void showCancelling() {
        cancel.setText("Cancelling…");
        cancel.setDisable(true);
    }

    private int doneCount() {
        int n = 0;
        for (boolean done : fileDone) if (done) n++;
        return n;
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
