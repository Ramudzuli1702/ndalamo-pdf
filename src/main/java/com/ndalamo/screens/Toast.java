package com.ndalamo.screens;

import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A notification card in the bottom-right corner (mockup: docs/ui/screens/Notifications.dc.html).
 *
 * Success toasts hide themselves after 6 seconds; partial results and errors stay
 * until dismissed, because the user may need to act on them. A background-task
 * toast shows a progress bar that the flow keeps updating.
 *
 * Usage: {@code window.toast(Toast.success("3 files compressed", "11.2 MB → 4.9 MB", showInFolder));}
 */
public class Toast extends HBox {

    /** A text button on the toast, e.g. "Show in folder". */
    public record Action(String label, Runnable run) {}

    private enum Kind { SUCCESS, WARNING, ERROR, INFO }

    private final Label body = new Label();
    private ProgressBar bar;
    private Runnable onDismiss = () -> {};

    public static Toast success(String title, String body, Action... actions) {
        Toast toast = new Toast(Kind.SUCCESS, title, body, actions);
        toast.hideAfter(Duration.seconds(6));
        return toast;
    }

    public static Toast partial(String title, String body, Action... actions) {
        return new Toast(Kind.WARNING, title, body, actions);
    }

    public static Toast error(String title, String body, Action... actions) {
        return new Toast(Kind.ERROR, title, body, actions);
    }

    public static Toast info(String title, String body, Action... actions) {
        Toast toast = new Toast(Kind.INFO, title, body, actions);
        toast.hideAfter(Duration.seconds(6));
        return toast;
    }

    /** A running task the user left: shows a progress bar until the flow replaces it. */
    public static Toast background(String title, String body, Action... actions) {
        Toast toast = new Toast(Kind.INFO, title, body, actions);
        toast.bar = new ProgressBar(0);
        toast.bar.getStyleClass().add("small-bar");
        toast.bar.setMaxWidth(Double.MAX_VALUE);
        ((VBox) toast.getChildren().get(1)).getChildren().add(2, toast.bar);
        return toast;
    }

    private Toast(Kind kind, String title, String bodyText, Action... actions) {
        super(12);
        getStyleClass().add("toast");
        setAlignment(Pos.TOP_LEFT);
        setMaxWidth(440);
        setPrefWidth(440);

        StackPane icon = new StackPane(switch (kind) {
            case SUCCESS -> Icons.check(16, Theme.SUCCESS);
            case WARNING -> Icons.warning(16, Theme.WARNING);
            case ERROR -> Icons.cross(16, Theme.DANGER);
            case INFO -> Icons.info(16, Theme.LINK);
        });
        icon.setMinSize(32, 32);
        icon.setMaxSize(32, 32);
        icon.setStyle("-fx-background-radius: 999; -fx-background-color: " + switch (kind) {
            case SUCCESS -> Theme.SUCCESS_SOFT;
            case WARNING -> Theme.WARNING_SOFT;
            case ERROR -> Theme.DANGER_SOFT;
            case INFO -> Theme.ACCENT_SOFT;
        } + ";");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("toast-title");
        titleLabel.setWrapText(true);
        body.setText(bodyText);
        body.getStyleClass().add("toast-body");
        body.setWrapText(true);
        body.setManaged(!bodyText.isEmpty());
        body.setVisible(!bodyText.isEmpty());

        HBox buttons = new HBox(14);
        for (Action action : actions) {
            Button button = new Button(action.label());
            button.getStyleClass().add("text-button");
            button.setOnAction(e -> {
                action.run().run();
                dismiss();
            });
            buttons.getChildren().add(button);
        }

        VBox text = new VBox(4, titleLabel, body, buttons);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button close = new Button();
        close.setGraphic(Icons.close(14, Theme.TEXT_MUTED));
        close.getStyleClass().add("toast-close");
        close.setAccessibleText("Dismiss");
        close.setOnAction(e -> dismiss());
        close.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(icon, text, close);
    }

    /** Updates a background-task toast, e.g. "page 4 of 10". */
    public void setProgress(double fraction, String status) {
        if (bar != null) bar.setProgress(fraction);
        body.setText(status);
        body.setManaged(true);
        body.setVisible(true);
    }

    public void dismiss() {
        onDismiss.run();
    }

    void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    private void hideAfter(Duration delay) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(e -> dismiss());
        // Don't hide while the mouse is on it — the user may be about to click "Open".
        setOnMouseEntered(e -> pause.stop());
        setOnMouseExited(e -> pause.playFromStart());
        pause.play();
    }
}
