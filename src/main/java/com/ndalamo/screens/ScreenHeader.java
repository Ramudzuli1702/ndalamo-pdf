package com.ndalamo.screens;

import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The header on every screen except Home (SPEC §6.0): the same logo and name as
 * Home's header, a "Home" back button, then the screen's title. Screens can add their own
 * buttons to the right with {@code getChildren().add(...)}.
 */
public class ScreenHeader extends HBox {

    public ScreenHeader(String title, Runnable onHome) {
        getStyleClass().add("screen-header");
        setAlignment(Pos.CENTER_LEFT);

        Button home = new Button("Home", Icons.back(16, Theme.TEXT));
        home.getStyleClass().add("button-secondary");
        home.setGraphicTextGap(6);
        home.setOnAction(e -> onHome.run());

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("screen-title");

        getChildren().addAll(Header.brand(), home, titleLabel);
    }
}
