package com.ndalamo.screens;

import com.ndalamo.services.FileInspector;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Screen 3, "What do you want to do?" (mockup: docs/ui/screens/DropSuggest.dc.html): shown
 * when files are dropped on Home or chosen there. A card sums up the files (thumbnail, pages,
 * size, traits such as "scanned"), then one recommended tool and a few others, each with the
 * reason it fits. The files are looked at off the JavaFX thread; nothing is changed.
 */
public class SuggestScreen extends BorderPane {

    private final BiConsumer<PdfTool, List<Path>> onOpenTool;
    private final Runnable onSeeAllTools;
    private final FileInspector inspector = new FileInspector();
    private final VBox content = new VBox(24);
    private final List<Path> files = new ArrayList<>();

    /**
     * @param onOpenTool    opens a tool with the files (only the ones it accepts are passed on by the caller)
     * @param onSeeAllTools goes to Home, where every tool is listed
     */
    public SuggestScreen(MainWindow window, List<Path> dropped, Runnable onHome,
                         BiConsumer<PdfTool, List<Path>> onOpenTool, Runnable onSeeAllTools) {
        this.onOpenTool = onOpenTool;
        this.onSeeAllTools = onSeeAllTools;
        setTop(new ScreenHeader("What do you want to do?", onHome));
        content.setPadding(new Insets(32, 24, 32, 24));
        content.setMaxWidth(880);
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
        addFiles(dropped);
    }

    /** Adds files (dropped again, or from "Add more files") and looks at the whole set afresh. */
    public void addFiles(List<Path> more) {
        LinkedHashSet<Path> all = new LinkedHashSet<>(files);
        all.addAll(more);
        files.clear();
        files.addAll(all);
        List<Path> snapshot = List.copyOf(files);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(28, 28);
        Label looking = new Label(snapshot.size() == 1 ? "Looking at " + snapshot.get(0).getFileName() + "…"
                : "Looking at " + snapshot.size() + " files…");
        looking.getStyleClass().add("small-muted");
        HBox waiting = new HBox(12, spinner, looking);
        waiting.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().setAll(waiting);
        CompletableFuture.supplyAsync(() -> snapshot.stream().map(inspector::look).toList())
                .thenAccept(looks -> Platform.runLater(() -> {
                    if (snapshot.equals(files)) show(looks);    // ignore an older look if more files came meanwhile
                }));
    }

    private void show(List<FileInspector.Look> looks) {
        content.getChildren().setAll(summary(looks));
        List<FileInspector.Suggestion> suggestions = FileInspector.suggest(looks);
        if (suggestions.isEmpty()) {
            Label none = new Label("Ndalamo works with PDFs, pictures (JPG, PNG, TIFF…), Office documents and Markdown files. "
                                   + "None of these is one of those, so there's nothing to suggest. Your files haven't been changed.");
            none.setWrapText(true);
            none.getStyleClass().add("muted");
            content.getChildren().add(none);
        } else {
            Label heading = new Label(looks.size() == 1 ? "Suggested for this file" : "Suggested for these files");
            heading.getStyleClass().add("section-title");
            VBox list = new VBox(10, heading, option(suggestions.get(0), true));
            FlowPane others = new FlowPane(10, 10);
            for (FileInspector.Suggestion s : suggestions.subList(1, suggestions.size())) {
                Node card = option(s, false);
                others.getChildren().add(card);
            }
            // Two cards to a row, filling the width (like the mockup's grid).
            others.widthProperty().addListener((obs, was, now) -> others.getChildren().forEach(n ->
                    ((VBox) n).setPrefWidth((now.doubleValue() - 10) / 2)));
            if (!others.getChildren().isEmpty()) list.getChildren().add(others);
            content.getChildren().add(list);
        }
        Hyperlink all = new Hyperlink("See all tools");
        all.setOnAction(e -> onSeeAllTools.run());
        content.getChildren().add(all);
    }

    /** The card at the top: a picture of the first file, the name(s), and what was found. */
    private Node summary(List<FileInspector.Look> looks) {
        String title = looks.size() == 1 ? looks.get(0).file().getFileName().toString() : looks.size() + " files";
        Label name = new Label(title);
        name.getStyleClass().add("semibold");
        name.setStyle("-fx-font-size: 17px;");
        Label details = new Label(FileInspector.describe(looks));
        details.getStyleClass().add("small-muted");
        details.setWrapText(true);
        VBox text = new VBox(4, name, details);
        if (looks.size() > 1) {
            Label names = new Label(String.join(", ", looks.stream().map(l -> l.file().getFileName().toString()).toList()));
            names.getStyleClass().add("small-muted");
            names.setWrapText(true);
            text.getChildren().add(names);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        Button addMore = new Button("Add more files");
        addMore.getStyleClass().add("button-secondary");
        addMore.setOnAction(e -> chooseMore());
        HBox card = new HBox(18, thumbnail(looks.get(0)), text, addMore);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        return card;
    }

    /** A small picture of the file's first page (PDF) or the picture itself; a plain page shape otherwise. */
    private Node thumbnail(FileInspector.Look look) {
        StackPane frame = new StackPane();
        frame.setMinSize(64, 90);
        frame.setPrefSize(64, 90);
        frame.setMaxSize(64, 90);
        frame.getStyleClass().add("ws-page");
        ImageView view = new ImageView();
        view.setFitWidth(62);
        view.setFitHeight(88);
        view.setPreserveRatio(true);
        frame.getChildren().add(view);
        if (look.kind().equals("pdf") && look.info().getPages() > 0) {
            PageThumbnails thumbnails = new PageThumbnails(PageThumbnails.SMALL);
            thumbnails.start(look.file(), 1);
            thumbnails.whenReady(look.file(), 1, view::setImage);
        } else if (look.kind().equals("image") && FileInspector.IMAGE_EXTENSIONS.contains(FileInspector.extension(look.file()))) {
            view.setImage(new Image(look.file().toUri().toString(), 124, 176, true, true, true));
        }
        return frame;
    }

    private Node option(FileInspector.Suggestion suggestion, boolean recommended) {
        Optional<PdfTool> found = Tools.find(suggestion.toolId());
        String toolName = found.map(PdfTool::name).orElse(suggestion.toolId());
        Label name = new Label(toolName);
        name.getStyleClass().add("tool-name");
        if (recommended) name.setStyle("-fx-font-size: 15px;");
        Label reason = new Label(suggestion.reason());
        reason.getStyleClass().add("tool-desc");
        if (recommended) reason.setStyle("-fx-font-size: 13px;");
        reason.setWrapText(true);
        VBox words = new VBox(2, name, reason);
        VBox card;
        if (recommended) {
            Label badge = new Label("Recommended");
            badge.getStyleClass().add("recommended-badge");
            HBox.setHgrow(words, Priority.ALWAYS);
            HBox row = new HBox(14, words, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            card = new VBox(row);
            card.getStyleClass().addAll("tool-card", "suggest-recommended");
        } else {
            card = words;
            card.getStyleClass().add("tool-card");
        }
        card.setFocusTraversable(true);
        card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        card.setAccessibleText(toolName + (recommended ? ", recommended" : "") + ". " + suggestion.reason());
        Runnable open = () -> found.ifPresent(tool -> onOpenTool.accept(tool, List.copyOf(files)));
        card.setOnMouseClicked(e -> open.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                open.run();
                e.consume();
            }
        });
        return card;
    }

    private void chooseMore() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add more files");
        List<File> more = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (more != null && !more.isEmpty()) addFiles(more.stream().map(File::toPath).toList());
    }
}
