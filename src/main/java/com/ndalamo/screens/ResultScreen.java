package com.ndalamo.screens;

import com.ndalamo.models.FileResult;
import com.ndalamo.services.FileInspector;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The tool has finished (mockup: Result.dc.html): a headline, one row per output
 * with Open, "Show in folder", and chips to carry the results straight into
 * another tool.
 */
public class ResultScreen extends BorderPane {

    /**
     * @param inputCount how many files went in (Merge turns 3 into 1)
     * @param onContinue opens another tool with these outputs already added
     */
    private final MainWindow window;

    public ResultScreen(PdfTool tool, List<FileResult> results, int inputCount, MainWindow window, Runnable onHome,
                        BiConsumer<PdfTool, List<Path>> onContinue) {
        this.window = window;
        ScreenHeader header = new ScreenHeader(tool.name(), onHome);
        Label step = new Label("Done");
        step.getStyleClass().add("step-label");
        header.getChildren().add(step);
        setTop(header);

        List<Path> outputs = results.stream().map(FileResult::getOutput).filter(p -> p != null).toList();
        boolean anyPartial = results.stream().anyMatch(r -> r.getStatus() == FileResult.Status.PARTIAL);

        VBox content = new VBox(22, headline(tool, results, outputs, inputCount, anyPartial), resultList(results));
        List<PdfTool> next = nextTools(tool, outputs);
        if (!next.isEmpty()) content.getChildren().add(continueSection(next, outputs, onContinue));
        // "Save as pipeline" banner arrives with pipelines (chunk 16).

        content.setMaxWidth(820);
        content.setPadding(new Insets(40, 24, 40, 24));
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
    }

    private HBox headline(PdfTool tool, List<FileResult> results, List<Path> outputs, int inputCount, boolean partial) {
        boolean nothingSaved = outputs.isEmpty();
        StackPane icon = new StackPane(nothingSaved ? Icons.cross(24, Theme.DANGER)
                : partial ? Icons.warning(24, Theme.WARNING) : Icons.check(24, Theme.SUCCESS));
        icon.setMinSize(52, 52);
        icon.setMaxSize(52, 52);
        icon.setStyle("-fx-background-radius: 999; -fx-background-color: "
                + (nothingSaved ? Theme.DANGER_SOFT : partial ? Theme.WARNING_SOFT : Theme.SUCCESS_SOFT) + ";");

        long before = results.stream().mapToLong(FileResult::getSizeBefore).sum();
        long after = results.stream().mapToLong(FileResult::getSizeAfter).sum();
        String subtitle;
        if (nothingSaved) {
            subtitle = "Nothing was saved. Your original files were not changed.";
        } else if (outputs.size() != inputCount) {
            // Merge (3 → 1) and Split (1 → 6): sizes before/after don't compare, so count files instead.
            // Name the kind of file made: "3 JPGs", "1 PDF", "1 ZIP".
            String kind = FileInspector.extension(outputs.get(0)).toUpperCase(Locale.ROOT);
            subtitle = Format.count(inputCount, "file", "files") + " → " + Format.count(outputs.size(), kind, kind + "s")
                    + " · " + Format.size(after);
        } else {
            String saving = Format.saving(before, after);
            subtitle = Format.size(before) + " → " + Format.size(after) + (saving.isEmpty() ? "" : " · " + saving);
        }

        Label title = new Label(nothingSaved ? "Couldn’t finish " + tool.name() : tool.doneLabel(inputCount));
        title.getStyleClass().add("page-title");
        Label sub = new Label(subtitle);
        sub.getStyleClass().add("muted");
        VBox text = new VBox(2, title, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(16, icon, text);
        row.setAlignment(Pos.CENTER_LEFT);
        if (!nothingSaved) {
            Button show = new Button("Show in folder");
            show.getStyleClass().add("button-primary");
            show.setMinWidth(Region.USE_PREF_SIZE);
            show.setOnAction(e -> MessageDialog.ifFileExists(window, outputs.get(0),
                    () -> FileActions.showInFolder(outputs.get(0))));
            row.getChildren().add(show);
        }
        return row;
    }

    private VBox resultList(List<FileResult> results) {
        VBox list = new VBox();
        list.getStyleClass().add("result-list");
        for (FileResult result : results) {
            Path shown = result.getOutput() != null ? result.getOutput() : result.getInput();
            Label name = new Label(shown.getFileName().toString());
            name.getStyleClass().add("file-name");
            Label note = new Label(result.getNote() == null ? "" : result.getNote());
            note.setWrapText(true);
            note.setStyle("-fx-font-size: 12px; -fx-text-fill: " + switch (result.getStatus()) {
                case SUCCESS -> Theme.SUCCESS;
                case PARTIAL -> Theme.WARNING;
                case FAILED, SKIPPED -> Theme.DANGER;
            } + ";");
            VBox text = new VBox(2, name, note);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(12, text);
            row.getStyleClass().add("result-row");
            row.setAlignment(Pos.CENTER_LEFT);
            if (result.getOutput() != null) {
                Label sizes = new Label(result.getSizeBefore() > 0
                        ? Format.sizeChange(result.getSizeBefore(), result.getSizeAfter())
                        : Format.size(result.getSizeAfter()));
                sizes.setMinWidth(Region.USE_PREF_SIZE);
                Button open = new Button("Open");
                open.getStyleClass().addAll("button-secondary", "button-small");
                open.setMinWidth(Region.USE_PREF_SIZE);
                open.setOnAction(e -> MessageDialog.ifFileExists(window, result.getOutput(),
                        () -> FileActions.open(result.getOutput())));
                row.getChildren().addAll(sizes, open);
            }
            list.getChildren().add(row);
        }
        return list;
    }

    /**
     * Built tools that can take these outputs next: after PDF to JPG that's Image to PDF,
     * after Compress it's the PDF tools. Merge needs at least two files, and so on.
     */
    private static List<PdfTool> nextTools(PdfTool current, List<Path> outputs) {
        if (outputs.isEmpty()) return List.of();
        String type = FileInspector.extension(outputs.get(0));
        return Tools.all().stream()
                .filter(t -> t.isReady() && t != current && t.fileTypes().contains(type) && t.minFiles() <= outputs.size())
                .toList();
    }

    private VBox continueSection(List<PdfTool> next, List<Path> outputs, BiConsumer<PdfTool, List<Path>> onContinue) {
        Label title = new Label("Continue with these files");
        title.getStyleClass().add("section-title");
        FlowPane chips = new FlowPane(8, 8);
        for (PdfTool tool : next) {
            Button chip = new Button(tool.name());
            chip.setMnemonicParsing(false);
            chip.getStyleClass().add("chip");
            chip.setOnAction(e -> onContinue.accept(tool, outputs));
            chips.getChildren().add(chip);
        }
        return new VBox(10, title, chips);
    }
}
