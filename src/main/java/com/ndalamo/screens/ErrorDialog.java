package com.ndalamo.screens;

import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * "Couldn't open Thesis_final.pdf"-style error dialog (mockup: Dialogs.dc.html).
 *
 * The plain-language message comes first and always says the original is safe
 * (SPEC §6.3). Technical details — the actual Java error — stay folded away
 * unless the user opens them, and can be copied for a bug report.
 */
public class ErrorDialog extends VBox {

    /**
     * @param fixLabel suggested next step, e.g. "Try again" or "Repair PDF"; null for none
     * @param onFix    what the suggested step does (the dialog closes first)
     */
    public ErrorDialog(MainWindow window, String title, String message, Throwable error,
                       String fixLabel, Runnable onFix) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(560);
        setMaxHeight(USE_PREF_SIZE);

        StackPane icon = new StackPane(Icons.cross(18, Theme.DANGER));
        icon.setMinSize(36, 36);
        icon.setMaxSize(36, 36);
        icon.setStyle("-fx-background-color: " + Theme.DANGER_SOFT + "; -fx-background-radius: 999;");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title");
        titleLabel.setWrapText(true);
        HBox heading = new HBox(12, icon, titleLabel);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("dialog-text");
        messageLabel.setWrapText(true);

        String details = stackTrace(error);
        TextArea detailsText = new TextArea(details);
        detailsText.setEditable(false);
        detailsText.setWrapText(true);
        detailsText.setPrefRowCount(6);
        detailsText.getStyleClass().add("mono");
        TitledPane technical = new TitledPane("Technical details", detailsText);
        technical.setExpanded(false);
        technical.getStyleClass().add("details-pane");

        Button copy = new Button("Copy details");
        copy.getStyleClass().add("button-secondary");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(details);
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
        });
        Button close = new Button("Close");
        close.getStyleClass().add("button-secondary");
        close.setCancelButton(true);
        close.setOnAction(e -> window.closeDialog());
        HBox buttons = new HBox(8, copy, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        if (fixLabel != null) {
            Button fix = new Button(fixLabel);
            fix.getStyleClass().addAll("button-primary", "button-dialog");
            fix.setDefaultButton(true);
            fix.setOnAction(e -> {
                window.closeDialog();
                onFix.run();
            });
            buttons.getChildren().add(fix);
        }

        getChildren().addAll(heading, messageLabel, technical, buttons);
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
