package com.ndalamo.screens;

import com.ndalamo.models.Pipeline;
import com.ndalamo.models.WatchFolder;
import com.ndalamo.services.WatchFolderService;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Add watch folder": the folder to watch, the pipeline to run on each file, where results
 * go, and what happens to originals afterwards. Problems (results into the watched folder
 * itself, a pipeline that asks for passwords…) are explained before it's added.
 */
public class WatchFolderDialog extends VBox {

    private final Label inputPath = new Label("No folder chosen");
    private final Label outputPath = new Label("No folder chosen");
    private final Label problem = new Label();
    private String input;
    private String output;

    /** @param chosenPipeline preselected (from the builder's "Link to a watch folder"), or null */
    public WatchFolderDialog(MainWindow window, List<Pipeline> pipelines, Pipeline chosenPipeline, List<WatchFolder> existing,
                             Consumer<WatchFolder> onAdd) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(580);
        setMaxHeight(USE_PREF_SIZE);
        Label title = new Label("Add a watch folder");
        title.getStyleClass().add("dialog-title");
        Label about = new Label("Every file put in this folder runs through the pipeline while Ndalamo is open or in the system tray.");
        about.getStyleClass().add("dialog-text");
        about.setWrapText(true);

        Button pickInput = new Button("Choose…");
        pickInput.getStyleClass().add("button-secondary");
        pickInput.setOnAction(e -> {
            File folder = chooseFolder("The folder to watch");
            if (folder == null) return;
            input = folder.getAbsolutePath();
            inputPath.setText(input);
            if (output == null) {
                // A sensible start: a "Done" folder inside it (only the folder itself is watched, not folders inside).
                output = Path.of(input, "Done").toString();
                outputPath.setText(output);
            }
        });
        Button pickOutput = new Button("Choose…");
        pickOutput.getStyleClass().add("button-secondary");
        pickOutput.setOnAction(e -> {
            File folder = chooseFolder("Where results go");
            if (folder == null) return;
            output = folder.getAbsolutePath();
            outputPath.setText(output);
        });
        inputPath.getStyleClass().add("mono-text");
        outputPath.getStyleClass().add("mono-text");

        ComboBox<Pipeline> pipeline = new ComboBox<>();
        pipeline.getItems().setAll(pipelines);
        pipeline.setConverter(new StringConverter<>() {
            @Override public String toString(Pipeline p) { return p == null ? "" : p.getName(); }
            @Override public Pipeline fromString(String s) { return null; }
        });
        pipeline.setPromptText(pipelines.isEmpty() ? "Make a pipeline first" : "Choose a pipeline");
        pipeline.setMaxWidth(Double.MAX_VALUE);
        pipeline.setAccessibleText("Pipeline to run");
        if (chosenPipeline != null) pipelines.stream().filter(p -> p.getId().equals(chosenPipeline.getId())).findFirst()
                .ifPresent(pipeline.getSelectionModel()::select);

        ToggleGroup afterwards = new ToggleGroup();
        RadioButton move = new RadioButton("Move them to a “processed” folder inside the watched folder");
        RadioButton leave = new RadioButton("Leave them where they are");
        move.setToggleGroup(afterwards);
        leave.setToggleGroup(afterwards);
        move.setSelected(true);

        problem.getStyleClass().add("status-error");
        problem.setWrapText(true);
        problem.setMinHeight(Region.USE_PREF_SIZE);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button add = new Button("Add watch folder");
        add.getStyleClass().addAll("button-primary", "button-dialog");
        add.setDefaultButton(true);
        add.setOnAction(e -> {
            Pipeline chosen = pipeline.getValue();
            WatchFolder folder = new WatchFolder(input, chosen == null ? null : chosen.getId(), output);
            folder.setAfterwards(leave.isSelected() ? WatchFolder.Afterwards.LEAVE : WatchFolder.Afterwards.MOVE_TO_PROCESSED);
            String wrong = WatchFolderService.check(folder, pipelines, existing);
            if (wrong != null) {
                problem.setText(wrong);
                return;
            }
            window.closeDialog();
            onAdd.accept(folder);
        });
        HBox buttons = new HBox(8, cancel, add);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(title, about,
                field("Watch this folder", pickInput, inputPath),
                labelled("Run this pipeline on each file", pipeline),
                field("Put results in", pickOutput, outputPath),
                labelled("Originals, once done", new VBox(8, move, leave)),
                problem, buttons);
    }

    private static VBox field(String label, Button pick, Label path) {
        path.setWrapText(true);
        HBox.setHgrow(path, Priority.ALWAYS);
        HBox row = new HBox(10, pick, path);
        row.setAlignment(Pos.CENTER_LEFT);
        return labelled(label, row);
    }

    private static VBox labelled(String label, javafx.scene.Node content) {
        Label heading = new Label(label);
        heading.getStyleClass().add("small-heading");
        return new VBox(6, heading, content);
    }

    private File chooseFolder(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        return chooser.showDialog(getScene().getWindow());
    }
}
