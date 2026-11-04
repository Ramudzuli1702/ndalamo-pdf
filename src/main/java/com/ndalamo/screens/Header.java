package com.ndalamo.screens;

import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * The bar across the top of the home screen: the logo on a blue tile and the
 * name "Ndalamo" (mockup: docs/ui/screens/Main.dc.html).
 * Search, Pipelines and Settings buttons are added in chunk 2.
 */
public class Header extends HBox {

    public Header() {
        super(16);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(14, 24, 14, 24));
        setStyle("-fx-background-color: " + Theme.SURFACE + ";"
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-width: 0 0 1 0;");

        StackPane tile = new StackPane(LogoMark.white(22));
        tile.setMinSize(32, 32);
        tile.setMaxSize(32, 32);
        tile.setStyle("-fx-background-color: " + Theme.ACCENT + "; -fx-background-radius: " + Theme.RADIUS_SM + ";");

        Label name = new Label("Ndalamo");
        name.setFont(Font.font(Theme.FONT, FontWeight.BOLD, 17));
        name.setStyle("-fx-text-fill: " + Theme.TEXT + ";");

        HBox brand = new HBox(10, tile, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(brand);
    }
}
