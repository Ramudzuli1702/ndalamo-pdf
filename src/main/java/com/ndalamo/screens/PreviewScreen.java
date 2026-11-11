package com.ndalamo.screens;

import com.ndalamo.models.FileResult;
import com.ndalamo.services.FileInspector;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Check before saving: the results of a run, shown from the private staging folder
 * (see StagedResults) before anything reaches the user's folders. PDFs are shown page
 * by page, pictures as they are; Word, Excel, Markdown and ZIP files open in their own
 * program to check. Save puts them in place; Discard deletes them and goes back to the
 * tool with the same files and choices.
 */
public class PreviewScreen extends BorderPane {

    /** Pages are drawn this many pixels on their long side: sharp at full width on a laptop screen. */
    private static final int PAGE_PIXELS = 1400;

    private final MainWindow window;
    private final List<FileResult> outputs;
    private final BorderPane viewer = new BorderPane();
    private final List<HBox> rows = new ArrayList<>();
    private final Button save = new Button();
    private final Button discard = new Button("Discard");
    private final Label status = new Label("Not saved yet. Your original files are unchanged.");
    private final PageThumbnails pictures = new PageThumbnails();

    /**
     * @param results   what the tool made, in the staging folder (failed files included, to show why)
     * @param whereTo   e.g. "Same folder as each original"
     * @param onSave    puts the results in place (the flow does the work and shows the results after)
     * @param onDiscard deletes them and goes back to the tool
     */
    public PreviewScreen(PdfTool tool, List<FileResult> results, String whereTo, MainWindow window, Runnable onHome,
                         Runnable onSave, Runnable onDiscard) {
        this.window = window;
        this.outputs = results.stream().filter(r -> r.getOutput() != null).toList();

        ScreenHeader header = new ScreenHeader(tool.name(), onHome);
        Label step = new Label("Check before saving");
        step.getStyleClass().add("step-label");
        header.getChildren().add(step);
        setTop(header);

        VBox list = fileList(results);
        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("plain-scroll");
        listScroll.setPrefWidth(300);
        listScroll.setMinWidth(240);
        VBox side = new VBox(10, sideTitle(), listScroll);
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        side.setPadding(new Insets(20, 12, 20, 20));
        side.getStyleClass().add("preview-side");

        viewer.getStyleClass().add("preview-viewer");
        HBox.setHgrow(viewer, Priority.ALWAYS);
        setCenter(new HBox(side, viewer));
        setBottom(footer(whereTo, onSave, onDiscard));

        if (!outputs.isEmpty()) show(outputs.get(0));
    }

    private Label sideTitle() {
        Label title = new Label(Format.count(outputs.size(), "result", "results"));
        title.getStyleClass().add("section-title");
        return title;
    }

    private VBox fileList(List<FileResult> results) {
        VBox list = new VBox(4);
        for (FileResult result : results) {
            Path shown = result.getOutput() != null ? result.getOutput() : result.getInput();
            Label name = new Label(shown.getFileName().toString());
            name.getStyleClass().add("file-name");
            name.setMinWidth(0);
            String meta = result.getOutput() != null
                    ? (result.getSizeBefore() > 0 ? Format.sizeChange(result.getSizeBefore(), result.getSizeAfter()) : Format.size(result.getSizeAfter()))
                    : result.getNote() == null ? "Not made" : result.getNote();
            Label detail = new Label(meta);
            detail.getStyleClass().add("file-meta");
            detail.setWrapText(true);
            if (result.getOutput() == null) detail.setStyle("-fx-text-fill: " + Theme.DANGER + ";");
            VBox text = new VBox(2, name, detail);
            text.setMinWidth(0);
            HBox row = new HBox(10, Icons.document(18, Theme.TEXT_MUTED), text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("preview-file");
            if (result.getOutput() != null) {
                row.setOnMouseClicked(e -> show(result));
                row.setUserData(result);
            }
            rows.add(row);
            list.getChildren().add(row);
        }
        return list;
    }

    private HBox footer(String whereTo, Runnable onSave, Runnable onDiscard) {
        status.getStyleClass().add("muted");
        Label where = new Label("Saves to: " + whereTo);
        where.getStyleClass().add("small-muted");
        VBox text = new VBox(2, status, where);
        HBox.setHgrow(text, Priority.ALWAYS);

        discard.getStyleClass().add("button-secondary");
        discard.setOnAction(e -> onDiscard.run());
        save.setText(outputs.size() > 1 ? "Save " + outputs.size() + " files" : "Save");
        save.getStyleClass().add("button-primary");
        save.setDisable(outputs.isEmpty());
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            saving();
            onSave.run();
        });
        HBox bar = new HBox(12, text, discard, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 24, 14, 24));
        bar.getStyleClass().add("preview-footer");
        return bar;
    }

    /** Buttons off while the files are being put in place. */
    public void saving() {
        save.setDisable(true);
        discard.setDisable(true);
        status.setText("Saving…");
    }

    /** Saving failed: buttons back, with what happened. */
    public void saveFailed(String message) {
        save.setDisable(false);
        discard.setDisable(false);
        status.setText(message);
    }

    private void show(FileResult result) {
        for (HBox row : rows) {
            row.getStyleClass().remove("preview-file-selected");
            if (row.getUserData() == result) row.getStyleClass().add("preview-file-selected");
        }
        Path file = result.getOutput();
        String ext = FileInspector.extension(file);
        if (ext.equals("pdf")) {
            showPdf(file);
        } else if (FileInspector.IMAGE_EXTENSIONS.contains(ext)) {
            showPicture(file);
        } else {
            showOpenToCheck(file, result.getSizeAfter());
        }
    }

    // ── PDFs: every page, drawn as it scrolls into view ───────────────────

    private void showPdf(Path file) {
        int pages = new FileInspector().inspect(file).getPages();
        int shown = Math.min(pages, PageThumbnails.MAX_PAGES);
        VBox column = new VBox(18);
        column.setAlignment(Pos.TOP_CENTER);
        column.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");

        // Wide enough to read, never wider than the space: pages fit the viewer's width up to a limit.
        var width = scroll.widthProperty().subtract(60);
        List<StackPane> slots = new ArrayList<>();
        for (int page = 1; page <= shown; page++) {
            ImageView view = new ImageView();
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(width, 720));
            StackPane slot = new StackPane(view);
            slot.getStyleClass().add("preview-page");
            slot.setMaxWidth(Region.USE_PREF_SIZE);
            // A4-shaped space until the picture arrives, so the scroll bar is about right from the start.
            slot.minHeightProperty().bind(view.fitWidthProperty().multiply(1.414));
            slot.setUserData(new int[]{page, 0});            // page number, drawn yet (0/1)
            Label caption = new Label("Page " + page + " of " + pages);
            caption.getStyleClass().add("page-caption");
            column.getChildren().addAll(slot, caption);
            slots.add(slot);
        }
        if (pages > shown) {
            Label more = new Label("The first " + shown + " pages are shown. Open the file to see the rest.");
            more.getStyleClass().add("small-muted");
            column.getChildren().add(more);
        }

        Runnable drawVisible = () -> drawVisible(file, scroll, slots);
        scroll.vvalueProperty().addListener((o, a, b) -> drawVisible.run());
        scroll.viewportBoundsProperty().addListener((o, a, b) -> drawVisible.run());
        viewer.setTop(openBar(file, Format.count(pages, "page", "pages")));
        viewer.setCenter(scroll);
        Platform.runLater(drawVisible);
    }

    /** Starts drawing the pages in or near view; each page is drawn once. */
    private void drawVisible(Path file, ScrollPane scroll, List<StackPane> slots) {
        Bounds viewport = scroll.localToScene(scroll.getBoundsInLocal());
        if (viewport == null) return;
        double margin = viewport.getHeight();               // one screen ahead, so scrolling finds pages ready
        for (StackPane slot : slots) {
            int[] state = (int[]) slot.getUserData();
            if (state[1] == 1) continue;
            Bounds b = slot.localToScene(slot.getBoundsInLocal());
            if (b.getMaxY() < viewport.getMinY() - margin || b.getMinY() > viewport.getMaxY() + margin) continue;
            state[1] = 1;
            ImageView view = (ImageView) slot.getChildren().get(0);
            pictures.renderLarge(file, state[0], 0, PAGE_PIXELS, image -> {
                view.setImage(image);
                slot.minHeightProperty().unbind();
                slot.setMinHeight(Region.USE_COMPUTED_SIZE);
            });
        }
    }

    // ── Pictures and other files ─────────────────────────────────────────

    private void showPicture(Path file) {
        ImageView view = new ImageView(new Image(file.toUri().toString(), true));
        view.setPreserveRatio(true);
        StackPane box = new StackPane(view);
        box.setPadding(new Insets(24));
        view.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(box.widthProperty().subtract(48), 1200));
        view.fitHeightProperty().bind(box.heightProperty().subtract(48));
        viewer.setTop(openBar(file, "Picture"));
        viewer.setCenter(box);
    }

    private static final Map<String, String> KINDS = Map.of(
            "docx", "Word document", "xlsx", "Excel workbook", "md", "Markdown file", "zip", "ZIP folder");

    private void showOpenToCheck(Path file, long size) {
        String ext = FileInspector.extension(file);
        String kind = KINDS.getOrDefault(ext, ext.toUpperCase(Locale.ROOT) + " file");
        Label title = new Label(kind + " · " + Format.size(size));
        title.getStyleClass().add("section-title");
        Label text = new Label("NdalamoPDF can’t show this kind of file inside the app. Open it to check it in its own "
                + "program. Nothing is saved until you choose Save, and changes made there won’t be kept.");
        text.setWrapText(true);
        text.setMaxWidth(460);
        text.getStyleClass().add("muted");
        Button open = new Button("Open to check");
        open.getStyleClass().add("button-primary");
        open.setOnAction(e -> FileActions.open(file));
        VBox card = new VBox(12, Icons.document(40, Theme.ACCENT), title, text, open);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(560);
        card.setPadding(new Insets(32));
        card.getStyleClass().add("card");
        StackPane centre = new StackPane(card);
        centre.setPadding(new Insets(24));
        viewer.setTop(null);
        viewer.setCenter(centre);
    }

    /** File name, its size or page count, and Open (a read-only look in the usual program). */
    private Node openBar(Path file, String detail) {
        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("file-name");
        Label meta = new Label(detail);
        meta.getStyleClass().add("file-meta");
        HBox text = new HBox(10, name, meta);
        text.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button open = new Button("Open in another program");
        open.getStyleClass().addAll("button-secondary", "button-small");
        open.setOnAction(e -> FileActions.open(file));
        HBox bar = new HBox(12, text, open);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 24, 12, 24));
        bar.getStyleClass().add("preview-bar");
        return bar;
    }
}
