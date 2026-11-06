package com.ndalamo.screens;

import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.models.WorkspaceDocument;
import com.ndalamo.models.WorkspaceDocument.Change;
import com.ndalamo.models.WorkspacePage;
import com.ndalamo.services.FileInspector;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.services.WorkspaceService;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The Page workspace (mockup: Workspace.dc.html): open PDFs in tabs, then reorder,
 * turn, delete, add, crop, split and move pages between them, and save the result
 * as a new PDF.
 *
 * Every edit changes a WorkspaceDocument (a list of page recipes), never a file, so
 * Undo is instant and the original stays untouched. Only "Save as new PDF" and
 * "Extract" write anything, through WorkspaceService on a background thread.
 *
 * Selecting pages: click one, Ctrl+click to add or remove, Shift+click for a range.
 * Keys: Delete, Ctrl+Z (undo), Ctrl+S (save), Ctrl+A (select all).
 */
public class WorkspaceScreen extends BorderPane {

    private static final double[] ZOOM_WIDTHS = {100, 130, 160, 200, 250, 310};
    private static final double PAGE_RATIO = 297.0 / 210;    // A4 shape for every card

    private final MainWindow window;
    private final Settings settings;
    private final Runnable saveSettings;
    private final SaveTo saveTo;
    private final Runnable onHome;
    private final WorkspaceService service = new WorkspaceService();
    // Pages are drawn as sharp as the biggest zoom needs on this screen (Windows display scaling
    // included), so zooming in shrinks a sharp picture rather than stretching a small one.
    private final PageThumbnails thumbnails = new PageThumbnails(sharpEnoughForBiggestZoom());

    private final List<WorkspaceDocument> docs = new ArrayList<>();
    private WorkspaceDocument current;
    private final Set<Long> selected = new LinkedHashSet<>();
    private long anchor = -1;                 // where a Shift+click range starts
    private long focusedPage = -1;            // the page with keyboard focus, kept when the grid is rebuilt
    private double pageWidth = ZOOM_WIDTHS[2];

    private static int sharpEnoughForBiggestZoom() {
        double screenScale = Screen.getPrimary().getOutputScaleX();       // 1.25 or 1.5 on most laptops
        double biggestPage = ZOOM_WIDTHS[ZOOM_WIDTHS.length - 1] * PAGE_RATIO;
        return (int) Math.min(720, Math.ceil(biggestPage * screenScale));
    }

    private final HBox tabs = new HBox(4);
    private final Button undo = new Button("Undo");
    private final Button save = new Button("Save as new PDF");
    private final List<Button> needSelection = new ArrayList<>();
    private final FlowPane grid = new FlowPane(20, 20);
    private final StackPane centre = new StackPane();
    private final VBox panel = new VBox(22);
    private final TextField saveName = new TextField();

    public WorkspaceScreen(MainWindow window, Settings settings, Runnable saveSettings, SaveTo saveTo, Runnable onHome) {
        this.window = window;
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.saveTo = saveTo;
        this.onHome = onHome;

        setTop(new VBox(header(), actionBar()));
        setCenter(centre);
        panel.getStyleClass().add("workspace-panel");
        panel.setPrefWidth(280);
        panel.setMinWidth(260);
        panel.setMaxWidth(300);
        setRight(panel);
        addEventFilter(KeyEvent.KEY_PRESSED, this::keys);
        redraw();
    }

    // ── Opening files ────────────────────────────────────────────────────

    /**
     * Opens PDFs as new tabs, in the order given, and shows the first. Files load in
     * the background at different speeds, so each one's tab waits for the files before it.
     * Other kinds of file are skipped with a note.
     */
    public void open(List<Path> files) {
        List<String> skipped = new ArrayList<>();
        List<Path> pdfs = new ArrayList<>();
        for (Path file : files) {
            if (FileInspector.extension(file).equals("pdf")) pdfs.add(file);
            else skipped.add(file.getFileName().toString());
        }
        WorkspaceDocument[] loaded = new WorkspaceDocument[pdfs.size()];
        int[] remaining = {pdfs.size()};
        for (int i = 0; i < pdfs.size(); i++) {
            Path file = pdfs.get(i);
            int slot = i;
            Runnable oneDone = () -> {
                if (--remaining[0] > 0) return;
                WorkspaceDocument first = null;
                for (WorkspaceDocument doc : loaded) {
                    if (doc == null) continue;                 // that file couldn't be opened
                    docs.add(doc);
                    if (first == null) first = doc;
                }
                if (first != null) show(first);
            };
            loadPages(file, pages -> {
                loaded[slot] = new WorkspaceDocument(file, file.getFileName().toString(), pages);
                oneDone.run();
            }, oneDone);
        }
        if (!skipped.isEmpty()) {
            window.toast(Toast.info("Some files were left out",
                    "The page workspace opens PDFs; " + String.join(", ", skipped) + " isn't one. Your files were not changed."));
        }
    }

    /** Reads a PDF's pages in the background, then hands them over on the screen's thread. */
    private void loadPages(Path file, Consumer<List<WorkspacePage>> then) {
        loadPages(file, then, () -> {});
    }

    /** @param onFailure runs (after the error toast) if the file can't be opened */
    private void loadPages(Path file, Consumer<List<WorkspacePage>> then, Runnable onFailure) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return service.open(file);
            } catch (IOException e) {
                return null;
            }
        }).thenAccept(pages -> Platform.runLater(() -> {
            if (pages == null) {
                window.toast(Toast.error("Couldn’t open " + file.getFileName(),
                        "It may need a password (use Unlock PDF first) or be damaged. Your file was not changed."));
                onFailure.run();
                return;
            }
            thumbnails.start(file, pages.size());
            then.accept(pages);
        }));
    }

    private void show(WorkspaceDocument doc) {
        current = doc;
        selected.clear();
        anchor = -1;
        saveName.setText(defaultName(doc));
        redraw();
    }

    // ── Header: tabs, Undo, Save ─────────────────────────────────────────

    private HBox header() {
        Button home = new Button("Home", Icons.back(16, Theme.TEXT));
        home.getStyleClass().add("button-secondary");
        home.setGraphicTextGap(6);
        home.setOnAction(e -> leave(onHome));

        undo.getStyleClass().add("button-secondary");
        undo.setOnAction(e -> undo());
        save.getStyleClass().addAll("button-primary", "button-dialog");
        save.setOnAction(e -> saveCurrent(null));

        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox header = new HBox(12, LogoMark.color(28), home, tabs, push, undo, save);
        header.getStyleClass().add("screen-header");
        header.setAlignment(Pos.CENTER_LEFT);
        tabs.setAlignment(Pos.BOTTOM_LEFT);
        return header;
    }

    private void redrawTabs() {
        tabs.getChildren().clear();
        for (WorkspaceDocument doc : docs) {
            Label name = new Label((doc.isChanged() ? "• " : "") + doc.getName());
            Button close = new Button();
            close.setGraphic(Icons.close(12, Theme.TEXT_MUTED));
            close.getStyleClass().add("tab-close");
            close.setAccessibleText("Close " + doc.getName());
            close.setTooltip(new javafx.scene.control.Tooltip("Close " + doc.getName()));
            close.setOnAction(e -> closeTab(doc));
            HBox tab = new HBox(8, name, close);
            tab.getStyleClass().add(doc == current ? "ws-tab-active" : "ws-tab");
            tab.setAlignment(Pos.CENTER_LEFT);
            tab.setOnMouseClicked(e -> {
                if (doc != current) show(doc);
            });
            // From the keyboard: Tab to it, Enter or Space to open it.
            tab.setFocusTraversable(true);
            tab.setAccessibleRole(javafx.scene.AccessibleRole.TAB_ITEM);
            tab.setAccessibleText(doc.getName() + (doc == current ? ", open" : ""));
            tab.setOnKeyPressed(e -> {
                if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && doc != current) {
                    show(doc);
                    e.consume();
                }
            });
            // Drop pages on a tab to move them into that file.
            tab.setOnDragOver(e -> {
                if (doc != current && e.getDragboard().hasString() && e.getDragboard().getString().startsWith("ws:")) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    e.consume();
                }
            });
            tab.setOnDragDropped(e -> {
                moveSelectionTo(doc);
                e.setDropCompleted(true);
                e.consume();
            });
            tabs.getChildren().add(tab);
        }
        Button add = new Button("+");
        add.getStyleClass().add("ws-tab-add");
        add.setAccessibleText("Open another PDF");
        add.setOnAction(e -> chooseFilesToOpen());
        tabs.getChildren().add(add);
    }

    /** The file chooser for opening PDFs (the + tab, or Ctrl+O). */
    public void chooseFilesToOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open PDFs in the page workspace");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        List<File> chosen = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (chosen != null) open(chosen.stream().map(File::toPath).toList());
    }

    // ── Action bar ───────────────────────────────────────────────────────

    private HBox actionBar() {
        Button left = action("Rotate left", true, () -> current.rotate(selected, -90));
        Button right = action("Rotate right", true, () -> current.rotate(selected, 90));
        Button blank = action("Insert blank page", false, this::insertBlank);
        Button addPages = action("Add pages from file", false, this::addPagesFromFile);
        Button split = action("Split after selection", true, this::splitAfterSelection);
        Button crop = action("Crop", true, this::crop);
        Button delete = action("Delete", true, this::askToDelete);
        delete.setStyle("-fx-text-fill: " + Theme.DANGER + ";");

        Label zoomLabel = new Label("Zoom");
        zoomLabel.getStyleClass().add("small-muted");
        Slider zoom = new Slider(0, ZOOM_WIDTHS.length - 1, 2);
        zoom.setMajorTickUnit(1);
        zoom.setMinorTickCount(0);
        zoom.setSnapToTicks(true);
        zoom.setPrefWidth(120);
        zoom.setAccessibleText("Thumbnail size");
        zoom.valueProperty().addListener((obs, old, now) -> {
            pageWidth = ZOOM_WIDTHS[(int) Math.round(now.doubleValue())];
            redrawGrid();
        });

        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox bar = new HBox(8, left, right, blank, addPages, split, crop, delete, push, zoomLabel, zoom);
        bar.getStyleClass().add("ws-action-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** A button in the action bar; edits run on the current file, then the screen redraws. */
    private Button action(String label, boolean needsSelectedPages, Runnable edit) {
        Button button = new Button(label);
        button.getStyleClass().add("ws-action");
        button.setOnAction(e -> {
            if (current == null) return;
            edit.run();
            redraw();
        });
        if (needsSelectedPages) needSelection.add(button);
        return button;
    }

    // ── The page grid ────────────────────────────────────────────────────

    private void redrawGrid() {
        // Did a page card have keyboard focus? Asked before the old cards go.
        boolean pageHadFocus = getScene() != null && getScene().getFocusOwner() != null
                && getScene().getFocusOwner().getStyleClass().contains("ws-page-card");
        grid.getChildren().clear();
        if (current == null) return;
        List<WorkspacePage> pages = current.getPages();
        for (int i = 0; i < pages.size(); i++) grid.getChildren().add(pageCard(pages.get(i), i + 1));
        // The grid is rebuilt after every change; give keyboard focus back to the page that had it. Later, not
        // now: when the focused card is removed, JavaFX moves focus to the next button once this change is done,
        // which would undo a focus given back straight away.
        if (focusedPage >= 0 && pageHadFocus) {
            long page = focusedPage;
            javafx.application.Platform.runLater(() -> grid.getChildren().stream()
                    .filter(n -> Long.valueOf(page).equals(n.getUserData())).findFirst()
                    .ifPresent(javafx.scene.Node::requestFocus));
        }
    }

    private VBox pageCard(WorkspacePage page, int number) {
        double h = pageWidth * PAGE_RATIO;
        StackPane frame = new StackPane();
        frame.getStyleClass().add("ws-page");
        frame.setMinSize(pageWidth, h);
        frame.setMaxSize(pageWidth, h);

        if (page.isBlank()) {
            Label blank = new Label("Blank page");
            blank.getStyleClass().add("small-muted");
            frame.getChildren().add(blank);
        } else {
            ImageView picture = new ImageView();
            picture.setPreserveRatio(true);
            picture.setSmooth(true);
            boolean sideways = page.extraRotation() % 180 != 0;
            // A turned page has to fit the card sideways, so it gets a square to fit into.
            picture.setFitWidth(pageWidth - 8);
            picture.setFitHeight(sideways ? pageWidth - 8 : h - 8);
            picture.setRotate(page.extraRotation());
            thumbnails.whenReady(page.source(), page.sourcePage(), image -> {
                if (page.crop() != null) picture.setViewport(viewport(image, page));
                picture.setImage(image);
            });
            frame.getChildren().add(picture);
        }

        boolean isSelected = selected.contains(page.id());
        if (isSelected) {
            frame.getStyleClass().add("ws-page-selected");
            StackPane badge = new StackPane(Icons.check(12, "#FFFFFF"));
            badge.setMinSize(22, 22);
            badge.setMaxSize(22, 22);
            badge.setStyle("-fx-background-color: " + Theme.ACCENT + "; -fx-background-radius: 999;");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            StackPane.setMargin(badge, new Insets(6));
            frame.getChildren().add(badge);
        }

        Label caption = new Label(String.valueOf(number));
        caption.getStyleClass().add("page-caption");
        VBox card = new VBox(8, frame, caption);
        card.setAlignment(Pos.TOP_CENTER);
        card.setAccessibleText("Page " + number + (isSelected ? ", selected" : ""));
        card.setOnMouseClicked(e -> selectPage(page, e.isShiftDown(), e.isShortcutDown()));
        // From the keyboard: arrows move between pages, Space selects (Ctrl+Space adds, Shift+Space a range).
        card.setFocusTraversable(true);
        card.setUserData(page.id());
        card.getStyleClass().add("ws-page-card");
        card.focusedProperty().addListener((obs, was, now) -> {
            if (now) focusedPage = page.id();
        });
        card.setOnKeyPressed(e -> {
            // By page, not by card: the grid is rebuilt after each change, so this card may be an old copy.
            int index = current.indexOf(page.id());
            switch (e.getCode()) {
                case SPACE, ENTER -> selectPage(page, e.isShiftDown(), e.isShortcutDown());
                case RIGHT, DOWN -> focusCard(index + 1);
                case LEFT, UP -> focusCard(index - 1);
                case HOME -> focusCard(0);
                case END -> focusCard(grid.getChildren().size() - 1);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        makeDraggable(card, page);
        return card;
    }

    /** The part of the thumbnail inside the page's crop, in the thumbnail's own pixels. */
    private static Rectangle2D viewport(Image image, WorkspacePage page) {
        // Thumbnails are drawn with the file's own turn already applied; the user's extra turn is
        // done by the ImageView, so the crop is measured on the page as the file shows it.
        double[] c = page.crop().toShown(page.baseRotation());
        double w = image.getWidth(), h = image.getHeight();
        return new Rectangle2D(c[0] * w, c[1] * h, (c[2] - c[0]) * w, (c[3] - c[1]) * h);
    }

    private void focusCard(int index) {
        if (index >= 0 && index < grid.getChildren().size()) grid.getChildren().get(index).requestFocus();
    }

    /** Click or Space: select this page; with Ctrl add or remove it; with Shift select everything from the last one. */
    private void selectPage(WorkspacePage page, boolean range, boolean add) {
        long id = page.id();
        if (range && anchor >= 0 && current.indexOf(anchor) >= 0) {
            int a = current.indexOf(anchor), b = current.indexOf(id);
            selected.clear();
            List<WorkspacePage> pages = current.getPages();
            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) selected.add(pages.get(i).id());
        } else if (add) {
            if (!selected.remove(id)) selected.add(id);
            anchor = id;
        } else {
            selected.clear();
            selected.add(id);
            anchor = id;
        }
        redraw();
    }

    /** Drag a page (and the other selected pages with it) in front of another page, or onto a tab. */
    private void makeDraggable(VBox card, WorkspacePage page) {
        card.setOnDragDetected(e -> {
            if (!selected.contains(page.id())) {
                selected.clear();
                selected.add(page.id());
                anchor = page.id();
            }
            ClipboardContent content = new ClipboardContent();
            content.putString("ws:" + selected.stream().map(String::valueOf).collect(Collectors.joining(",")));
            card.startDragAndDrop(TransferMode.MOVE).setContent(content);
            e.consume();
        });
        card.setOnDragOver(e -> {
            if (e.getDragboard().hasString() && e.getDragboard().getString().startsWith("ws:")) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        card.setOnDragEntered(e -> card.getStyleClass().add("ws-drop-before"));
        card.setOnDragExited(e -> card.getStyleClass().remove("ws-drop-before"));
        card.setOnDragDropped(e -> {
            current.move(idsFrom(e.getDragboard().getString()), page.id());
            e.setDropCompleted(true);
            e.consume();
            redraw();
        });
    }

    private static Set<Long> idsFrom(String dragged) {
        return Arrays.stream(dragged.substring(3).split(",")).map(Long::parseLong)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** What fills the middle: the page grid, or a "start here" card when nothing is open. */
    private void redrawCentre() {
        if (current == null) {
            Label title = new Label("Open a PDF to start");
            title.getStyleClass().add("drop-title");
            Label hint = new Label("Choose one or more PDFs, or drop them anywhere on this window. "
                    + "Each opens in its own tab, and you can move pages between them.");
            hint.getStyleClass().add("muted");
            hint.setWrapText(true);
            Button open = new Button("Choose PDFs");
            open.getStyleClass().add("button-primary");
            open.setOnAction(e -> chooseFilesToOpen());
            VBox card = new VBox(12, title, hint, open);
            card.getStyleClass().add("card");
            card.setMaxWidth(520);
            card.setMaxHeight(Region.USE_PREF_SIZE);
            centre.getChildren().setAll(card);
            StackPane.setAlignment(card, Pos.CENTER);
            return;
        }
        grid.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        // Dropping in the empty space after the last page moves pages to the end.
        grid.setOnDragOver(e -> {
            if (e.getDragboard().hasString() && e.getDragboard().getString().startsWith("ws:")) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        grid.setOnDragDropped(e -> {
            current.move(idsFrom(e.getDragboard().getString()), -1);
            e.setDropCompleted(true);
            e.consume();
            redraw();
        });
        // Clicking empty space clears the selection.
        grid.setOnMouseClicked(e -> {
            if (e.getTarget() == grid) {
                selected.clear();
                redraw();
            }
        });
        centre.getChildren().setAll(scroll);
    }

    // ── Right panel ──────────────────────────────────────────────────────

    private void redrawPanel() {
        panel.getChildren().clear();
        if (current == null) return;

        Label title = new Label(selected.isEmpty() ? "No pages selected" : Format.count(selected.size(), "page", "pages") + " selected");
        title.getStyleClass().add("screen-title");
        Label hint = new Label(selected.isEmpty()
                ? "Click a page to select it. Ctrl+click or Shift+click to select more."
                : (selected.size() == 1 ? "Page " : "Pages ") + PageRanges.format(current.positionsOf(selected)).replace("-", "–")
                        + " · drag to move, or use the actions above");
        hint.getStyleClass().add("small-muted");
        hint.setWrapText(true);
        panel.getChildren().add(new VBox(6, title, hint));

        VBox actions = new VBox(8);
        Button extract = panelButton("Extract to a new PDF", false);
        extract.setOnAction(e -> extractSelection());
        actions.getChildren().add(extract);
        for (WorkspaceDocument other : docs) {
            if (other == current) continue;
            Button move = panelButton("Move to " + other.getName(), false);
            move.setOnAction(e -> moveSelectionTo(other));
            actions.getChildren().add(move);
        }
        Button delete = panelButton("Delete selected pages", true);
        delete.setOnAction(e -> askToDelete());
        actions.getChildren().add(delete);
        actions.getChildren().forEach(n -> n.setDisable(selected.isEmpty()));
        panel.getChildren().add(actions);

        Label saveLabel = new Label("Save as");
        saveLabel.getStyleClass().add("small-heading");
        saveName.getStyleClass().add("ws-name");
        Label never = new Label("The original file is never overwritten.");
        never.getStyleClass().add("file-meta");
        VBox saveBox = new VBox(8, saveLabel, saveName, never);
        saveBox.getStyleClass().add("save-section");
        panel.getChildren().add(saveBox);

        Region push = new Region();
        VBox.setVgrow(push, Priority.ALWAYS);
        Label count = new Label(Format.count(current.size(), "page", "pages")
                + (current.getOrigin() != null ? " · " + Format.size(service.size(current.getOrigin())) : ""));
        count.getStyleClass().add("small-muted");
        Label applied = new Label(current.isChanged() ? "Changes are applied when you save" : "No changes yet");
        applied.getStyleClass().add("small-muted");
        panel.getChildren().addAll(push, new VBox(4, count, applied));
    }

    private static Button panelButton(String label, boolean danger) {
        Button button = new Button(label);
        button.setMnemonicParsing(false);     // file names often contain "_", which buttons would otherwise hide
        button.getStyleClass().add(danger ? "ws-panel-danger" : "ws-panel-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        return button;
    }

    private void redraw() {
        selected.removeIf(id -> current == null || current.indexOf(id) < 0);   // pages that are gone can't stay selected
        redrawTabs();
        redrawCentre();
        redrawGrid();
        redrawPanel();
        undo.setDisable(current == null || !current.canUndo());
        save.setDisable(current == null || current.size() == 0);
        needSelection.forEach(b -> b.setDisable(current == null || selected.isEmpty()));
    }

    // ── Edits ────────────────────────────────────────────────────────────

    private void undo() {
        if (current != null && current.undo()) redraw();
    }

    /** After the last selected page, or at the end if nothing is selected. */
    private int afterSelection() {
        int last = -1;
        for (long id : selected) last = Math.max(last, current.indexOf(id));
        return last < 0 ? current.size() : last + 1;
    }

    private void insertBlank() {
        WorkspacePage blank = current.insertBlank(afterSelection());
        selected.clear();
        selected.add(blank.id());
        anchor = blank.id();
    }

    private void addPagesFromFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add pages from a PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        WorkspaceDocument target = current;
        int position = afterSelection();
        loadPages(file.toPath(), pages -> {
            target.add(pages, position, Change.ADDED);
            selected.clear();
            pages.forEach(p -> selected.add(p.id()));
            redraw();
            window.toast(Toast.info(Format.count(pages.size(), "page", "pages") + " added",
                    "From " + file.getName() + ". Nothing is saved until you click Save."));
        });
    }

    private void splitAfterSelection() {
        long last = -1;
        int lastIndex = -1;
        for (long id : selected) {
            if (current.indexOf(id) > lastIndex) {
                lastIndex = current.indexOf(id);
                last = id;
            }
        }
        List<WorkspacePage> after = current.splitAfter(last);
        if (after.isEmpty()) {
            window.toast(Toast.info("Nothing to split off", "Select a page before the end; the pages after it become a new file."));
            return;
        }
        WorkspaceDocument part = new WorkspaceDocument(current.getOrigin(), current.getName() + " (part 2)", List.of());
        part.add(after, -1, Change.SPLIT_OFF);       // a change, so leaving asks to save it
        docs.add(docs.indexOf(current) + 1, part);
        window.toast(Toast.info("Split into two files",
                Format.count(after.size(), "page", "pages") + " moved to a new tab. Save each one when you're ready."));
    }

    private void crop() {
        WorkspacePage first = current.getPages().stream()
                .filter(p -> selected.contains(p.id()) && !p.isBlank()).findFirst().orElse(null);
        if (first == null) {
            window.toast(Toast.info("Nothing to crop", "Blank pages can't be cropped. Select a page from a file."));
            return;
        }
        WorkspaceDocument target = current;
        Set<Long> ids = Set.copyOf(selected);
        window.showDialog(new CropDialog(window, thumbnails, first, ids.size(), crop -> {
            target.crop(ids, crop);
            redraw();
        }));
    }

    private void askToDelete() {
        if (selected.isEmpty()) return;
        if (selected.size() == current.size()) {
            window.toast(Toast.info("Keep at least one page",
                    "A PDF needs at least one page. To get rid of the whole file, just close its tab."));
            return;
        }
        if (!settings.isAskBeforeDeletingPages()) {
            deleteSelection();
            return;
        }
        List<Integer> positions = current.positionsOf(selected);
        String which = positions.size() == 1 ? "Page " + positions.get(0)
                : "Pages " + joinWithAnd(positions);
        new ChoiceDialog(window, "Delete " + Format.count(selected.size(), "page", "pages") + "?",
                which + " will be removed from " + current.getName()
                        + ". You can undo until you save, and the original file stays untouched.",
                "Don’t ask again")
                .button("Cancel", ChoiceDialog.Style.PLAIN, dontAsk -> {})
                .button("Delete pages", ChoiceDialog.Style.DANGER, dontAsk -> {
                    if (dontAsk) {
                        settings.setAskBeforeDeletingPages(false);
                        saveSettings.run();
                    }
                    deleteSelection();
                })
                .show();
    }

    private void deleteSelection() {
        int count = selected.size();
        WorkspaceDocument target = current;
        target.delete(selected);
        redraw();
        window.toast(Toast.undo(Format.count(count, "page", "pages") + " deleted",
                "Nothing is saved until you click Save.", new Toast.Action("Undo", () -> {
                    target.undo();
                    if (target == current) redraw();
                })));
    }

    private void moveSelectionTo(WorkspaceDocument other) {
        if (selected.isEmpty() || other == current) return;
        List<WorkspacePage> moving = current.takeOut(selected, Change.MOVED);
        other.add(moving, -1, Change.MOVED);
        window.toast(Toast.info(Format.count(moving.size(), "page", "pages") + " moved",
                "Now at the end of " + other.getName() + "."));
        redraw();
    }

    // ── Saving ───────────────────────────────────────────────────────────

    /** "Report_edited.pdf" by the user's naming pattern, next to the original. */
    private String defaultName(WorkspaceDocument doc) {
        Path origin = doc.getOrigin();
        if (origin == null) return doc.getName() + ".pdf";
        String suffix = doc.getName().endsWith("(part 2)") ? "part 2" : "edited";
        return saveTo.outputFor(origin, suffix, "pdf", 0).getFileName().toString();
    }

    private Path folderFor(WorkspaceDocument doc) {
        Path origin = doc.getOrigin() != null ? doc.getOrigin()
                : doc.getPages().stream().filter(p -> !p.isBlank()).map(WorkspacePage::source).findFirst().orElse(null);
        return origin != null ? saveTo.folderFor(origin) : Path.of(System.getProperty("user.home"));
    }

    /** Saves the current tab under the name in the Save as box. {@code then} runs after a successful save. */
    private void saveCurrent(Runnable then) {
        if (current == null) return;
        String typed = saveName.getText().strip();
        if (typed.isEmpty()) {
            window.toast(Toast.info("Name the new PDF first", "Type a name in the Save as box on the right."));
            return;
        }
        if (!typed.toLowerCase().endsWith(".pdf")) typed += ".pdf";
        Path target = folderFor(current).resolve(typed.replaceAll("[<>:\"/\\\\|?*]", "_"));
        if (isASourceFile(current, target)) {
            new ChoiceDialog(window, "Choose another name",
                    target.getFileName() + " is one of the files these pages come from. "
                            + "Ndalamo never overwrites an original, so the new PDF needs a different name.", null)
                    .button("OK", ChoiceDialog.Style.PRIMARY, x -> saveName.requestFocus()).show();
            return;
        }
        Path free = freeName(target);
        WorkspaceDocument doc = current;
        List<WorkspacePage> pages = doc.getPages();
        save.setDisable(true);
        save.setText("Saving…");
        CompletableFuture.runAsync(() -> {
            try {
                service.save(pages, free);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((ok, error) -> Platform.runLater(() -> {
            save.setText("Save as new PDF");
            if (error != null) {
                redraw();
                window.showDialog(new ErrorDialog(window, "Couldn’t save " + free.getFileName(),
                        "The new PDF couldn't be written. The original files were not changed, and your edits are still here.",
                        error.getCause() != null ? error.getCause() : error, "Try again", () -> saveCurrent(then)));
                return;
            }
            doc.markSaved();
            redraw();
            window.toast(Toast.success("Saved " + free.getFileName(),
                    Format.count(pages.size(), "page", "pages") + (free.equals(target) ? "" : " · that name was taken, so a number was added"),
                    new Toast.Action("Show in folder", () -> MessageDialog.ifFileExists(window, free, () -> FileActions.showInFolder(free))),
                    new Toast.Action("Open", () -> MessageDialog.ifFileExists(window, free, () -> FileActions.open(free)))));
            if (then != null) then.run();
        }));
    }

    /** Saves just the selected pages as a new PDF, named after their pages. */
    private void extractSelection() {
        if (selected.isEmpty()) return;
        List<WorkspacePage> chosen = current.getPages().stream().filter(p -> selected.contains(p.id())).toList();
        String label = "pages " + PageRanges.format(current.positionsOf(selected)).replace(", ", "+");
        Path origin = current.getOrigin() != null ? current.getOrigin() : folderFor(current).resolve(current.getName() + ".pdf");
        Path target = saveTo.inFolder(folderFor(current)).outputFor(origin, label, "pdf", chosen.size());
        CompletableFuture.runAsync(() -> {
            try {
                service.save(chosen, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((ok, error) -> Platform.runLater(() -> {
            if (error != null) {
                window.toast(Toast.error("Couldn’t extract the pages", "Nothing was saved and your files were not changed."));
                return;
            }
            window.toast(Toast.success("Extracted " + Format.count(chosen.size(), "page", "pages"), target.getFileName().toString(),
                    new Toast.Action("Show in folder", () -> MessageDialog.ifFileExists(window, target, () -> FileActions.showInFolder(target))),
                    new Toast.Action("Open", () -> MessageDialog.ifFileExists(window, target, () -> FileActions.open(target)))));
        }));
    }

    private static boolean isASourceFile(WorkspaceDocument doc, Path target) {
        for (WorkspacePage page : doc.getPages()) {
            if (page.isBlank()) continue;
            try {
                if (Files.exists(target) && Files.isSameFile(target, page.source())) return true;
            } catch (IOException e) {
                return true;     // can't tell: be safe
            }
        }
        return doc.getOrigin() != null && target.toAbsolutePath().equals(doc.getOrigin().toAbsolutePath());
    }

    /** The name itself, or "name (2).pdf", "name (3).pdf"… if it's taken. */
    private static Path freeName(Path target) {
        if (!Files.exists(target)) return target;
        String file = target.getFileName().toString();
        String stem = file.substring(0, file.length() - 4);
        for (int n = 2; ; n++) {
            Path numbered = target.resolveSibling(stem + " (" + n + ").pdf");
            if (!Files.exists(numbered)) return numbered;
        }
    }

    // ── Leaving and closing with unsaved changes ─────────────────────────

    /** Called before going Home: asks about each file with unsaved changes, then goes. */
    public void leave(Runnable thenGo) {
        WorkspaceDocument unsaved = docs.stream().filter(WorkspaceDocument::isChanged).findFirst().orElse(null);
        if (unsaved == null) {
            thenGo.run();
            return;
        }
        askToSave(unsaved, () -> {
            docs.remove(unsaved);
            if (current == unsaved) current = docs.isEmpty() ? null : docs.get(0);
            leave(thenGo);
        });
    }

    private void closeTab(WorkspaceDocument doc) {
        Runnable close = () -> {
            int index = docs.indexOf(doc);
            docs.remove(doc);
            if (current == doc) {
                if (docs.isEmpty()) current = null;
                else show(docs.get(Math.max(0, index - 1)));
            }
            redraw();
        };
        if (doc.isChanged()) askToSave(doc, close);
        else close.run();
    }

    /** "Save changes to X?" — Don't save · Cancel · Save as new PDF (mockup: Dialogs.dc.html). */
    private void askToSave(WorkspaceDocument doc, Runnable afterwards) {
        if (doc != current) show(doc);
        new ChoiceDialog(window, "Save changes to " + doc.getName() + "?",
                doc.changeSummary() + " If you don’t save, these changes are lost.", null)
                .button("Don’t save", ChoiceDialog.Style.PLAIN, x -> afterwards.run())
                .button("Cancel", ChoiceDialog.Style.PLAIN, x -> {})
                .button("Save as new PDF", ChoiceDialog.Style.PRIMARY, x -> saveCurrent(afterwards))
                .show();
    }

    // ── Keyboard ─────────────────────────────────────────────────────────

    private void keys(KeyEvent e) {
        if (current == null || e.getTarget() instanceof TextInputControl) return;   // typing a name: leave keys alone
        if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            askToDelete();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
            undo();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.S) {
            saveCurrent(null);
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.A) {
            selected.clear();
            current.getPages().forEach(p -> selected.add(p.id()));
            redraw();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.TAB && docs.size() > 1) {
            // Ctrl+Tab: the next open file (Ctrl+Shift+Tab: the one before).
            int i = docs.indexOf(current) + (e.isShiftDown() ? -1 : 1);
            show(docs.get(Math.floorMod(i, docs.size())));
        } else {
            return;
        }
        e.consume();
    }

    private static String joinWithAnd(List<Integer> numbers) {
        List<String> parts = numbers.stream().map(String::valueOf).toList();
        if (parts.size() > 6) return PageRanges.format(numbers);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
