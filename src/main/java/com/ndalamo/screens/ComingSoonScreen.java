package com.ndalamo.screens;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Stand-in for screens that aren't built yet (Pipelines, Settings, file-drop
 * suggestions), so navigation can be tried out from chunk 2. Each one is
 * replaced by the real screen in its own chunk.
 */
public class ComingSoonScreen extends BorderPane {

    /**
     * @param details extra lines to show, e.g. the names of dropped files; may be empty
     */
    public ComingSoonScreen(String title, String message, List<String> details, Runnable onHome) {
        setTop(new ScreenHeader(title, onHome));

        Label heading = new Label(title + " isn't built yet");
        heading.getStyleClass().addAll("semibold");
        heading.setStyle("-fx-font-size: 18px;");

        Label text = new Label(message);
        text.getStyleClass().add("muted");
        text.setWrapText(true);

        VBox card = new VBox(heading, text);
        card.getStyleClass().add("card");
        card.setMaxWidth(640);
        for (String line : details) {
            Label detail = new Label("• " + line);
            detail.setWrapText(true);
            card.getChildren().add(detail);
        }

        VBox centre = new VBox(card);
        centre.setAlignment(Pos.TOP_CENTER);
        centre.setPadding(new Insets(48, 24, 24, 24));
        setCenter(centre);
    }
}
