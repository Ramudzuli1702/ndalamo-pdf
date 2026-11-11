package com.ndalamo.screens;

import com.ndalamo.models.PdfEdits;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.tools.EditTool;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Edit PDF editor: a page shown large, with two ways to work.
 *
 * Remove text: drag over text (or click a word) to select it, then press Delete or
 * "Remove selected". "Select highlighted text" picks out what a highlighter marked.
 * Removed text is shown covered here; in the saved file it's taken out for good.
 *
 * Add text: click where it should go and type, in a chosen font, size and colour.
 * Added text can be dragged to move it, double-clicked to change it, and deleted.
 *
 * Ctrl+Z undoes. Positions are kept as fractions of the page as shown (PdfEdits), so
 * EditTool can apply them to the file. The file is only read here.
 */
public class EditDialog extends VBox {

    private static final String[] COLOURS = {"#1F2430", "#2F5BEA", "#C0392B", "#1E8A4C"};
    private static final String[] COLOUR_NAMES = {"Black", "Blue", "Red", "Green"};
    private static final Map<String, String> SCREEN_FONTS = Map.of(
            "Sans", "Liberation Sans", "Serif", "Liberation Serif", "Mono", "Liberation Mono", "Handwriting", "Dancing Script");
    private static boolean fontsLoaded;

    private final MainWindow window;
    private final PageThumbnails thumbnails;
    private final Path file;
    private final int pageCount;
    private final double viewW, viewH;
    private final Map<Integer, EditTool.PageInfo> pages = new HashMap<>();

    private final Pane canvas = new Pane();
    private final ImageView pageView = new ImageView();
    private final Pane removedLayer = new Pane();
    private final Pane selectionLayer = new Pane();
    private final Pane additionLayer = new Pane();
    private final Rectangle dragBox = new Rectangle();
    private final Label pageLabel = new Label();
    private final Label summary = new Label();
    private final Label status = new Label();
    private final ToggleButton removeMode = new ToggleButton("Remove text");
    private final ToggleButton addMode = new ToggleButton("Add text");
    private final ComboBox<String> fontBox = new ComboBox<>();
    private final ComboBox<Integer> sizeBox = new ComboBox<>();
    private final CheckBox boldBox = new CheckBox("Bold");
    private final ToggleGroup colourGroup = new ToggleGroup();

    private int page = 1;
    private double shownW, shownH, offX, offY;
    private List<PdfEdits.Removal> removals = new ArrayList<>();
    private List<PdfEdits.Addition> additions = new ArrayList<>();
    private final Deque<PdfEdits> undo = new ArrayDeque<>();
    private final List<double[]> selection = new ArrayList<>();   // areas selected on this page, not yet removed
    private int selectedAddition = -1;
    private TextArea typing;                                      // the box being typed in, if any
    private double dragX, dragY;
    private boolean dragging;

    public EditDialog(MainWindow window, PageThumbnails thumbnails, Path file, int pageCount, PdfEdits start,
                      Consumer<PdfEdits> onDone) {
        super(14);
        this.window = window;
        this.thumbnails = thumbnails;
        this.file = file;
        this.pageCount = Math.max(1, pageCount);
        loadScreenFonts();
        getStyleClass().add("dialog-card");
        setMaxWidth(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);
        if (start != null) {
            removals = new ArrayList<>(start.removals());
            additions = new ArrayList<>(start.additions());
        }

        // As big as the window allows, leaving room for the panel and buttons.
        viewH = Math.max(420, Math.min(860, (window.getHeight() > 0 ? window.getHeight() : 900) - 230));
        viewW = viewH * 0.9;

        Label title = new Label("Edit PDF");
        title.getStyleClass().add("dialog-title");
        Label hint = new Label("Remove text by dragging over it, or add text by clicking where it should go.");
        hint.getStyleClass().add("dialog-text");

        canvas.setPrefSize(viewW, viewH);
        canvas.setMinSize(viewW, viewH);
        canvas.setMaxSize(viewW, viewH);
        canvas.setStyle("-fx-background-color: " + Theme.BG + ";");
        Rectangle clip = new Rectangle(viewW, viewH);
        canvas.setClip(clip);
        pageView.setPreserveRatio(true);
        dragBox.setFill(Color.web("#2F5BEA", 0.08));
        dragBox.setStroke(Color.web("#2F5BEA"));
        dragBox.getStrokeDashArray().addAll(4.0, 3.0);
        dragBox.setVisible(false);
        dragBox.setMouseTransparent(true);
        removedLayer.setMouseTransparent(true);
        selectionLayer.setMouseTransparent(true);
        additionLayer.setPickOnBounds(false);
        canvas.getChildren().addAll(pageView, removedLayer, selectionLayer, additionLayer, dragBox);
        canvas.setOnMousePressed(this::pressed);
        canvas.setOnMouseDragged(this::dragged);
        canvas.setOnMouseReleased(this::released);

        HBox middle = new HBox(16, new StackPane(canvas), sidePanel());
        HBox.setHgrow(middle.getChildren().get(1), Priority.NEVER);

        Button previous = new Button("‹");
        previous.getStyleClass().add("button-secondary");
        previous.setAccessibleText("Previous page");
        previous.setOnAction(e -> showPage(page - 1));
        Button next = new Button("›");
        next.getStyleClass().add("button-secondary");
        next.setAccessibleText("Next page");
        next.setOnAction(e -> showPage(page + 1));
        pageLabel.getStyleClass().add("small-muted");
        HBox nav = new HBox(8, previous, pageLabel, next);
        nav.setAlignment(Pos.CENTER_LEFT);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setOnAction(e -> window.closeDialog());
        Button done = new Button("Done");
        done.getStyleClass().addAll("button-primary", "button-dialog");
        done.setOnAction(e -> {
            commitTyping();
            window.closeDialog();
            onDone.accept(new PdfEdits(List.copyOf(removals), List.copyOf(additions)));
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, nav, push, cancel, done);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, hint, middle, buttons);
        addEventFilter(KeyEvent.KEY_PRESSED, this::keys);
        updateSummary();
        showPage(1);
    }

    // ── The side panel ───────────────────────────────────────────────────

    private VBox sidePanel() {
        ToggleGroup modes = new ToggleGroup();
        removeMode.setToggleGroup(modes);
        addMode.setToggleGroup(modes);
        removeMode.setSelected(true);
        removeMode.getStyleClass().add("edit-mode");
        addMode.getStyleClass().add("edit-mode");
        removeMode.setMaxWidth(Double.MAX_VALUE);
        addMode.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(removeMode, Priority.ALWAYS);
        HBox.setHgrow(addMode, Priority.ALWAYS);
        modes.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) was.setSelected(true);           // one mode is always on
            commitTyping();
            selection.clear();
            drawSelection();
            canvas.setCursor(addMode.isSelected() ? Cursor.TEXT : Cursor.CROSSHAIR);
            showModeControls();
        });
        HBox modeRow = new HBox(0, removeMode, addMode);
        modeRow.getStyleClass().add("edit-modes");

        VBox panel = new VBox(14, modeRow, removeControls(), addControls(), new Region(), status, summary, undoButton());
        VBox.setVgrow(panel.getChildren().get(3), Priority.ALWAYS);
        panel.setPrefWidth(260);
        panel.setMinWidth(260);
        panel.setMaxWidth(260);
        status.setWrapText(true);
        status.getStyleClass().add("small-muted");
        summary.getStyleClass().add("small-muted");
        canvas.setCursor(Cursor.CROSSHAIR);
        showModeControls();
        return panel;
    }

    private VBox removeControls;
    private VBox addControls;

    private VBox removeControls() {
        Label text = new Label("Drag over the text to remove, or click a word. Then press Delete.");
        text.setWrapText(true);
        text.getStyleClass().add("small-muted");
        Button remove = new Button("Remove selected");
        remove.getStyleClass().add("button-primary");
        remove.setMaxWidth(Double.MAX_VALUE);
        remove.setOnAction(e -> removeSelection());
        Button highlighted = new Button("Select highlighted text");
        highlighted.getStyleClass().add("button-secondary");
        highlighted.setMaxWidth(Double.MAX_VALUE);
        highlighted.setOnAction(e -> selectHighlighted());
        removeControls = new VBox(10, text, remove, highlighted);
        return removeControls;
    }

    private VBox addControls() {
        Label text = new Label("Click on the page where the text should go, then type. Drag added text to move it; "
                + "double-click to change it.");
        text.setWrapText(true);
        text.getStyleClass().add("small-muted");
        fontBox.getItems().setAll(PdfEdits.FONTS);
        fontBox.setValue("Sans");
        fontBox.setMaxWidth(Double.MAX_VALUE);
        sizeBox.getItems().setAll(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48);
        sizeBox.setValue(12);
        sizeBox.setMaxWidth(Double.MAX_VALUE);
        HBox colours = new HBox(6);
        for (int i = 0; i < COLOURS.length; i++) {
            ToggleButton swatch = new ToggleButton();
            swatch.setToggleGroup(colourGroup);
            swatch.setUserData(COLOURS[i]);
            swatch.setAccessibleText(COLOUR_NAMES[i]);
            swatch.getStyleClass().add("colour-swatch");
            swatch.setStyle("-fx-background-color: " + COLOURS[i] + ";");
            if (i == 0) swatch.setSelected(true);
            colours.getChildren().add(swatch);
        }
        colourGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) was.setSelected(true);
        });
        // Changing the style changes the text being typed, or the added text picked.
        Runnable restyle = this::restyleCurrent;
        fontBox.valueProperty().addListener((o, a, b) -> restyle.run());
        sizeBox.valueProperty().addListener((o, a, b) -> restyle.run());
        boldBox.selectedProperty().addListener((o, a, b) -> restyle.run());
        colourGroup.selectedToggleProperty().addListener((o, a, b) -> restyle.run());
        Button delete = new Button("Delete this text");
        delete.getStyleClass().add("button-secondary");
        delete.setMaxWidth(Double.MAX_VALUE);
        delete.setOnAction(e -> deleteSelectedAddition());
        addControls = new VBox(10, text, labelled("Font", fontBox), labelled("Size", sizeBox), boldBox,
                labelled("Colour", colours), delete);
        return addControls;
    }

    private static VBox labelled(String name, javafx.scene.Node control) {
        Label label = new Label(name);
        label.getStyleClass().add("small-heading");
        return new VBox(4, label, control);
    }

    private Button undoButton() {
        Button button = new Button("Undo");
        button.getStyleClass().add("button-secondary");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> undo());
        return button;
    }

    private void showModeControls() {
        if (removeControls == null || addControls == null) return;
        boolean adding = addMode.isSelected();
        removeControls.setVisible(!adding);
        removeControls.setManaged(!adding);
        addControls.setVisible(adding);
        addControls.setManaged(adding);
        status.setText("");
    }

    // ── Pages ────────────────────────────────────────────────────────────

    private void showPage(int number) {
        commitTyping();
        page = Math.max(1, Math.min(pageCount, number));
        pageLabel.setText("Page " + page + " of " + pageCount);
        selection.clear();
        selectedAddition = -1;
        status.setText("");
        int asked = page;
        thumbnails.renderLarge(file, page, 0, (int) (viewH * 2), image -> {
            if (asked != page) return;
            pageView.setImage(image);
            double scale = Math.min(viewW / image.getWidth(), viewH / image.getHeight());
            shownW = image.getWidth() * scale;
            shownH = image.getHeight() * scale;
            offX = (viewW - shownW) / 2;
            offY = (viewH - shownH) / 2;
            pageView.setFitWidth(shownW);
            pageView.setFitHeight(shownH);
            pageView.relocate(offX, offY);
            redraw();
        });
        if (!pages.containsKey(page)) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return EditTool.page(file, asked);
                } catch (java.io.IOException e) {
                    return new EditTool.PageInfo(595, 842, List.of(), List.of());
                }
            }).thenAccept(info -> Platform.runLater(() -> {
                pages.put(asked, info);
                if (asked == page) redraw();
            }));
        }
    }

    private EditTool.PageInfo info() {
        return pages.get(page);
    }

    private void redraw() {
        drawRemoved();
        drawSelection();
        drawAdditions();
    }

    /** Removed text shows as covered, the way it will look once saved. */
    private void drawRemoved() {
        removedLayer.getChildren().clear();
        EditTool.PageInfo info = info();
        if (info == null || shownW <= 0) return;
        for (PdfEdits.Removal r : removals) {
            if (r.page() != page) continue;
            for (EditTool.Letter l : info.letters()) {
                if (!r.contains(mid(l.left(), l.right()), mid(l.top(), l.bottom()))) continue;
                Rectangle cover = rect(l.left(), l.top(), l.right(), l.bottom(), 0.6);
                cover.setFill(Color.WHITE);
                removedLayer.getChildren().add(cover);
            }
            Rectangle outline = rect(r.left(), r.top(), r.right(), r.bottom(), 2);
            outline.setFill(Color.TRANSPARENT);
            outline.setStroke(Color.web("#C0392B", 0.55));
            outline.getStrokeDashArray().addAll(3.0, 3.0);
            removedLayer.getChildren().add(outline);
        }
    }

    private void drawSelection() {
        selectionLayer.getChildren().clear();
        EditTool.PageInfo info = info();
        if (info == null || shownW <= 0) return;
        for (EditTool.Letter l : selectedLetters()) {
            Rectangle mark = rect(l.left(), l.top(), l.right(), l.bottom(), 0.4);
            mark.setFill(Color.web("#2F5BEA", 0.28));
            selectionLayer.getChildren().add(mark);
        }
    }

    private void drawAdditions() {
        additionLayer.getChildren().clear();
        EditTool.PageInfo info = info();
        if (shownW <= 0) return;
        double perPoint = shownH / (info != null ? info.height() : 842);
        for (int i = 0; i < additions.size(); i++) {
            PdfEdits.Addition a = additions.get(i);
            if (a.page() != page) continue;
            Label label = new Label(a.text());
            label.setFont(screenFont(a.font(), a.bold(), a.size() * perPoint));
            label.setTextFill(Color.web(a.color()));
            label.setLineSpacing(a.size() * perPoint * 0.08);
            label.getStyleClass().add("edit-addition");
            if (i == selectedAddition) label.getStyleClass().add("edit-addition-selected");
            label.relocate(offX + a.left() * shownW, offY + a.top() * shownH);
            int index = i;
            label.setCursor(Cursor.MOVE);
            label.setOnMousePressed(e -> {
                e.consume();
                commitTyping();
                selectAddition(index);
                dragX = e.getSceneX();
                dragY = e.getSceneY();
                dragging = false;
                if (e.getClickCount() == 2) editAddition(index);
            });
            label.setOnMouseDragged(e -> {
                e.consume();
                PdfEdits.Addition was = additions.get(index);
                if (!dragging) {
                    remember();
                    dragging = true;
                }
                double dx = (e.getSceneX() - dragX) / shownW, dy = (e.getSceneY() - dragY) / shownH;
                dragX = e.getSceneX();
                dragY = e.getSceneY();
                additions.set(index, move(was, clamp(was.left() + dx, 0, 0.98), clamp(was.top() + dy, 0, 0.98)));
                label.relocate(offX + additions.get(index).left() * shownW, offY + additions.get(index).top() * shownH);
            });
            label.setOnMouseReleased(MouseEvent::consume);
            additionLayer.getChildren().add(label);
        }
    }

    // ── Selecting and removing ───────────────────────────────────────────

    private void pressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || shownW <= 0) return;
        if (typing != null) {
            commitTyping();
            return;
        }
        selectedAddition = -1;
        double fx = (e.getX() - offX) / shownW, fy = (e.getY() - offY) / shownH;
        if (fx < 0 || fy < 0 || fx > 1 || fy > 1) return;
        if (addMode.isSelected()) {
            startTyping(fx, fy, null, -1);
            return;
        }
        dragX = e.getX();
        dragY = e.getY();
        dragging = false;
        if (!e.isShiftDown()) selection.clear();
        drawAdditions();
    }

    private void dragged(MouseEvent e) {
        if (addMode.isSelected() || shownW <= 0) return;
        if (!dragging && Math.hypot(e.getX() - dragX, e.getY() - dragY) < 4) return;
        dragging = true;
        dragBox.setVisible(true);
        dragBox.setX(Math.min(dragX, e.getX()));
        dragBox.setY(Math.min(dragY, e.getY()));
        dragBox.setWidth(Math.abs(e.getX() - dragX));
        dragBox.setHeight(Math.abs(e.getY() - dragY));
    }

    private void released(MouseEvent e) {
        if (addMode.isSelected() || shownW <= 0) return;
        dragBox.setVisible(false);
        EditTool.PageInfo info = info();
        if (info == null) return;
        if (dragging) {
            double[] area = {
                    (Math.min(dragX, e.getX()) - offX) / shownW, (Math.min(dragY, e.getY()) - offY) / shownH,
                    (Math.max(dragX, e.getX()) - offX) / shownW, (Math.max(dragY, e.getY()) - offY) / shownH};
            double[] tight = tighten(area, info.letters());
            if (tight != null) selection.add(tight);
            else status.setText(info.letters().isEmpty()
                    ? "This page has no text that can be removed. If it's a scan, the words are a picture: use Redact PDF to black them out."
                    : "No text there. Drag over the words you want to remove.");
        } else {
            double[] word = wordAt((e.getX() - offX) / shownW, (e.getY() - offY) / shownH, info.letters());
            if (word != null) selection.add(word);
        }
        dragging = false;
        drawSelection();
        int count = selectedLetters().size();
        if (count > 0) status.setText(count + (count == 1 ? " letter" : " letters") + " selected. Press Delete to remove.");
    }

    /** The selected letters' own box, so the removal takes exactly those letters; null if none. */
    private static double[] tighten(double[] area, List<EditTool.Letter> letters) {
        double l = 2, t = 2, r = -1, b = -1;
        for (EditTool.Letter letter : letters) {
            double mx = mid(letter.left(), letter.right()), my = mid(letter.top(), letter.bottom());
            if (mx < area[0] || mx > area[2] || my < area[1] || my > area[3]) continue;
            l = Math.min(l, mx);
            t = Math.min(t, my);
            r = Math.max(r, mx);
            b = Math.max(b, my);
        }
        if (r < 0) return null;
        double pad = 0.0005;
        return new double[]{l - pad, t - pad, r + pad, b + pad};
    }

    /** The word under a click: the letters next to each other up to the spaces either side. */
    private static double[] wordAt(double fx, double fy, List<EditTool.Letter> letters) {
        int hit = -1;
        for (int i = 0; i < letters.size(); i++) {
            EditTool.Letter l = letters.get(i);
            if (fx >= l.left() && fx <= l.right() && fy >= l.top() && fy <= l.bottom() && !l.text().isBlank()) {
                hit = i;
                break;
            }
        }
        if (hit < 0) return null;
        int from = hit, to = hit;
        while (from > 0 && sameWord(letters.get(from - 1), letters.get(from))) from--;
        while (to < letters.size() - 1 && sameWord(letters.get(to), letters.get(to + 1))) to++;
        return tighten(new double[]{0, 0, 1, 1}, letters.subList(from, to + 1));
    }

    private static boolean sameWord(EditTool.Letter a, EditTool.Letter b) {
        if (a.text().isBlank() || b.text().isBlank()) return false;
        double height = a.bottom() - a.top();
        return Math.abs(mid(a.top(), a.bottom()) - mid(b.top(), b.bottom())) < height / 2
               && b.left() - a.right() < height * 0.25 && b.left() >= a.left();
    }

    private List<EditTool.Letter> selectedLetters() {
        EditTool.PageInfo info = info();
        if (info == null || selection.isEmpty()) return List.of();
        List<EditTool.Letter> chosen = new ArrayList<>();
        for (EditTool.Letter l : info.letters()) {
            double mx = mid(l.left(), l.right()), my = mid(l.top(), l.bottom());
            for (double[] a : selection) {
                if (mx >= a[0] && mx <= a[2] && my >= a[1] && my <= a[3]) {
                    chosen.add(l);
                    break;
                }
            }
        }
        return chosen;
    }

    private void selectHighlighted() {
        EditTool.PageInfo info = info();
        if (info == null) return;
        selection.clear();
        for (double[] h : info.highlights()) {
            double[] tight = tighten(h, info.letters());
            if (tight != null) selection.add(tight);
        }
        drawSelection();
        int marks = info.highlights().size();
        status.setText(marks == 0 ? "Nothing on this page is marked with a highlighter."
                : selectedLetters().size() + " highlighted letters selected. Press Delete to remove them and their marks.");
    }

    private void removeSelection() {
        if (selection.isEmpty()) {
            status.setText("Select some text first: drag over it, or click a word.");
            return;
        }
        remember();
        for (double[] a : selection) removals.add(new PdfEdits.Removal(page, a[0], a[1], a[2], a[3]));
        int count = selectedLetters().size();
        selection.clear();
        status.setText(count + (count == 1 ? " letter" : " letters") + " will be removed when you save.");
        updateSummary();
        redraw();
    }

    // ── Adding text ──────────────────────────────────────────────────────

    /** Opens a box to type in at a point, or over an existing addition to change it. */
    private void startTyping(double fx, double fy, String text, int replacing) {
        EditTool.PageInfo info = info();
        double perPoint = shownH / (info != null ? info.height() : 842);
        typing = new TextArea(text == null ? "" : text);
        typing.setPrefRowCount(Math.max(1, text == null ? 1 : text.split("\n", -1).length));
        typing.setPrefColumnCount(18);
        typing.setWrapText(false);
        typing.getStyleClass().add("edit-typing");
        typing.setFont(screenFont(fontBox.getValue(), boldBox.isSelected(), sizeBox.getValue() * perPoint));
        typing.setStyle("-fx-text-fill: " + colour() + ";");
        typing.setUserData(new double[]{fx, fy, replacing});
        typing.relocate(offX + fx * shownW - 6, offY + fy * shownH - 5);
        typing.addEventFilter(KeyEvent.KEY_PRESSED, k -> {
            if (k.getCode() == KeyCode.ESCAPE) {
                k.consume();
                cancelTyping();
            } else if (k.getCode() == KeyCode.ENTER && k.isControlDown()) {
                k.consume();
                commitTyping();
            }
        });
        // Not finished on losing focus: the font, size and colour controls take focus while typing.
        canvas.getChildren().add(typing);
        Platform.runLater(() -> {
            if (typing != null) {
                typing.requestFocus();
                typing.end();
            }
        });
        status.setText("Type the text. Click elsewhere on the page or press Ctrl+Enter to finish; Esc to cancel.");
    }

    private void commitTyping() {
        if (typing == null) return;
        TextArea box = typing;
        typing = null;
        canvas.getChildren().remove(box);
        double[] at = (double[]) box.getUserData();
        int replacing = (int) at[2];
        String text = box.getText().stripTrailing();
        remember();
        if (replacing >= 0) additions.remove(replacing);
        if (!text.isBlank()) {
            additions.add(new PdfEdits.Addition(page, at[0], at[1], text, sizeBox.getValue(), boldBox.isSelected(),
                    colour(), fontBox.getValue()));
            selectedAddition = additions.size() - 1;
        } else {
            selectedAddition = -1;
        }
        status.setText(text.isBlank() ? "" : "Added. Drag it to move it; double-click to change it.");
        updateSummary();
        drawAdditions();
    }

    private void cancelTyping() {
        if (typing == null) return;
        canvas.getChildren().remove(typing);
        typing = null;
        status.setText("");
        drawAdditions();
    }

    private void selectAddition(int index) {
        selectedAddition = index;
        PdfEdits.Addition a = additions.get(index);
        if (!addMode.isSelected()) addMode.setSelected(true);
        // Show its style in the panel without changing it.
        int keep = selectedAddition;
        selectedAddition = -1;
        fontBox.setValue(a.font());
        sizeBox.setValue(Math.round(a.size()));
        boldBox.setSelected(a.bold());
        colourGroup.getToggles().stream().filter(t -> a.color().equalsIgnoreCase((String) t.getUserData()))
                .findFirst().ifPresent(t -> t.setSelected(true));
        selectedAddition = keep;
        drawAdditions();
    }

    private void editAddition(int index) {
        PdfEdits.Addition a = additions.get(index);
        startTyping(a.left(), a.top(), a.text(), index);
        additionLayer.getChildren().clear();
    }

    private void deleteSelectedAddition() {
        if (selectedAddition < 0 || selectedAddition >= additions.size()) {
            status.setText("Click an added text first to pick it.");
            return;
        }
        remember();
        additions.remove(selectedAddition);
        selectedAddition = -1;
        updateSummary();
        drawAdditions();
    }

    /** The font, size, weight or colour was changed: apply it to what's being typed or picked. */
    private void restyleCurrent() {
        EditTool.PageInfo info = info();
        double perPoint = shownH / (info != null ? info.height() : 842);
        if (typing != null) {
            typing.setFont(screenFont(fontBox.getValue(), boldBox.isSelected(), sizeBox.getValue() * perPoint));
            typing.setStyle("-fx-text-fill: " + colour() + ";");
        } else if (selectedAddition >= 0 && selectedAddition < additions.size()) {
            PdfEdits.Addition a = additions.get(selectedAddition);
            remember();
            additions.set(selectedAddition, new PdfEdits.Addition(a.page(), a.left(), a.top(), a.text(),
                    sizeBox.getValue(), boldBox.isSelected(), colour(), fontBox.getValue()));
            drawAdditions();
        }
    }

    private String colour() {
        return colourGroup.getSelectedToggle() == null ? COLOURS[0] : (String) colourGroup.getSelectedToggle().getUserData();
    }

    private static PdfEdits.Addition move(PdfEdits.Addition a, double left, double top) {
        return new PdfEdits.Addition(a.page(), left, top, a.text(), a.size(), a.bold(), a.color(), a.font());
    }

    // ── Keys, undo, summary ──────────────────────────────────────────────

    private void keys(KeyEvent e) {
        if (typing != null) return;
        if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            e.consume();
            if (selectedAddition >= 0) deleteSelectedAddition();
            else removeSelection();
        } else if (e.getCode() == KeyCode.Z && e.isShortcutDown()) {
            e.consume();
            undo();
        } else if (e.getCode() == KeyCode.PAGE_DOWN) {
            showPage(page + 1);
        } else if (e.getCode() == KeyCode.PAGE_UP) {
            showPage(page - 1);
        }
    }

    private void remember() {
        undo.push(new PdfEdits(List.copyOf(removals), List.copyOf(additions)));
        if (undo.size() > 200) undo.removeLast();
    }

    private void undo() {
        cancelTyping();
        if (undo.isEmpty()) {
            status.setText("Nothing to undo.");
            return;
        }
        PdfEdits last = undo.pop();
        removals = new ArrayList<>(last.removals());
        additions = new ArrayList<>(last.additions());
        selectedAddition = -1;
        selection.clear();
        status.setText("Undone.");
        updateSummary();
        redraw();
    }

    private void updateSummary() {
        PdfEdits edits = new PdfEdits(removals, additions);
        summary.setText(edits.isEmpty() ? "No changes yet" : "Changes: " + edits.describe());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Rectangle rect(double l, double t, double r, double b, double pad) {
        Rectangle box = new Rectangle(offX + l * shownW - pad, offY + t * shownH - pad,
                (r - l) * shownW + 2 * pad, (b - t) * shownH + 2 * pad);
        box.setMouseTransparent(true);
        return box;
    }

    private static double mid(double a, double b) {
        return (a + b) / 2;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** The same bundled fonts the file gets, so the text looks here as it will in the PDF. */
    private static Font screenFont(String family, boolean bold, double size) {
        String name = SCREEN_FONTS.getOrDefault(family, "Liberation Sans");
        return Font.font(name, bold && !"Handwriting".equals(family) ? FontWeight.BOLD : FontWeight.NORMAL,
                FontPosture.REGULAR, Math.max(4, size));
    }

    private static void loadScreenFonts() {
        if (fontsLoaded) return;
        fontsLoaded = true;
        for (String f : List.of("/fonts/liberation/LiberationSans-Regular.ttf", "/fonts/liberation/LiberationSans-Bold.ttf",
                "/fonts/liberation/LiberationSerif-Regular.ttf", "/fonts/liberation/LiberationSerif-Bold.ttf",
                "/fonts/liberation/LiberationMono-Regular.ttf", "/fonts/liberation/LiberationMono-Bold.ttf",
                "/fonts/DancingScript-Regular.ttf")) {
            try (InputStream in = EditDialog.class.getResourceAsStream(f)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (java.io.IOException ignored) {
                // the system's default font stands in on screen; the PDF still gets the right one
            }
        }
    }
}
