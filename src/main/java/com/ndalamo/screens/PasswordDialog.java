package com.ndalamo.screens;

import com.ndalamo.services.FileInspector;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * "This PDF is password-protected" (mockup: Dialogs.dc.html). Asks for one file's
 * password and checks it before closing; a wrong password shows "That password
 * didn't work. Try again." and the user can try again or skip the file.
 *
 * The password is handed straight to the running tool and never saved or logged.
 */
public class PasswordDialog extends VBox {

    /**
     * @param restrictedOnly the file opens without a password but has print/copy limits,
     *                       so its full ("owner") password is needed
     */
    public PasswordDialog(MainWindow window, Path file, boolean restrictedOnly, FileInspector inspector,
                          Consumer<String> onUnlocked, Runnable onSkip) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(520);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label(restrictedOnly ? "This PDF has print and copy limits" : "This PDF is password-protected");
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);

        Label text = new Label((restrictedOnly
                ? "Enter the full password for " + file.getFileName() + " to remove its limits."
                : "Enter the password for " + file.getFileName() + " to continue.")
                + " It’s only used for this task and never stored.");
        text.getStyleClass().add("dialog-text");
        text.setWrapText(true);

        Label fieldLabel = new Label("Password");
        fieldLabel.getStyleClass().add("small-heading");
        PasswordField password = new PasswordField();
        password.setAccessibleText("Password for " + file.getFileName());
        Label error = new Label("That password didn’t work. Try again.");
        error.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.DANGER + ";");
        error.setVisible(false);
        error.setManaged(false);

        Button skip = new Button("Skip this file");
        skip.getStyleClass().add("button-secondary");
        skip.setCancelButton(true);
        skip.setOnAction(e -> {
            window.closeDialog();
            onSkip.run();
        });

        Button unlock = new Button("Unlock");
        unlock.getStyleClass().addAll("button-primary", "button-dialog");
        unlock.setDefaultButton(true);
        unlock.setOnAction(e -> {
            String typed = password.getText();
            unlock.setDisable(true);
            unlock.setText("Checking…");
            // Opening a big PDF to test the password can take a moment; don't freeze the window.
            CompletableFuture.supplyAsync(() -> inspector.passwordWorks(file, typed))
                    .thenAccept(works -> Platform.runLater(() -> {
                        if (works) {
                            window.closeDialog();
                            onUnlocked.accept(typed);
                            return;
                        }
                        error.setVisible(true);
                        error.setManaged(true);
                        password.getStyleClass().add("field-error");
                        password.selectAll();
                        password.requestFocus();
                        unlock.setDisable(false);
                        unlock.setText("Unlock");
                    }));
        });
        password.textProperty().addListener((obs, old, now) -> {
            error.setVisible(false);
            error.setManaged(false);
            password.getStyleClass().remove("field-error");
        });

        HBox buttons = new HBox(8, skip, unlock);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(title, text, fieldLabel, password, error, buttons);
        Platform.runLater(password::requestFocus);
    }
}
