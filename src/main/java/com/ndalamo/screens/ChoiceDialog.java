package com.ndalamo.screens;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * A question with a few answers, like "Delete 2 pages?" or "Save changes to
 * Report.pdf?" (mockup: Dialogs.dc.html). The last button is the main one; it can
 * be shown in red for actions that remove things. An optional "Don't ask again"
 * box is passed to every answer.
 *
 * Usage:
 * {@code new ChoiceDialog(window, "Delete 2 pages?", "…", "Don't ask again")
 *        .button("Cancel", Style.PLAIN, dontAsk -> {}).button("Delete pages", Style.DANGER, dontAsk -> …).show();}
 */
public class ChoiceDialog extends VBox {

    public enum Style { PLAIN, PRIMARY, DANGER }

    private final MainWindow window;
    private final HBox buttons = new HBox(8);
    private final CheckBox checkBox;

    /** @param checkBoxText e.g. "Don't ask again"; null for no check box */
    public ChoiceDialog(MainWindow window, String title, String message, String checkBoxText) {
        super(14);
        this.window = window;
        getStyleClass().add("dialog-card");
        setMaxWidth(540);
        setMaxHeight(USE_PREF_SIZE);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title");
        titleLabel.setWrapText(true);
        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("dialog-text");
        messageLabel.setWrapText(true);
        getChildren().addAll(titleLabel, messageLabel);

        checkBox = checkBoxText == null ? null : new CheckBox(checkBoxText);
        if (checkBox != null) getChildren().add(checkBox);

        buttons.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(buttons);
    }

    /** Adds an answer. The dialog closes before the answer runs; it receives the check box's state. */
    public ChoiceDialog button(String label, Style style, Consumer<Boolean> onChosen) {
        Button button = new Button(label);
        button.setMnemonicParsing(false);     // keep "_" in file names visible
        button.setMinWidth(Region.USE_PREF_SIZE);   // never cut a label short to "Ca…"
        switch (style) {
            case PRIMARY -> button.getStyleClass().addAll("button-primary", "button-dialog");
            case DANGER -> button.getStyleClass().addAll("button-danger", "button-dialog");
            default -> button.getStyleClass().add("button-secondary");
        }
        if (style != Style.PLAIN) button.setDefaultButton(true);
        if (label.equals("Cancel")) button.setCancelButton(true);
        button.setOnAction(e -> {
            window.closeDialog();
            onChosen.accept(checkBox != null && checkBox.isSelected());
        });
        buttons.getChildren().add(button);
        return this;
    }

    /** For dialogs with many or long answers; the usual width is 540. */
    public ChoiceDialog width(double maxWidth) {
        setMaxWidth(maxWidth);
        return this;
    }

    public void show() {
        window.showDialog(this);
    }
}
