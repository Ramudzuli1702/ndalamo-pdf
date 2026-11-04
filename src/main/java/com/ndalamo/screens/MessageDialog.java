package com.ndalamo.screens;

import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A simple dialog with a warning icon, a message and a Close button — for things
 * the user should know about that aren't errors, like a result file that has
 * since been deleted.
 */
public class MessageDialog extends VBox {

    public MessageDialog(MainWindow window, String title, String message) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(520);
        setMaxHeight(USE_PREF_SIZE);

        StackPane icon = new StackPane(Icons.warning(18, Theme.WARNING));
        icon.setMinSize(36, 36);
        icon.setMaxSize(36, 36);
        icon.setStyle("-fx-background-color: " + Theme.WARNING_SOFT + "; -fx-background-radius: 999;");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title");
        titleLabel.setWrapText(true);
        HBox heading = new HBox(12, icon, titleLabel);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("dialog-text");
        messageLabel.setWrapText(true);

        Button close = new Button("Close");
        close.getStyleClass().addAll("button-primary", "button-dialog");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> window.closeDialog());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(heading, messageLabel, buttons);
    }

    /**
     * Runs the action (Open, Show in folder) only if the file still exists;
     * otherwise explains that it was moved or deleted.
     */
    public static void ifFileExists(MainWindow window, Path file, Runnable action) {
        if (Files.exists(file)) {
            action.run();
            return;
        }
        window.showDialog(new MessageDialog(window, "This file no longer exists",
                file.getFileName() + " was moved or deleted after it was saved, so it can't be opened. "
                        + "Your original files were not changed. Run the tool again to make a new copy."));
    }
}
