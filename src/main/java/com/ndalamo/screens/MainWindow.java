package com.ndalamo.screens;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Everything inside the app window, in three layers stacked on top of each other:
 * 1. the current screen (Home, a tool screen, ...);
 * 2. toasts in the bottom-right corner;
 * 3. a dialog over a dimmed background, when one is open.
 *
 * Screens and flows use this to switch screens, show toasts and open dialogs.
 */
public class MainWindow extends StackPane {

    private static final int MAX_TOASTS = 3;   // SPEC §6.2 screen 11

    private final StackPane screenLayer = new StackPane();
    private final VBox toastLayer = new VBox(10);
    private final StackPane dialogLayer = new StackPane();

    public MainWindow() {
        toastLayer.setAlignment(Pos.BOTTOM_RIGHT);
        toastLayer.setPadding(new Insets(24));
        toastLayer.setPickOnBounds(false);       // clicks between toasts reach the screen underneath

        dialogLayer.getStyleClass().add("dim-backdrop");
        dialogLayer.setVisible(false);
        dialogLayer.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) closeDialog();
        });

        getChildren().addAll(screenLayer, toastLayer, dialogLayer);
    }

    public void show(Node screen) {
        screenLayer.getChildren().setAll(screen);
    }

    /** The screen currently showing. */
    public Node current() {
        return screenLayer.getChildren().isEmpty() ? null : screenLayer.getChildren().get(0);
    }

    /** Shows a toast at the top of the stack; the oldest goes once there are more than three. */
    public void toast(Toast toast) {
        toast.setOnDismiss(() -> toastLayer.getChildren().remove(toast));
        toastLayer.getChildren().add(0, toast);
        if (toastLayer.getChildren().size() > MAX_TOASTS) {
            toastLayer.getChildren().remove(MAX_TOASTS, toastLayer.getChildren().size());
        }
    }

    public void showDialog(Node dialog) {
        dialogLayer.getChildren().setAll(dialog);
        dialogLayer.setVisible(true);
        dialogLayer.requestFocus();               // so Esc closes it straight away
    }

    public void closeDialog() {
        dialogLayer.getChildren().clear();
        dialogLayer.setVisible(false);
    }

    public boolean hasDialog() {
        return dialogLayer.isVisible();
    }

    /** Closes the newest toast (Esc with no dialog open, SPEC §6.3). False if there are none. */
    public boolean dismissNewestToast() {
        if (toastLayer.getChildren().isEmpty()) return false;
        if (toastLayer.getChildren().get(0) instanceof Toast toast) toast.dismiss();
        else toastLayer.getChildren().remove(0);
        return true;
    }
}
