package com.ndalamo.screens;

import com.ndalamo.services.WordAddIn;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * The Word add-in card in Settings: what the add-in does, whether it is installed, and an
 * Install or Remove button. Checking and installing run off the JavaFX thread (they call
 * Windows' "reg" command and copy a file); the card updates when they finish.
 */
public final class WordAddInCard extends VBox {

    private final MainWindow window;
    private final Label status = new Label("Checking…");
    private final Button action = new Button();

    public WordAddInCard(MainWindow window) {
        super(10);
        this.window = window;
        Label about = new Label("Adds a “Delete Page” button to the Microsoft Word ribbon. Requires Word for Windows.");
        about.setWrapText(true);
        status.getStyleClass().add("small-muted");
        status.setWrapText(true);
        action.setVisible(false);
        action.managedProperty().bind(action.visibleProperty());
        HBox row = new HBox(10, status, action);
        row.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("In Word: Home tab › Ndalamo › Delete Page, or right-click a page. Ctrl+Z brings a deleted page back.");
        note.getStyleClass().add("small-muted");
        note.setWrapText(true);
        getChildren().addAll(about, row, note);
        refresh();
    }

    private void refresh() {
        CompletableFuture.supplyAsync(() -> {
            WordAddIn.Status now = WordAddIn.status();
            return new Object[] {now, now == WordAddIn.Status.UNAVAILABLE ? WordAddIn.unavailableReason() : null};
        }).thenAccept(result -> Platform.runLater(() -> show((WordAddIn.Status) result[0], (String) result[1])));
    }

    private void show(WordAddIn.Status now, String reason) {
        action.setDisable(false);
        action.setVisible(now != WordAddIn.Status.UNAVAILABLE);
        switch (now) {
            case UNAVAILABLE -> setStatus("Status: not available · " + reason, "small-muted");
            case NOT_INSTALLED -> {
                setStatus("Status: not installed", "small-muted");
                button("Install add-in", "button-primary", true);
            }
            case INSTALLED -> {
                setStatus("Status: installed · restart Word if it was open", "status-good");
                button("Remove add-in", "button-secondary", false);
            }
            case TURNED_OFF_IN_WORD -> {
                setStatus("Status: installed, but Word has turned it off · install again to turn it back on", "status-warning");
                button("Install again", "button-primary", true);
            }
        }
    }

    private void setStatus(String text, String style) {
        status.setText(text);
        status.getStyleClass().setAll("label", style);
    }

    private void button(String text, String style, boolean install) {
        action.setText(text);
        action.getStyleClass().setAll("button", style);
        action.setAccessibleText(text);
        action.setOnAction(e -> run(install));
    }

    private void run(boolean install) {
        action.setDisable(true);
        setStatus(install ? "Installing…" : "Removing…", "small-muted");
        CompletableFuture.runAsync(() -> {
            try {
                if (install) WordAddIn.install();
                else WordAddIn.remove();
                Platform.runLater(() -> window.toast(install
                        ? Toast.success("Word add-in installed", "Open (or restart) Word: Delete Page is on the Home tab.")
                        : Toast.success("Word add-in removed", "Restart Word if it's open to finish removing it.")));
            } catch (IOException ex) {
                String title = install ? "The add-in wasn't installed" : "The add-in wasn't removed";
                Platform.runLater(() -> window.toast(Toast.error(title, ex.getMessage())));
            }
            refresh();
        });
    }
}
