package com.ndalamo.screens;

import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
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
    private final VBox pinned = new VBox(10);
    private final VBox recentFiles = new VBox(10);
    private final VBox recentTools = new VBox(10);
    private final java.util.Map<String, Node> cards = new java.util.HashMap<>();
    private ScrollPane scroll;
    private Node dropZone, sidebar;

    public HomeScreen(Runnable onPipelines, Runnable onSettings, Runnable onChooseFiles,
                      Consumer<PdfTool> onOpenTool) {
        this.onOpenTool = onOpenTool;
        header = new Header(onPipelines, onSettings);
        header.getSearchField().textProperty().addListener((obs, old, text) -> showTools(text));

        pinned.setVisible(false);
        pinned.setManaged(false);
        dropZone = dropZone(onChooseFiles);
        VBox main = new VBox(28, dropZone, pinned, sections);
        main.setPadding(new Insets(28, 32, 28, 32));

        scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        setTop(header);
        sidebar = sidebar();
        setCenter(new HBox(sidebar, scroll));
        showTools("");
    }

    /** The pinned pipelines row above the tool groups (SPEC §6.2); clicking one runs it. Hidden when none are pinned. */
    public void showPinned(List<com.ndalamo.models.Pipeline> pipelines, Consumer<com.ndalamo.models.Pipeline> onRun) {
        pinned.getChildren().clear();
        List<com.ndalamo.models.Pipeline> shown = pipelines.stream().filter(com.ndalamo.models.Pipeline::isPinned).toList();
        pinned.setVisible(!shown.isEmpty());
        pinned.setManaged(!shown.isEmpty());
        if (shown.isEmpty()) return;
        Label title = new Label("Pinned pipelines");
        title.getStyleClass().add("section-title");
        javafx.scene.layout.FlowPane cards = new javafx.scene.layout.FlowPane(12, 12);
        for (com.ndalamo.models.Pipeline pipeline : shown) {
            Label name = new Label(pipeline.getName());
            name.getStyleClass().add("tool-name");
            Label steps = new Label(String.join(" \u2192 ", pipeline.toolIds().stream()
                    .map(id -> com.ndalamo.tools.Tools.find(id).map(PdfTool::name).orElse(id).replace(" PDF", "")).toList()));
            steps.getStyleClass().add("tool-desc");
            steps.setWrapText(true);
            VBox card = new VBox(4, name, steps);
            card.getStyleClass().addAll("tool-card", "pinned-pipeline");
            card.setPrefWidth(250);
            card.setFocusTraversable(true);
            card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            card.setAccessibleText("Run pipeline " + pipeline.getName());
            card.setOnMouseClicked(e -> onRun.accept(pipeline));
            card.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    onRun.accept(pipeline);
                    e.consume();
                }
            });
            cards.getChildren().add(card);
        }
        pinned.getChildren().addAll(title, cards);
    }

    // ── For the welcome tour ─────────────────────────────────────────────

    public Header header() { return header; }
    public Node dropZoneNode() { return dropZone; }
    public Node sidebarNode() { return sidebar; }

    /** A tool's card, or null if it isn't showing (a search hides it). */
    public Node card(PdfTool tool) { return cards.get(tool.id()); }

    /** Clears the search so every tool card shows. */
    public void showAllTools() {
        header.getSearchField().clear();
        showTools("");
    }

    /** Scrolls the tool list so the node is in view (with some room above it), and calls back when done. */
    public void scrollIntoView(Node node, Runnable then) {
        javafx.application.Platform.runLater(() -> {
            double contentHeight = scroll.getContent().getBoundsInLocal().getHeight();
            double viewHeight = scroll.getViewportBounds().getHeight();
            if (node == null || contentHeight <= viewHeight) {
                then.run();
                return;
            }
            javafx.geometry.Bounds inContent = scroll.getContent().sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double top = scroll.getVvalue() * (contentHeight - viewHeight);
            double wanted = top;
            if (inContent.getMinY() < top + 40) wanted = inContent.getMinY() - 40;
            else if (inContent.getMaxY() > top + viewHeight - 170) wanted = inContent.getMaxY() - viewHeight + 170;
            double target = Math.max(0, Math.min(1, wanted / (contentHeight - viewHeight)));
            if (Math.abs(target - scroll.getVvalue()) < 0.001) {
                then.run();
                return;
            }
            javafx.animation.Timeline glide = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(320),
                    new javafx.animation.KeyValue(scroll.vvalueProperty(), target, javafx.animation.Interpolator.EASE_BOTH)));
            glide.setOnFinished(e -> then.run());
            glide.play();
        });
    }

    /** Puts the cursor in the search box (Ctrl+K). */
    public void focusSearch() {
        header.getSearchField().requestFocus();
        header.getSearchField().selectAll();
    }

    // ── Sidebar ──────────────────────────────────────────────────────────

    private VBox sidebar() {
        // Filled in by showRecent once tools have run.
        recentFiles.getChildren().setAll(sidebarSection("RECENT FILES", "Files you work on will appear here.").getChildren());
        recentTools.getChildren().setAll(sidebarSection("RECENT TOOLS", "Tools you use will appear here.").getChildren());

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

    /**
     * The sidebar's recent files (with what was last done to each, and when) and recent tools.
     * A file opens the suggestions for it; a tool opens the tool. Empty lists show a short note.
     */
    public void showRecent(List<com.ndalamo.services.HistoryService.RecentFile> files, List<PdfTool> tools,
                           Consumer<java.nio.file.Path> onFile, Runnable onHistory) {
        recentFiles.getChildren().setAll(sidebarSection("RECENT FILES", "Files you work on will appear here.").getChildren());
        if (!files.isEmpty()) {
            recentFiles.getChildren().remove(1);         // the empty note
            for (var file : files) {
                java.nio.file.Path path = java.nio.file.Path.of(file.path());
                Label name = new Label(path.getFileName().toString());
                name.getStyleClass().add("recent-name");
                Label what = new Label(file.lastTool() + " · " + ago(file.time()));
                what.getStyleClass().add("recent-detail");
                VBox row = new VBox(1, name, what);
                row.getStyleClass().add("recent-file");
                row.setFocusTraversable(true);
                row.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
                row.setAccessibleText(path.getFileName() + ", " + file.lastTool());
                javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(file.path()));
                row.setOnMouseClicked(e -> onFile.accept(path));
                row.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) onFile.accept(path);
                });
                recentFiles.getChildren().add(row);
            }
            javafx.scene.control.Hyperlink history = new javafx.scene.control.Hyperlink("View history");
            history.getStyleClass().add("sidebar-link");
            history.setOnAction(e -> onHistory.run());
            recentFiles.getChildren().add(history);
        }
        recentTools.getChildren().setAll(sidebarSection("RECENT TOOLS", "Tools you use will appear here.").getChildren());
        if (!tools.isEmpty()) {
            recentTools.getChildren().remove(1);
            javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(6, 6);
            for (PdfTool tool : tools) {
                javafx.scene.control.Button chip = new javafx.scene.control.Button(tool.name());
                chip.getStyleClass().add("tool-chip");
                chip.setOnAction(e -> onOpenTool.accept(tool));
                chips.getChildren().add(chip);
            }
            recentTools.getChildren().add(chips);
        }
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or the date. */
    static String ago(long millis) {
        long minutes = (System.currentTimeMillis() - millis) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 24 * 60) return (minutes / 60) + " h ago";
        if (minutes < 48 * 60) return "yesterday";
        if (minutes < 7 * 24 * 60) return (minutes / (24 * 60)) + " days ago";
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
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
        cards.clear();

        for (String category : Tools.CATEGORIES) {
            List<PdfTool> inCategory = matches.stream().filter(t -> t.category().equals(category)).toList();
            if (inCategory.isEmpty()) continue;   // hide sections with no matches

            Label title = new Label(category);
            title.getStyleClass().add("section-title");
            FlowPane grid = cardGrid();
            for (PdfTool tool : inCategory) {
                HBox card = toolCard(tool);
                cards.put(tool.id(), card);
                grid.getChildren().add(card);
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
