package com.ndalamo.screens;

import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The home screen (mockup: docs/ui/screens/Main.dc.html): header with search,
 * a sidebar with recent files and tools, the drop zone, and every tool as a
 * card grouped by category. Typing in the search box filters the cards.
 */
public class HomeScreen extends BorderPane {

    private static final double CARD_MIN_WIDTH = 190;  // mockup: minmax(190px, 1fr)
    private static final double GAP = 12;

    private final Header header;
    private final VBox sections = new VBox(28);
    private final Consumer<PdfTool> onOpenTool;

    public HomeScreen(Runnable onPipelines, Runnable onSettings, Runnable onChooseFiles,
                      Consumer<PdfTool> onOpenTool) {
        this.onOpenTool = onOpenTool;
        header = new Header(onPipelines, onSettings);
        header.getSearchField().textProperty().addListener((obs, old, text) -> showTools(text));

        VBox main = new VBox(28, dropZone(onChooseFiles), sections);
        main.setPadding(new Insets(28, 32, 28, 32));

        ScrollPane scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        setTop(header);
        setCenter(new HBox(sidebar(), scroll));
        showTools("");
    }

    /** Puts the cursor in the search box (Ctrl+K). */
    public void focusSearch() {
        header.getSearchField().requestFocus();
        header.getSearchField().selectAll();
    }

    // ── Sidebar ──────────────────────────────────────────────────────────

    private VBox sidebar() {
        // Recent files and tools are filled in once tools run (chunk 3 onwards).
        VBox recentFiles = sidebarSection("RECENT FILES", "Files you work on will appear here.");
        VBox recentTools = sidebarSection("RECENT TOOLS", "Tools you use will appear here.");

        Label offline = new Label("Offline · files never leave this PC", Icons.lock(16, Theme.SUCCESS));
        offline.getStyleClass().add("offline-badge");
        offline.setGraphicTextGap(8);
        offline.setWrapText(true);

        Region push = new Region();
        VBox.setVgrow(push, Priority.ALWAYS);  // keeps the offline badge at the bottom

        VBox sidebar = new VBox(28, recentFiles, recentTools, push, offline);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(260);
        sidebar.setMinWidth(220);
        sidebar.setMaxWidth(260);
        return sidebar;
    }

    private VBox sidebarSection(String title, String emptyText) {
        Label label = new Label(title);
        label.getStyleClass().add("sidebar-label");
        Label empty = new Label(emptyText);
        empty.getStyleClass().add("empty-note");
        empty.setWrapText(true);
        return new VBox(10, label, empty);
    }

    // ── Drop zone ────────────────────────────────────────────────────────

    private HBox dropZone(Runnable onChooseFiles) {
        StackPane icon = new StackPane(Icons.upload(24, Theme.ACCENT));
        icon.getStyleClass().add("drop-zone-icon");
        icon.setMinSize(52, 52);
        icon.setMaxSize(52, 52);

        Label title = new Label("Drop files anywhere to start");
        title.getStyleClass().add("drop-title");
        Label subtitle = new Label("PDF, Word, Excel, PowerPoint or images — we’ll suggest the right tools.");
        subtitle.getStyleClass().add("muted");
        subtitle.setWrapText(true);
        VBox text = new VBox(4, title, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button choose = new Button("Choose files");
        choose.getStyleClass().add("button-primary");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(e -> onChooseFiles.run());

        HBox zone = new HBox(icon, text, choose);
        zone.getStyleClass().add("drop-zone");
        zone.setAlignment(Pos.CENTER_LEFT);
        return zone;
    }

    // ── Tool cards ───────────────────────────────────────────────────────

    /** Rebuilds the tool sections, showing only tools that match the search text. */
    private void showTools(String searchText) {
        List<PdfTool> matches = Tools.search(searchText);
        sections.getChildren().clear();

        for (String category : Tools.CATEGORIES) {
            List<PdfTool> inCategory = matches.stream().filter(t -> t.category().equals(category)).toList();
            if (inCategory.isEmpty()) continue;   // hide sections with no matches

            Label title = new Label(category);
            title.getStyleClass().add("section-title");
            FlowPane grid = cardGrid();
            for (PdfTool tool : inCategory) {
                grid.getChildren().add(toolCard(tool));
            }
            sections.getChildren().add(new VBox(12, title, grid));
        }

        if (sections.getChildren().isEmpty()) {
            Label none = new Label("No tools match “" + searchText.strip() + "”. Try another word, like “merge” or “image”.");
            none.getStyleClass().add("muted");
            sections.getChildren().add(none);
        }
    }

    /**
     * A wrapping grid whose cards stretch to fill each row, like the mockup's CSS
     * grid. FlowPane can't stretch on its own, so card widths are recalculated
     * whenever the grid's width changes.
     */
    private FlowPane cardGrid() {
        FlowPane grid = new FlowPane(GAP, GAP);
        grid.widthProperty().addListener((obs, old, width) -> {
            double w = width.doubleValue();
            int columns = Math.max(1, (int) ((w + GAP) / (CARD_MIN_WIDTH + GAP)));
            double cardWidth = Math.floor((w - (columns - 1) * GAP) / columns);
            grid.getChildren().forEach(card -> ((Region) card).setPrefWidth(cardWidth));
        });
        return grid;
    }

    /**
     * One tool card. It's a plain HBox that acts like a button (click, Enter or Space),
     * because a real Button sizes itself badly around wrapping text.
     */
    private HBox toolCard(PdfTool tool) {
        StackPane icon = new StackPane(Icons.document(16, Theme.categoryInk(tool.category())));
        icon.setMinSize(34, 34);
        icon.setMaxSize(34, 34);
        icon.setStyle("-fx-background-color: " + Theme.categoryTint(tool.category()) + ";"
                + "-fx-background-radius: " + Theme.RADIUS_SM + ";");

        Label name = new Label(tool.name());
        name.getStyleClass().add("tool-name");
        Label description = new Label(tool.description());
        description.getStyleClass().add("tool-desc");
        description.setWrapText(true);
        VBox text = new VBox(2, name, description);
        text.setMinWidth(0);                       // lets the description wrap instead of widening the card
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox card = new HBox(12, icon, text);
        card.getStyleClass().add("tool-card");
        card.setAccessibleRole(AccessibleRole.BUTTON);
        card.setAccessibleText(tool.name() + ". " + tool.description());

        if (!tool.isReady()) {
            // Not disabled on purpose: JavaFX would fade the whole card. The tag says it all.
            Label soon = new Label("Coming soon");
            soon.getStyleClass().add("coming-soon");
            VBox.setMargin(soon, new Insets(4, 0, 0, 0));
            text.getChildren().add(soon);
            card.getStyleClass().add("not-ready");
            card.setAccessibleText(tool.name() + ". " + tool.description() + ". Coming soon");
            return card;
        }

        card.setFocusTraversable(true);
        card.setOnMouseClicked(e -> onOpenTool.accept(tool));
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) onOpenTool.accept(tool);
        });
        return card;
    }
}
