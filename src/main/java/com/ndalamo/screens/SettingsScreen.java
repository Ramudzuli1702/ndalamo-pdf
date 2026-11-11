package com.ndalamo.screens;

import com.ndalamo.models.OptionField;
import com.ndalamo.models.Settings;
import com.ndalamo.tools.LibreOffice;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.OutputNames;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Settings screen (mockup: docs/ui/screens/Settings.dc.html): the sections on
 * the left, one card per section on the right. Every change is saved at once
 * through {@code save}; there's no Save button to forget.
 */
public class SettingsScreen extends BorderPane {

    /** The tool options offered as defaults, as "tool id.option id" with a label. */
    private static final Map<String, String> TOOL_DEFAULTS = new LinkedHashMap<>();
    static {
        TOOL_DEFAULTS.put("compress.mode", "Compression level");
        TOOL_DEFAULTS.put("ocr.language", "OCR language");
        TOOL_DEFAULTS.put("pdf-to-jpg.quality", "PDF to JPG quality");
        TOOL_DEFAULTS.put("page-numbers.position", "Page number position");
        TOOL_DEFAULTS.put("page-numbers.style", "Page number style");
    }

    private final MainWindow window;
    private final Settings settings;
    private final Runnable save;
    private final VBox cards = new VBox(20);
    private final ScrollPane scroll = new ScrollPane();
    private final Map<String, Node> sections = new LinkedHashMap<>();
    private Runnable onClearRecent = () -> {};
    private Runnable onShowTour = () -> {};

    /** Also empties History (the recent files and tools come from it). */
    public void setOnClearRecent(Runnable action) {
        this.onClearRecent = action;
    }

    /** "Take the tour" in Help. */
    public void setOnShowTour(Runnable action) {
        this.onShowTour = action;
    }

    /** "Tell us what you think" in Help; the button only shows once this is set. */
    public void setOnFeedback(Runnable action) {
        feedback.setOnAction(e -> action.run());
        feedback.setVisible(true);
    }

    private final Button feedback = new Button("Tell us what you think");
    private final Map<Node, Button> navItems = new LinkedHashMap<>();
    /** The section last picked in the list, so it stays highlighted when it can't scroll to the top. */
    private Node picked;

    /**
     * @param save         writes the settings to disk
     * @param onAppearance applies Light / Dark / Follow system straight away
     * @param wordAddIn    the Word add-in card's contents (status and buttons), built by its own class
     */
    public SettingsScreen(MainWindow window, Settings settings, Runnable save, Consumer<Settings.Appearance> onAppearance,
                          Node wordAddIn, String version, Runnable onHome) {
        this.window = window;
        this.settings = settings;
        this.save = save;
        getStyleClass().add("settings-screen");
        setTop(new ScreenHeader("Settings", onHome));

        add("Output", output());
        add("Tool defaults", toolDefaults());
        add("Office conversion", office());
        add("Performance", performance());
        add("Appearance", appearance(onAppearance));
        add("Keyboard shortcuts", shortcuts());
        add("Privacy", privacy());
        add("Word add-in", wordAddIn);
        add("Help", help());
        add("About", about(version));

        cards.setPadding(new Insets(28, 32, 40, 32));
        cards.setMaxWidth(860);
        // The cards sit in the middle of the space beside the section list, however wide the window.
        VBox centred = new VBox(cards);
        centred.setAlignment(Pos.TOP_CENTER);
        scroll.setContent(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
        setLeft(nav());
        // Highlight the section being read as the cards scroll past.
        scroll.vvalueProperty().addListener((obs, was, now) -> highlightSectionInView());
        scroll.viewportBoundsProperty().addListener((obs, was, now) -> highlightSectionInView());
    }

    /** The section list on the left; clicking a section scrolls to its card. */
    private VBox nav() {
        VBox nav = new VBox(2);
        nav.getStyleClass().add("settings-nav");
        nav.setPrefWidth(230);
        nav.setPadding(new Insets(24, 12, 24, 16));
        sections.forEach((title, card) -> {
            Button item = new Button(title);
            item.getStyleClass().add("settings-nav-item");
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setOnAction(e -> {
                picked = card;
                scrollTo(card);
                highlight(card);
            });
            navItems.put(card, item);
            nav.getChildren().add(item);
        });
        if (!navItems.isEmpty()) highlight(navItems.keySet().iterator().next());
        return nav;
    }

    private void highlight(Node card) {
        navItems.forEach((c, item) -> {
            item.getStyleClass().remove("settings-nav-selected");
            if (c == card) item.getStyleClass().add("settings-nav-selected");
        });
    }

    /**
     * The section being read is the last card whose top has come into the top third of the view.
     * At the very bottom the last few cards can't reach the top, so a section picked in the list
     * stays highlighted there, and otherwise the last card is.
     */
    private void highlightSectionInView() {
        double content = scroll.getContent().getBoundsInLocal().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        // Nothing to follow until the cards are laid out and taller than the view.
        if (content <= viewport || viewport <= 0) return;
        double top = (content - viewport) * scroll.getVvalue();
        boolean atBottom = scroll.getVvalue() >= 0.999;
        // A section picked in the list stays highlighted while it's where the click put it.
        if (picked != null) {
            boolean stillThere = Math.abs(cardTop(picked) - top) <= 2 || (atBottom && cardTop(picked) >= top - 1);
            if (stillThere) {
                highlight(picked);
                return;
            }
            picked = null;
        }
        Node current = null;
        for (Node card : navItems.keySet()) {
            if (cardTop(card) <= top + viewport / 3) current = card;
        }
        if (atBottom) current = cards.getChildren().get(cards.getChildren().size() - 1);
        if (current != null) highlight(current);
    }

    /** A card's top within the scrolling content. */
    private double cardTop(Node card) {
        return cards.getBoundsInParent().getMinY() + card.getBoundsInParent().getMinY();
    }

    private void scrollTo(Node card) {
        double content = scroll.getContent().getBoundsInLocal().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        double y = cardTop(card);
        scroll.setVvalue(content > viewport ? Math.min(1, y / (content - viewport)) : 0);
    }

    private void add(String title, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-card-title");
        VBox card = new VBox(14, heading, content);
        card.getStyleClass().add("settings-card");
        card.setPadding(new Insets(20, 24, 22, 24));
        sections.put(title, card);
        cards.getChildren().add(card);
    }

    // ── Output ───────────────────────────────────────────────────────────

    private VBox output() {
        ToggleGroup where = new ToggleGroup();
        RadioButton same = radio("Same folder as the original", where);
        RadioButton fixed = radio("A fixed folder", where);
        RadioButton ask = radio("Ask every time", where);
        Label folder = new Label(settings.getFixedFolder() == null ? "No folder chosen" : settings.getFixedFolder());
        folder.getStyleClass().add("mono-text");
        Button browse = new Button("Browse…");
        browse.getStyleClass().add("button-secondary");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Save results to this folder");
            File picked = chooser.showDialog(getScene().getWindow());
            if (picked == null) return;
            settings.setFixedFolder(picked.getAbsolutePath());
            settings.setSaveLocation(Settings.SaveLocation.FIXED_FOLDER);
            folder.setText(picked.getAbsolutePath());
            fixed.setSelected(true);
            save.run();
        });
        HBox fixedRow = new HBox(10, fixed, folder, browse);
        fixedRow.setAlignment(Pos.CENTER_LEFT);
        switch (settings.getSaveLocation()) {
            case FIXED_FOLDER -> fixed.setSelected(true);
            case ASK -> ask.setSelected(true);
            default -> same.setSelected(true);
        }
        where.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == fixed && settings.getFixedFolder() == null) {
                browse.fire();                                      // a fixed folder needs a folder
                if (settings.getFixedFolder() == null) {
                    old.setSelected(true);
                    return;
                }
            }
            settings.setSaveLocation(now == fixed ? Settings.SaveLocation.FIXED_FOLDER
                    : now == ask ? Settings.SaveLocation.ASK : Settings.SaveLocation.SAME_FOLDER);
            save.run();
        });
        VBox location = group("Save results to", same, fixedRow, ask);

        TextField pattern = new TextField(settings.getFileNamePattern());
        pattern.setMaxWidth(360);
        pattern.setAccessibleText("File name pattern");
        Label preview = new Label();
        preview.getStyleClass().add("small-muted");
        Runnable showPreview = () -> {
            String p = pattern.getText().isBlank() ? "{name}_{tool}" : pattern.getText();
            String name = OutputNames.fillPattern(p, Path.of("Assignment2.pdf"), "compressed", 12, LocalDate.now()) + ".pdf";
            preview.setText("Preview: " + name + " · also available: {date}, {pages}"
                    + (p.contains("{name}") ? "" : " · without {name}, every result gets the same name and a number"));
        };
        pattern.textProperty().addListener((obs, old, now) -> {
            showPreview.run();
            if (!now.isBlank()) {
                settings.setFileNamePattern(now.strip());
                save.run();
            }
        });
        showPreview.run();
        VBox naming = group("File name pattern", pattern, preview);

        ToggleGroup exists = new ToggleGroup();
        RadioButton number = radio("Add a number (Assignment2_compressed (2).pdf)", exists);
        RadioButton askMe = radio("Ask me", exists);
        RadioButton replace = radio("Replace it", exists);
        switch (settings.getIfExists()) {
            case ASK -> askMe.setSelected(true);
            case REPLACE -> replace.setSelected(true);
            default -> number.setSelected(true);
        }
        exists.selectedToggleProperty().addListener((obs, old, now) -> {
            settings.setIfExists(now == askMe ? Settings.IfExists.ASK : now == replace ? Settings.IfExists.REPLACE
                    : Settings.IfExists.ADD_NUMBER);
            save.run();
        });
        VBox ifExists = group("If a file with that name exists", number, askMe, replace);

        CheckBox previewFirst = new CheckBox("Preview results before saving");
        previewFirst.setSelected(settings.isPreviewBeforeSaving());
        previewFirst.selectedProperty().addListener((obs, old, now) -> {
            settings.setPreviewBeforeSaving(now);
            save.run();
        });
        Label previewNote = new Label("Check every result page by page first. Nothing reaches your folders until you choose Save.");
        previewNote.getStyleClass().add("small-muted");
        previewNote.setWrapText(true);
        VBox previewGroup = new VBox(4, previewFirst, previewNote);

        CheckBox never = new CheckBox("Never overwrite the original file (always on)");
        never.setSelected(true);
        never.setDisable(true);
        return new VBox(18, location, naming, ifExists, previewGroup, never);
    }

    // ── Tool defaults ────────────────────────────────────────────────────

    /** One list per option, its choices read from the tool itself so they can't drift apart. */
    private VBox toolDefaults() {
        VBox rows = new VBox(10);
        TOOL_DEFAULTS.forEach((key, label) -> {
            String toolId = key.substring(0, key.indexOf('.')), optionId = key.substring(key.indexOf('.') + 1);
            PdfTool tool = Tools.find(toolId).orElse(null);
            OptionField field = tool == null ? null : tool.optionFields().stream()
                    .filter(f -> f.getId().equals(optionId)).findFirst().orElse(null);
            if (field == null || field.getChoices().isEmpty()) return;
            ComboBox<String> list = new ComboBox<>();
            list.getItems().addAll(field.getChoices());
            String current = settings.getToolDefaults().get(key);
            list.setValue(current != null && field.getChoices().contains(current) ? current : String.valueOf(field.getDefaultValue()));
            list.setPrefWidth(280);
            list.setAccessibleText(label);
            list.valueProperty().addListener((obs, old, now) -> {
                settings.getToolDefaults().put(key, now);
                save.run();
            });
            Label name = new Label(label);
            name.getStyleClass().add("field-label");
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            HBox row = new HBox(12, name, push, list);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        });
        Label note = new Label("Each tool starts with these; you can still change them for one job on the tool screen.");
        note.getStyleClass().add("small-muted");
        note.setWrapText(true);
        rows.getChildren().add(note);
        return rows;
    }

    // ── Office conversion ────────────────────────────────────────────────

    private VBox office() {
        Label status = new Label("Looking for LibreOffice…");
        status.setWrapText(true);
        Label path = new Label();
        path.getStyleClass().add("mono-text");
        Runnable check = () -> CompletableFuture.supplyAsync(LibreOffice::find).thenAccept(found -> Platform.runLater(() -> {
            status.setText(found != null ? "LibreOffice found · Word, Excel and PowerPoint to PDF are available"
                    : "LibreOffice not found · Office to PDF won't work until it's installed");
            status.getStyleClass().setAll("label", found != null ? "status-good" : "status-warning");
            path.setText(found == null ? "" : found.toString());
        }));
        Button change = new Button("Change location…");
        change.getStyleClass().add("button-secondary");
        change.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Find LibreOffice (soffice.exe)");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("LibreOffice (soffice.exe)", "soffice.exe"));
            File picked = chooser.showOpenDialog(getScene().getWindow());
            if (picked == null) return;
            Path program = LibreOffice.programIn(picked.toPath());
            if (program == null) {
                window.toast(Toast.error("That isn't LibreOffice", "Pick soffice.exe in LibreOffice's “program” folder."));
                return;
            }
            settings.setLibreOfficePath(program.toString());
            LibreOffice.setChosenLocation(program.toString());
            save.run();
            check.run();
        });
        check.run();
        Label note = new Label("Not installed? The app works without it — only Office to PDF needs it.");
        note.getStyleClass().add("small-muted");
        return new VBox(10, status, path, change, note);
    }

    // ── Performance ──────────────────────────────────────────────────────

    private VBox performance() {
        ToggleGroup group = new ToggleGroup();
        RadioButton automatic = radio("Automatic (based on this PC)", group);
        RadioButton one = radio("1 — best for older laptops", group);
        RadioButton two = radio("2", group);
        RadioButton four = radio("4", group);
        switch (settings.getParallelism()) {
            case ONE -> one.setSelected(true);
            case TWO -> two.setSelected(true);
            case FOUR -> four.setSelected(true);
            default -> automatic.setSelected(true);
        }
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            settings.setParallelism(now == one ? Settings.Parallelism.ONE : now == two ? Settings.Parallelism.TWO
                    : now == four ? Settings.Parallelism.FOUR : Settings.Parallelism.AUTOMATIC);
            save.run();
        });
        CheckBox lowMemory = new CheckBox("Low-memory mode for large files (slower, but won’t freeze)");
        lowMemory.setSelected(settings.isLowMemoryMode());
        lowMemory.selectedProperty().addListener((obs, old, now) -> {
            settings.setLowMemoryMode(now);
            save.run();
        });
        Label note = new Label("Changes here take effect the next time NdalamoPDF starts.");
        note.getStyleClass().add("small-muted");
        return new VBox(14, group("Files processed at the same time", automatic, one, two, four), lowMemory, note);
    }

    // ── Appearance ───────────────────────────────────────────────────────

    private VBox appearance(Consumer<Settings.Appearance> onAppearance) {
        ToggleGroup group = new ToggleGroup();
        RadioButton light = radio("Light", group);
        RadioButton dark = radio("Dark", group);
        RadioButton system = radio("Follow system", group);
        switch (settings.getAppearance()) {
            case DARK -> dark.setSelected(true);
            case FOLLOW_SYSTEM -> system.setSelected(true);
            default -> light.setSelected(true);
        }
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            Settings.Appearance chosen = now == dark ? Settings.Appearance.DARK
                    : now == system ? Settings.Appearance.FOLLOW_SYSTEM : Settings.Appearance.LIGHT;
            settings.setAppearance(chosen);
            save.run();
            onAppearance.accept(chosen);
        });
        HBox row = new HBox(24, light, dark, system);
        return new VBox(row);
    }

    // ── Keyboard shortcuts ───────────────────────────────────────────────

    /** Every shortcut, so nobody has to discover them by accident (SPEC §6.3). */
    private VBox shortcuts() {
        String[][] keys = {
                {"Ctrl+K", "Search tools, from any screen"},
                {"Ctrl+O", "Open files"},
                {"Esc", "Close a dialog, or the newest message"},
                {"Tab / Shift+Tab", "Move between buttons, fields and pages"},
                {"Enter or Space", "Press the button, or choose the page, that has the focus ring"},
                {"Ctrl+S", "Page workspace: save as a new PDF"},
                {"Ctrl+Z", "Page workspace: undo"},
                {"Delete", "Page workspace: delete the selected pages"},
                {"Ctrl+A", "Page workspace: select every page"},
                {"Arrow keys", "Page workspace: move between pages (Ctrl+Space adds one, Shift+Space a range)"},
                {"Ctrl+Tab", "Page workspace: the next open file"}};
        VBox rows = new VBox(8);
        for (String[] key : keys) {
            Label shortcut = new Label(key[0]);
            shortcut.getStyleClass().add("key-hint");
            shortcut.setMinWidth(Region.USE_PREF_SIZE);
            Label what = new Label(key[1]);
            what.setWrapText(true);
            HBox row = new HBox(12, shortcut, what);
            row.setAlignment(Pos.CENTER_LEFT);
            shortcut.setPrefWidth(130);
            rows.getChildren().add(row);
        }
        return rows;
    }

    // ── Privacy ──────────────────────────────────────────────────────────

    private VBox privacy() {
        Label statement = new Label("Everything runs on this PC. No files, usage data or crash reports are sent anywhere. "
                + "The only things NdalamoPDF ever sends are a review or answers you choose to send.");
        statement.setWrapText(true);
        statement.getStyleClass().add("status-good");
        CheckBox remember = new CheckBox("Remember recent files");
        remember.setSelected(settings.isRememberRecentFiles());
        remember.selectedProperty().addListener((obs, old, now) -> {
            settings.setRememberRecentFiles(now);
            if (!now) {
                settings.getRecentFiles().clear();
                settings.getRecentTools().clear();
                onClearRecent.run();
            }
            save.run();
        });
        Button clear = new Button("Clear recent files and tools");
        clear.getStyleClass().add("button-secondary");
        clear.setOnAction(e -> {
            settings.getRecentFiles().clear();
            settings.getRecentTools().clear();
            onClearRecent.run();
            save.run();
            window.toast(Toast.info("Recent files, tools and history cleared", "Your files themselves were not touched."));
        });
        return new VBox(12, statement, remember, clear);
    }

    // ── Help ─────────────────────────────────────────────────────────────

    private VBox help() {
        Label about = new Label("A short tour of Home and every tool, the same one shown the first time NdalamoPDF opens.");
        about.setWrapText(true);
        Button tour = new Button("Take the tour");
        tour.getStyleClass().add("button-secondary");
        tour.setOnAction(e -> onShowTour.run());
        Label shortcuts = new Label("Keyboard shortcuts are listed above. Drop files anywhere on the window to start.");
        shortcuts.getStyleClass().add("small-muted");
        shortcuts.setWrapText(true);
        feedback.getStyleClass().add("button-secondary");
        feedback.setVisible(false);
        feedback.managedProperty().bind(feedback.visibleProperty());
        HBox buttons = new HBox(8, tour, feedback);
        return new VBox(10, about, buttons, shortcuts);
    }

    // ── About ────────────────────────────────────────────────────────────

    private VBox about(String version) {
        Label name = new Label("NdalamoPDF");
        name.getStyleClass().add("section-title");
        Label versionLabel = new Label("Version " + version);
        versionLabel.getStyleClass().add("small-muted");
        Label meaning = new Label("“Ndalamo” means prosperity.");
        Label heading = new Label("Open-source licences");
        heading.getStyleClass().add("field-label");
        VBox list = new VBox(6);
        for (String[] component : components()) {
            Label what = new Label(component[0]);
            what.setWrapText(true);
            Label licence = new Label(component[1]);
            licence.getStyleClass().add("small-muted");
            VBox text = new VBox(1, what, licence);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, text);
            row.setAlignment(Pos.CENTER_LEFT);
            if (component.length > 2 && !component[2].isBlank()) {
                Button view = new Button("Read licence");
                view.getStyleClass().add("link");
                view.setOnAction(e -> showLicence(component[0], component[2]));
                row.getChildren().add(view);
            }
            list.getChildren().add(row);
        }
        return new VBox(6, name, versionLabel, meaning, new Region(), heading, list);
    }

    /** The components list bundled at /licenses/components.txt: {name, licence, files}. */
    private static List<String[]> components() {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = SettingsScreen.class.getResourceAsStream("/licenses/components.txt")) {
            if (in == null) return rows;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                rows.add(line.split("\\|", -1));
            }
        } catch (IOException ignored) {
            // The list is bundled; without it the About card just has no licence rows.
        }
        return rows;
    }

    /** A licence's full text in a dialog (several texts, one after the other, when a component has a choice). */
    private void showLicence(String component, String files) {
        StringBuilder text = new StringBuilder();
        for (String file : files.split(",")) {
            String resource = file.startsWith("/") ? file : "/licenses/" + file;
            try (InputStream in = SettingsScreen.class.getResourceAsStream(resource)) {
                if (in != null) text.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip()).append("\n\n\n");
            } catch (IOException ignored) {
                // A missing text is skipped; the others still show.
            }
        }
        TextArea area = new TextArea(text.toString().strip());
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("licence-text");
        area.setPrefSize(640, 420);
        Label title = new Label(component);
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);
        Button close = new Button("Close");
        close.getStyleClass().addAll("button-primary", "button-dialog");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> window.closeDialog());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox dialog = new VBox(14, title, area, buttons);
        dialog.getStyleClass().add("dialog-card");
        dialog.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        window.showDialog(dialog);
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private static RadioButton radio(String text, ToggleGroup group) {
        RadioButton radio = new RadioButton(text);
        radio.setToggleGroup(group);
        radio.setMnemonicParsing(false);
        return radio;
    }

    private static VBox group(String title, Node... items) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        VBox group = new VBox(8, label);
        group.getChildren().addAll(items);
        return group;
    }
}
