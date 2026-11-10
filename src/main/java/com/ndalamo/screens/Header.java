package com.ndalamo.screens;

import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The bar across the top of the home screen (mockup: docs/ui/screens/Main.dc.html):
 * logo + "Ndalamo", the tool search box, and the Pipelines and Settings buttons.
 * Other screens use {@link ScreenHeader} instead.
 */
public class Header extends HBox {

    /** Sidebar (260) + content padding (32) − header padding (24) − spacing (16). See HomeScreen. */
    private static final double BRAND_WIDTH = 252;

    private final TextField search = new TextField();
    private HBox searchBox;
    private Button pipelinesButton, settingsButton;

    public Header(Runnable onPipelines, Runnable onSettings) {
        getStyleClass().add("app-header");
        setAlignment(Pos.CENTER_LEFT);

        HBox brand = brand();
        // Wide enough that the search box starts where the tool cards start below it
        brand.setMinWidth(BRAND_WIDTH);
        brand.setPrefWidth(BRAND_WIDTH);

        // Search box: grows with the window, up to 560 px like the mockup
        search.setPromptText("Search tools — try “compress” or “to Word”");
        search.getStyleClass().add("search-field");
        search.setAccessibleText("Search tools");
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) search.clear();
        });
        HBox.setHgrow(search, Priority.ALWAYS);
        Label keyHint = new Label("Ctrl K");
        keyHint.getStyleClass().add("key-hint");
        searchBox = new HBox(Icons.search(16, Theme.TEXT_MUTED), search, keyHint);
        searchBox.getStyleClass().add("search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setMaxWidth(560);
        searchBox.setOnMouseClicked(e -> search.requestFocus());
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        // Buttons on the right
        Button pipelines = new Button("Pipelines");
        pipelines.getStyleClass().add("button-secondary");
        pipelines.setOnAction(e -> onPipelines.run());

        Button settings = new Button();
        settings.setGraphic(Icons.settings(18, Theme.TEXT));
        settings.getStyleClass().addAll("button-secondary", "icon-button");
        settings.setAccessibleText("Settings");
        settings.setTooltip(new javafx.scene.control.Tooltip("Settings"));
        settings.setOnAction(e -> onSettings.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);

        getChildren().addAll(brand, searchBox, spacer, new HBox(8, pipelines, settings));
        pipelinesButton = pipelines;
        settingsButton = settings;
    }

    /**
     * The logo on a blue tile with "Ndalamo": the same at the top left of every screen, so the
     * brand doesn't change as you move between Home, tools, the workspace and Settings.
     */
    public static HBox brand() {
        StackPane tile = new StackPane(LogoMark.white(22));
        tile.setMinSize(32, 32);
        tile.setMaxSize(32, 32);
        tile.setStyle("-fx-background-color: " + Theme.ACCENT + "; -fx-background-radius: " + Theme.RADIUS_SM + ";");
        Label name = new Label("Ndalamo");
        name.getStyleClass().add("brand-name");
        HBox brand = new HBox(10, tile, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return brand;
    }

    /** For the welcome tour, which points at them. */
    public HBox searchBox() { return searchBox; }
    public Button pipelinesButton() { return pipelinesButton; }
    public Button settingsButton() { return settingsButton; }

    /** The search box, so the home screen can filter tools as the user types. */
    public TextField getSearchField() {
        return search;
    }
}
