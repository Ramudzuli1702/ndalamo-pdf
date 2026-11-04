package com.ndalamo.screens;

import com.ndalamo.models.FileInfo;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.SaveTo;
import com.ndalamo.services.FileInspector;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Step 1 of every tool: choose the files and options (mockup: Tool.dc.html).
 * The same screen works for every tool — the options panel is built from the
 * tool's optionFields(), so tools never build UI themselves.
 */
public class ToolScreen extends BorderPane {

    /** What the screen hands over when the user presses the run button. */
    public interface RunRequest {
        void run(List<Path> files, Map<String, Object> options, SaveTo saveTo);
    }

    private final PdfTool tool;
    private final MainWindow window;
    private final RunRequest onRun;
    private final FileInspector inspector = new FileInspector();

    private final List<Path> files = new ArrayList<>();
    private final Map<Path, FileInfo> info = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Object>> optionValues = new LinkedHashMap<>();
    private SaveTo saveTo;

    private final Label summary = new Label();
    private final Label dragHint = new Label("Drag to reorder");
    private final VBox fileList = new VBox(12);
    private final Label saveToText = new Label();
    private final Label problem = new Label();
    private final Button runButton = new Button();

    public ToolScreen(PdfTool tool, List<Path> startingFiles, SaveTo saveTo, MainWindow window,
                      Runnable onHome, RunRequest onRun) {
        this.tool = tool;
        this.window = window;
        this.saveTo = saveTo;
        this.onRun = onRun;

        ScreenHeader header = new ScreenHeader(tool.name(), onHome);
        Label step = new Label("Step 1 of 2 · Files and options");
        step.getStyleClass().add("step-label");
        header.getChildren().add(step);
        setTop(header);

        setCenter(filesPanel());
        setRight(optionsPanel());
        addFiles(startingFiles);
    }

    public PdfTool getTool() {
        return tool;
    }

    /** Adds files from the Add button or dropped on the window, skipping types this tool can't use. */
    public void addFiles(List<Path> newFiles) {
        List<String> skipped = new ArrayList<>();
        for (Path file : newFiles) {
            if (!tool.fileTypes().contains(FileInspector.extension(file))) {
                skipped.add(file.getFileName().toString());
            } else if (!files.contains(file)) {
                files.add(file);
                inspectInBackground(file);
            }
        }
        if (!skipped.isEmpty()) {
            window.toast(Toast.info("Some files were left out",
                    tool.name() + " can't use " + String.join(", ", skipped) + ". Your files were not changed."));
        }
        refresh();
    }

    // ── Left: the files ──────────────────────────────────────────────────

    private ScrollPane filesPanel() {
        summary.getStyleClass().add("section-title");
        dragHint.getStyleClass().add("muted");
        dragHint.setStyle("-fx-font-size: 13px;");
        Button add = new Button("+ Add files");
        add.getStyleClass().add("button-secondary");
        add.setOnAction(e -> chooseFiles());
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox top = new HBox(12, summary, dragHint, push, add);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox main = new VBox(12, top, fileList);
        main.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        return scroll;
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add files to " + tool.name());
        List<String> patterns = tool.fileTypes().stream().sorted().map(ext -> "*." + ext).toList();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Files " + tool.name() + " can use", patterns));
        List<File> chosen = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (chosen != null) addFiles(chosen.stream().map(File::toPath).toList());
    }

    private void inspectInBackground(Path file) {
        CompletableFuture.supplyAsync(() -> inspector.inspect(file))
                .thenAccept(result -> Platform.runLater(() -> {
                    info.put(file, result);
                    refresh();
                }));
    }

    /** Rebuilds the file list, the summary line and the run button after any change. */
    private void refresh() {
        long total = files.stream().mapToLong(f -> info.containsKey(f) ? info.get(f).getSize() : 0).sum();
        summary.setText(Format.count(files.size(), "file", "files") + (total > 0 ? " · " + Format.size(total) + " total" : ""));
        dragHint.setVisible(files.size() > 1);

        fileList.getChildren().clear();
        if (files.isEmpty()) {
            Label empty = new Label("Add the files you want to use, or drop them anywhere on this window.");
            empty.getStyleClass().add("muted");
            empty.setWrapText(true);
            VBox card = new VBox(empty);
            card.getStyleClass().add("card");
            fileList.getChildren().add(card);
        }
        for (int i = 0; i < files.size(); i++) {
            fileList.getChildren().add(fileRow(i));
        }

        int needed = tool.minFiles();
        runButton.setText(tool.buttonLabel(files.size()));
        if (files.size() < needed) {
            problem.setText("Add at least " + Format.count(needed, "file", "files") + ".");
        } else {
            problem.setText("");
        }
        problem.setManaged(!problem.getText().isEmpty());
        problem.setVisible(!problem.getText().isEmpty());
        runButton.setDisable(files.size() < needed);
        saveToText.setText(saveTo.describeFolder(tool.oneOutput()));
    }

    private HBox fileRow(int index) {
        Path file = files.get(index);
        FileInfo fileInfo = info.get(file);

        Region thumbnail = new Region();          // page-shaped placeholder; real thumbnails come with the page workspace
        thumbnail.getStyleClass().add("thumbnail");
        thumbnail.setMinSize(36, 51);
        thumbnail.setMaxSize(36, 51);

        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("file-name");
        Label meta = new Label(describe(fileInfo));
        meta.getStyleClass().add("file-meta");
        if (fileInfo != null && (fileInfo.isPasswordProtected() || fileInfo.isDamaged())) {
            meta.setStyle("-fx-text-fill: " + Theme.DANGER + ";");
        }
        VBox text = new VBox(2, name, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button remove = new Button();
        remove.setGraphic(Icons.close(18, Theme.TEXT_MUTED));
        remove.getStyleClass().add("icon-only");
        remove.setAccessibleText("Remove " + file.getFileName());
        remove.setOnAction(e -> {
            files.remove(file);
            refresh();
        });

        HBox row = new HBox(14, Icons.dragHandle(16, "#8A909C"), thumbnail, text, remove);
        row.getStyleClass().add("file-row");
        row.setAlignment(Pos.CENTER_LEFT);
        makeDraggable(row, index);
        return row;
    }

    private static String describe(FileInfo fileInfo) {
        if (fileInfo == null) return "Checking…";
        String size = Format.size(fileInfo.getSize());
        if (fileInfo.isPasswordProtected()) return "Needs a password · " + size;
        if (fileInfo.isDamaged()) return "Can't be read — it may be damaged · " + size;
        if (fileInfo.getPages() > 0) return Format.count(fileInfo.getPages(), "page", "pages") + " · " + size;
        return size;
    }

    /** Drag a row onto another row to move it there. */
    private void makeDraggable(HBox row, int index) {
        row.setOnDragDetected(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(index));
            row.startDragAndDrop(TransferMode.MOVE).setContent(content);
            e.consume();
        });
        row.setOnDragOver(e -> {
            if (e.getGestureSource() instanceof HBox && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        row.setOnDragEntered(e -> {
            if (e.getGestureSource() != row && e.getDragboard().hasString()) row.getStyleClass().add("drop-target");
        });
        row.setOnDragExited(e -> row.getStyleClass().remove("drop-target"));
        row.setOnDragDropped(e -> {
            int from = Integer.parseInt(e.getDragboard().getString());
            files.add(index, files.remove(from));
            e.setDropCompleted(true);
            e.consume();
            refresh();
        });
    }

    // ── Right: options, save location, run ───────────────────────────────

    private VBox optionsPanel() {
        VBox panel = new VBox(22);
        panel.getStyleClass().add("options-panel");
        panel.setPrefWidth(340);
        panel.setMinWidth(320);
        panel.setMaxWidth(380);

        VBox basic = new VBox(14);
        VBox advanced = new VBox(10);
        for (OptionField field : tool.optionFields()) {
            (field.isAdvanced() ? advanced : basic).getChildren().add(optionControl(field));
        }
        if (tool.optionFields().isEmpty()) {
            Label none = new Label(tool.oneOutput()
                    ? "Nothing to set — files are joined in the order shown on the left."
                    : "This tool has no options.");
            none.getStyleClass().add("muted");
            none.setWrapText(true);
            basic.getChildren().add(none);
        }
        panel.getChildren().add(basic);
        if (!advanced.getChildren().isEmpty()) {
            TitledPane more = new TitledPane("Advanced options", advanced);
            more.setExpanded(false);
            more.getStyleClass().add("details-pane");
            panel.getChildren().add(more);
        }

        // Save to
        Label saveLabel = new Label("Save to");
        saveLabel.getStyleClass().add("small-heading");
        saveToText.getStyleClass().add("small-muted");
        saveToText.setWrapText(true);
        Label namePattern = new Label(saveTo.describeName(tool.outputSuffix(), "pdf"));
        namePattern.getStyleClass().addAll("small-muted", "mono");
        Hyperlink change = new Hyperlink("Change");
        change.getStyleClass().add("link");
        change.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Save results to");
            File folder = chooser.showDialog(getScene().getWindow());
            if (folder != null) {
                saveTo = saveTo.inFolder(folder.toPath());
                refresh();
            }
        });
        VBox saveSection = new VBox(6, saveLabel, saveToText, namePattern, change);
        saveSection.getStyleClass().add("save-section");

        Region push = new Region();
        VBox.setVgrow(push, Priority.ALWAYS);

        problem.getStyleClass().add("small-muted");
        runButton.getStyleClass().addAll("button-primary", "run-button");
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDefaultButton(true);
        runButton.setOnAction(e -> {
            Map<String, Object> options = new LinkedHashMap<>();
            optionValues.forEach((id, value) -> options.put(id, value.get()));
            onRun.run(List.copyOf(files), options, saveTo);
        });

        panel.getChildren().addAll(saveSection, push, problem, runButton);
        return panel;
    }

    /** One control per option, chosen by its type. Remembers how to read the value back. */
    private VBox optionControl(OptionField field) {
        Label label = new Label(field.getLabel());
        label.getStyleClass().add("small-heading");
        VBox box = new VBox(8);

        switch (field.getType()) {
            case CHECKBOX -> {
                CheckBox check = new CheckBox(field.getLabel());
                check.setSelected(Boolean.TRUE.equals(field.getDefaultValue()));
                optionValues.put(field.getId(), check::isSelected);
                box.getChildren().add(check);
            }
            case CHOICE -> {
                ToggleGroup group = new ToggleGroup();
                box.getChildren().add(label);
                for (String choice : field.getChoices()) {
                    RadioButton radio = new RadioButton(choice);
                    radio.setToggleGroup(group);
                    radio.setUserData(choice);
                    radio.setSelected(choice.equals(field.getDefaultValue()));
                    box.getChildren().add(radio);
                }
                optionValues.put(field.getId(),
                        () -> group.getSelectedToggle() == null ? null : group.getSelectedToggle().getUserData());
            }
            case NUMBER -> {
                TextField number = new TextField(field.getDefaultValue() == null ? "" : field.getDefaultValue().toString());
                number.setMaxWidth(100);
                optionValues.put(field.getId(), () -> parseNumber(number.getText()));
                box.getChildren().addAll(label, number);
            }
            case PASSWORD -> {
                PasswordField password = new PasswordField();
                optionValues.put(field.getId(), password::getText);
                box.getChildren().addAll(label, password);
            }
            case TEXT -> {
                TextField text = new TextField(field.getDefaultValue() == null ? "" : field.getDefaultValue().toString());
                optionValues.put(field.getId(), text::getText);
                box.getChildren().addAll(label, text);
            }
            case FILE -> {
                Label chosen = new Label("No file chosen");
                chosen.getStyleClass().add("small-muted");
                Path[] picked = new Path[1];
                Button pick = new Button("Choose…");
                pick.getStyleClass().add("button-secondary");
                pick.setOnAction(e -> {
                    File file = new FileChooser().showOpenDialog(getScene().getWindow());
                    if (file != null) {
                        picked[0] = file.toPath();
                        chosen.setText(file.getName());
                    }
                });
                optionValues.put(field.getId(), () -> picked[0]);
                box.getChildren().addAll(label, new HBox(8, pick, chosen));
            }
        }
        return box;
    }

    private static Double parseNumber(String text) {
        try {
            return Double.parseDouble(text.strip().replace(',', '.'));   // accept "2,5" as well as "2.5"
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
