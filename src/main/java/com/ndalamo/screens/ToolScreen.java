package com.ndalamo.screens;

import com.ndalamo.models.FileInfo;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import java.nio.file.Files;
import com.ndalamo.models.Signature;
import com.ndalamo.models.WorkspacePage;
import com.ndalamo.services.FileInspector;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    private final Map<String, ToggleGroup> choiceGroups = new LinkedHashMap<>();
    private final Map<String, TextField> textBoxes = new LinkedHashMap<>();
    private final Map<String, OptionField> fieldsById = new LinkedHashMap<>();
    private final Map<String, VBox> choiceBodies = new LinkedHashMap<>();   // "mode=Target size" → inside of that card
    private final PageThumbnails thumbnails = new PageThumbnails();
    private final PagePreview preview;
    private SaveTo saveTo;

    private final Label summary = new Label();
    private final Label dragHint = new Label("Drag to reorder");
    private final VBox fileList = new VBox(12);
    private final Text saveToText = new Text();
    private final Label problem = new Label();
    private boolean showingMistake;
    private final Button runButton = new Button();
    private final Map<String, String> defaults;

    public ToolScreen(PdfTool tool, List<Path> startingFiles, SaveTo saveTo, MainWindow window,
                      Runnable onHome, RunRequest onRun) {
        this(tool, startingFiles, saveTo, Map.of(), window, onHome, onRun);
    }

    /** @param defaults the choices to start with, from Settings ("tool id.option id" → choice) */
    public ToolScreen(PdfTool tool, List<Path> startingFiles, SaveTo saveTo, Map<String, String> defaults, MainWindow window,
                      Runnable onHome, RunRequest onRun) {
        this.tool = tool;
        this.defaults = defaults;
        this.window = window;
        this.saveTo = saveTo;
        this.onRun = onRun;
        this.preview = tool.preview() == PdfTool.Preview.NONE ? null
                : new PagePreview(tool, thumbnails, text -> setOptionText(tool.pagesOption(), text));

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
        if (preview != null) {
            VBox.setMargin(preview, new Insets(20, 0, 0, 0));
            main.getChildren().add(preview);
        }
        main.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        return scroll;
    }

    /** The file chooser for adding files (the Add files button, or Ctrl+O). */
    public void chooseFiles() {
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
                    if (result.getPages() > 0 && !result.isPasswordProtected()) thumbnails.start(file, result.getPages());
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
            showProblem("Add at least " + Format.count(needed, "file", "files") + ".", false);
        } else if (!showingMistake) {
            // A red options mistake stays until the user changes a choice or presses run again;
            // a file finishing loading in the background mustn't wipe it.
            showProblem("", false);
        }
        runButton.setDisable(files.size() < needed);
        saveToText.setText(saveTo.describeFolder(tool.oneOutput()));
        updatePreview();
    }

    /** Redraws the page preview for the current files and options. */
    private void updatePreview() {
        if (preview != null) preview.show(files, info, currentOptions());
    }

    private Map<String, Object> currentOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        optionValues.forEach((id, value) -> options.put(id, value.get()));
        return options;
    }

    /**
     * Puts clicked pages into a page box, e.g. "2, 5-7". If the box only shows for a
     * certain choice ("Only some pages"), that choice is picked too.
     */
    private void setOptionText(String id, String text) {
        OptionField field = fieldsById.get(id);
        TextField box = textBoxes.get(id);
        if (field == null || box == null) return;
        if (field.getShownWhenId() != null) {
            ToggleGroup group = choiceGroups.get(field.getShownWhenId());
            group.getToggles().stream()
                    .filter(t -> field.getShownWhenValue().equals(t.getUserData()))
                    .findFirst().ifPresent(t -> t.setSelected(true));
        }
        box.setText(text);
    }

    /**
     * Office files have no page pictures until they're converted, so their thumbnail
     * shows the file type instead, in the colour people know it by. Null for PDFs and images.
     */
    private static Label typeBadge(String extension) {
        String colour = switch (extension) {
            case "doc", "docx", "odt", "rtf" -> "#2B579A";      // Word blue
            case "xls", "xlsx", "ods", "csv" -> "#217346";      // Excel green
            case "ppt", "pptx", "odp" -> "#C2410C";             // PowerPoint orange
            case "md", "markdown" -> "#5B616E";                 // Markdown: plain text, grey
            default -> null;
        };
        if (colour == null) return null;
        Label badge = new Label(extension.toUpperCase());
        badge.setStyle("-fx-font-family: \"IBM Plex Sans SmBld\"; -fx-font-size: 9px; -fx-text-fill: " + colour + ";");
        return badge;
    }

    private HBox fileRow(int index) {
        Path file = files.get(index);
        FileInfo fileInfo = info.get(file);

        ImageView firstPage = new ImageView();
        firstPage.setFitWidth(34);
        firstPage.setFitHeight(49);
        firstPage.setPreserveRatio(true);
        thumbnails.whenReady(file, 1, firstPage::setImage);
        StackPane thumbnail = new StackPane(firstPage);
        thumbnail.getStyleClass().add("thumbnail");
        thumbnail.setMinSize(36, 51);
        thumbnail.setMaxSize(36, 51);
        Label typeBadge = typeBadge(FileInspector.extension(file));
        if (typeBadge != null) thumbnail.getChildren().add(typeBadge);

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
        remove.setTooltip(new javafx.scene.control.Tooltip("Remove from the list (the file itself stays)"));
        remove.setOnAction(e -> {
            files.remove(file);
            refresh();
        });

        HBox row = new HBox(14, Icons.dragHandle(16, Theme.TEXT_FAINT), thumbnail, text, remove);
        row.getStyleClass().add("file-row");
        row.setAlignment(Pos.CENTER_LEFT);
        makeDraggable(row, index);
        return row;
    }

    private static String describe(FileInfo fileInfo) {
        if (fileInfo == null) return "Checking…";
        String size = Format.size(fileInfo.getSize());
        if (fileInfo.isPasswordProtected()) return "Needs a password · " + size;
        if (fileInfo.isRestricted()) {
            return "Has print/copy limits · " + Format.count(fileInfo.getPages(), "page", "pages") + " · " + size;
        }
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
        VBox panel = new VBox(18);
        panel.getStyleClass().add("options-panel");
        panel.setPrefWidth(340);
        panel.setMinWidth(320);
        panel.setMaxWidth(380);

        VBox basic = new VBox(22);
        VBox advanced = new VBox(8);
        for (OptionField field : tool.optionFields()) {
            // A follow-up input ("MB per file") goes inside the card of the choice it belongs to,
            // like the mockup's Target size card; everything else is listed in order.
            VBox insideCard = field.getShownWhenId() == null ? null
                    : choiceBodies.get(field.getShownWhenId() + "=" + field.getShownWhenValue());
            VBox control = optionControl(field, insideCard != null);
            if (insideCard != null) {
                VBox.setMargin(control, new Insets(8, 0, 0, 0));
                insideCard.getChildren().add(control);
            } else {
                (field.isAdvanced() ? advanced : basic).getChildren().add(control);
            }
            if (field.getShownWhenId() != null) showOnlyWhen(control, field);
        }
        if (tool.optionFields().isEmpty()) {
            Label none = new Label(tool.oneOutput()
                    ? "Nothing to set — files are joined in the order shown on the left."
                    : "This tool has no options.");
            none.getStyleClass().add("small-muted");
            none.setWrapText(true);
            basic.getChildren().add(none);
        }

        // Options and "Save to" scroll if the window is short; the run button stays put below them.
        VBox settings = new VBox(22, basic);
        if (!advanced.getChildren().isEmpty()) settings.getChildren().add(advancedSection(advanced));
        settings.getChildren().add(saveSection());
        settings.setPadding(new Insets(0, 4, 0, 0));    // room for the slim scrollbar

        ScrollPane scroll = new ScrollPane(settings);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("plain-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        problem.getStyleClass().add("small-muted");
        problem.setWrapText(true);
        problem.setMinHeight(Region.USE_PREF_SIZE);   // never squeeze the message away
        runButton.getStyleClass().addAll("button-primary", "run-button");
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDefaultButton(true);
        runButton.setOnAction(e -> {
            Map<String, Object> options = currentOptions();
            if (preview != null && preview.mergeOrder() != null) options.put(PdfTool.PAGE_ORDER, preview.mergeOrder());
            // Catch typing mistakes (like "1-x") here, before anything runs.
            String optionProblem = tool.checkOptions(options);
            if (optionProblem != null) {
                showProblem(optionProblem, true);
                return;
            }
            if (!tool.needsPasswords()) {
                start(List.copyOf(files), options);
                return;
            }
            // Unlock: ask for each locked file's password first, one dialog at a time.
            List<Path> locked = files.stream().filter(f -> info.containsKey(f)
                    && (info.get(f).isPasswordProtected() || info.get(f).isRestricted())).toList();
            Map<Path, String> passwords = new LinkedHashMap<>();
            List<Path> skipped = new ArrayList<>();
            askPasswords(new ArrayDeque<>(locked), passwords, skipped, () -> {
                List<Path> toRun = files.stream().filter(f -> !skipped.contains(f)).toList();
                if (toRun.isEmpty()) {
                    showProblem("Every file was skipped, so there's nothing to unlock.", true);
                    return;
                }
                options.put(PdfTool.PASSWORDS, passwords);
                start(toRun, options);
            });
        });

        runButton.setMinHeight(Region.USE_PREF_SIZE);
        panel.getChildren().addAll(scroll, problem, runButton);
        return panel;
    }

    /**
     * Starts the run, first asking what Settings say to ask: which folder ("Ask every
     * time"), and, if results with those names are already there, whether to replace
     * them or add a number ("Ask me").
     */
    private void start(List<Path> toRun, Map<String, Object> options) {
        SaveTo target = saveTo;
        if (target.asksForFolder()) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Where should the results go?");
            File startIn = toRun.get(0).toAbsolutePath().getParent().toFile();
            if (startIn.isDirectory()) chooser.setInitialDirectory(startIn);
            File folder = chooser.showDialog(getScene().getWindow());
            if (folder == null) return;                              // cancelled: nothing runs
            target = target.inFolder(folder.toPath());
        }
        if (target.asksIfExists()) {
            SaveTo chosen = target;
            List<Path> outputs = (tool.oneOutput() ? toRun.subList(0, 1) : toRun).stream()
                    .map(input -> chosen.plainOutputFor(input, tool.outputSuffix(), tool.outputExtension())).toList();
            List<String> taken = outputs.stream().filter(Files::exists).map(p -> p.getFileName().toString()).toList();
            if (!taken.isEmpty()) {
                String names = String.join(", ", taken.subList(0, Math.min(3, taken.size())))
                        + (taken.size() > 3 ? " and " + (taken.size() - 3) + " more" : "");
                new ChoiceDialog(window, taken.size() == 1 ? names + " already exists" : taken.size() + " results already exist",
                        names + (taken.size() == 1 ? " is" : " are") + " already there. Replace "
                                + (taken.size() == 1 ? "it" : "them") + ", or keep "
                                + (taken.size() == 1 ? "it" : "them") + " and add a number to the new name? Your original files are never replaced.",
                        null)
                        .button("Cancel", ChoiceDialog.Style.PLAIN, x -> {})
                        .button("Add a number", ChoiceDialog.Style.PLAIN,
                                x -> onRun.run(toRun, options, chosen.withIfExists(Settings.IfExists.ADD_NUMBER)))
                        .button(taken.size() == 1 ? "Replace it" : "Replace them", ChoiceDialog.Style.DANGER,
                                x -> onRun.run(toRun, options, chosen.withIfExists(Settings.IfExists.REPLACE)))
                        .show();
                return;
            }
        }
        onRun.run(toRun, options, target);
    }

    /** Shows the password dialog for each file in turn, then carries on with {@code done}. */
    private void askPasswords(Deque<Path> remaining, Map<Path, String> passwords, List<Path> skipped, Runnable done) {
        Path file = remaining.poll();
        if (file == null) {
            done.run();
            return;
        }
        boolean restrictedOnly = !info.get(file).isPasswordProtected();
        window.showDialog(new PasswordDialog(window, file, restrictedOnly, inspector,
                password -> {
                    passwords.put(file, password);
                    askPasswords(remaining, passwords, skipped, done);
                },
                () -> {
                    skipped.add(file);
                    askPasswords(remaining, passwords, skipped, done);
                }));
    }

    /** "Advanced options" with a small arrow; the options fold out underneath when clicked. */
    private VBox advancedSection(VBox advanced) {
        Region arrow = Icons.chevron(14, Theme.TEXT);
        Button toggle = new Button("Advanced options", arrow);
        toggle.getStyleClass().add("disclosure");
        toggle.setGraphicTextGap(6);
        advanced.setPadding(new Insets(10, 0, 0, 2));
        advanced.setVisible(false);
        advanced.setManaged(false);
        toggle.setOnAction(e -> {
            boolean open = !advanced.isVisible();
            advanced.setVisible(open);
            advanced.setManaged(open);
            arrow.setRotate(open ? 90 : 0);
        });
        return new VBox(toggle, advanced);
    }

    /** "Save to" — where results go and what they're called, on one line like the mockup — and Change. */
    private VBox saveSection() {
        Label heading = new Label("Save to");
        heading.getStyleClass().add("small-heading");

        saveToText.getStyleClass().add("save-text");
        Text pattern = new Text(saveTo.describeName(tool.outputSuffix(), tool.outputExtension()));
        pattern.getStyleClass().addAll("mono-text");
        Text separator = new Text(" · ");
        separator.getStyleClass().add("save-text");
        TextFlow where = new TextFlow(saveToText, separator, pattern);
        where.getStyleClass().add("save-line");

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

        VBox section = new VBox(6, heading, where);
        if (tool.saveToNote() != null) {
            Label note = new Label(tool.saveToNote());
            note.getStyleClass().add("small-muted");
            note.setWrapText(true);
            section.getChildren().add(note);
        }
        section.getChildren().add(change);
        section.getStyleClass().add("save-section");
        return section;
    }

    /** The line above the run button: why it can't run yet (grey) or what to fix in the options (red). */
    private void showProblem(String text, boolean isMistake) {
        showingMistake = isMistake;
        problem.setText(text);
        problem.setStyle(isMistake ? "-fx-text-fill: " + Theme.DANGER + ";" : "");
        problem.setManaged(!text.isEmpty());
        problem.setVisible(!text.isEmpty());
    }

    /** Hides a field unless its choice field has the right value, e.g. "Pages per file" only for "Every N pages". */
    private void showOnlyWhen(VBox control, OptionField field) {
        ToggleGroup group = choiceGroups.get(field.getShownWhenId());
        if (group == null) return;
        Runnable update = () -> {
            boolean show = group.getSelectedToggle() != null
                    && field.getShownWhenValue().equals(group.getSelectedToggle().getUserData());
            control.setVisible(show);
            control.setManaged(show);
            showProblem("", false);      // a new choice clears an old mistake message
        };
        group.selectedToggleProperty().addListener((obs, old, now) -> update.run());
        update.run();
    }

    /**
     * One control per option, chosen by its type. Remembers how to read the value back.
     *
     * @param insideCard true when it sits inside a choice card, where it's drawn more compactly
     */
    private VBox optionControl(OptionField field, boolean insideCard) {
        fieldsById.put(field.getId(), field);
        Label label = new Label(field.getLabel());
        label.getStyleClass().add(insideCard ? "inside-label" : "small-heading");
        VBox box = new VBox(8);

        switch (field.getType()) {
            case CHECKBOX -> {
                CheckBox check = new CheckBox(field.getLabel());
                check.setSelected(Boolean.TRUE.equals(field.getDefaultValue()));
                check.selectedProperty().addListener((obs, old, now) -> updatePreview());
                optionValues.put(field.getId(), check::isSelected);
                box.getChildren().add(check);
            }
            case CHOICE -> {
                ToggleGroup group = new ToggleGroup();
                label.getStyleClass().setAll("label", "option-legend");
                box.getChildren().add(label);
                List<HBox> cards = new ArrayList<>();
                for (int c = 0; c < field.getChoices().size(); c++) {
                    String choice = field.getChoices().get(c);
                    HBox card = choiceCard(choice, c < field.getChoiceDescriptions().size()
                            ? field.getChoiceDescriptions().get(c) : null, group);
                    choiceBodies.put(field.getId() + "=" + choice, (VBox) card.getChildren().get(1));
                    cards.add(card);
                    box.getChildren().add(card);
                }
                choiceGroups.put(field.getId(), group);
                // Outline the chosen card, like the mockup's options panel.
                group.selectedToggleProperty().addListener((obs, old, now) -> {
                    cards.forEach(card -> card.getStyleClass().remove("choice-selected"));
                    if (now != null) ((Node) now).getParent().getStyleClass().add("choice-selected");
                    updatePreview();
                });
                // Start with the choice set in Settings, if it's still one of this field's choices.
                String saved = defaults.get(tool.id() + "." + field.getId());
                Object start = saved != null && field.getChoices().contains(saved) ? saved : field.getDefaultValue();
                group.getToggles().stream()
                        .filter(t -> t.getUserData().equals(start))
                        .findFirst().ifPresent(t -> t.setSelected(true));
                optionValues.put(field.getId(),
                        () -> group.getSelectedToggle() == null ? null : group.getSelectedToggle().getUserData());
            }
            case NUMBER -> {
                TextField number = new TextField(field.getDefaultValue() == null ? "" : field.getDefaultValue().toString());
                number.setPrefWidth(72);
                number.setMaxWidth(72);
                number.setPromptText(field.getHint());
                number.setAccessibleText(field.getLabel());
                number.textProperty().addListener((obs, old, now) -> updatePreview());
                optionValues.put(field.getId(), () -> parseNumber(number.getText()));
                if (insideCard) {
                    // "[ 2 ] MB per file", on one line as in the mockup
                    HBox row = new HBox(8, number, label);
                    row.setAlignment(Pos.CENTER_LEFT);
                    box.getChildren().add(row);
                } else {
                    box.getChildren().addAll(label, number);
                }
            }
            case PASSWORD -> {
                PasswordField password = new PasswordField();
                password.setAccessibleText(field.getLabel());
                optionValues.put(field.getId(), password::getText);
                box.getChildren().addAll(label, password);
            }
            case TEXT -> {
                TextField text = new TextField(field.getDefaultValue() == null ? "" : field.getDefaultValue().toString());
                text.setPromptText(field.getHint());
                text.setAccessibleText(field.getLabel());
                textBoxes.put(field.getId(), text);
                text.textProperty().addListener((obs, old, now) -> updatePreview());
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
                        updatePreview();
                    }
                });
                optionValues.put(field.getId(), () -> picked[0]);
                HBox row = new HBox(8, pick, chosen);
                row.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().addAll(label, row);
            }
            case AREA -> {
                Label chosen = new Label("Not drawn yet");
                chosen.getStyleClass().add("small-muted");
                double[][] area = new double[1][];
                Button draw = new Button("Draw the area…");
                draw.getStyleClass().add("button-secondary");
                draw.setOnAction(e -> drawArea(shown -> {
                    area[0] = shown;
                    chosen.setText(shown == null ? "Not drawn yet" : String.format("Drawn: %d%% × %d%% of the page",
                            Math.round((shown[2] - shown[0]) * 100), Math.round((shown[3] - shown[1]) * 100)));
                    draw.setText(shown == null ? "Draw the area…" : "Change the area…");
                    refresh();
                }));
                optionValues.put(field.getId(), () -> area[0]);
                HBox row = new HBox(8, draw, chosen);
                row.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().addAll(label, row);
            }
            case FORM -> {
                Label chosen = new Label("Not filled in yet");
                chosen.getStyleClass().add("small-muted");
                chosen.setWrapText(true);
                @SuppressWarnings("unchecked") Map<String, String>[] answers = new Map[1];
                Button fill = new Button("Fill in the form…");
                fill.getStyleClass().add("button-secondary");
                fill.setOnAction(e -> fillForm(answers[0], given -> {
                    answers[0] = given;
                    long answered = given.values().stream().filter(v -> !v.isBlank() && !v.equals("false")).count();
                    chosen.setText(answered + " of " + given.size() + " answered");
                    fill.setText("Change the answers…");
                    refresh();
                }));
                optionValues.put(field.getId(), () -> answers[0]);
                VBox column = new VBox(6, fill, chosen);
                box.getChildren().addAll(label, column);
            }
            case SIGNATURE -> {
                Label chosen = new Label("Not made yet");
                chosen.getStyleClass().add("small-muted");
                Signature[] signature = new Signature[1];
                Button create = new Button("Create your signature…");
                create.getStyleClass().add("button-secondary");
                Button move = new Button("Move it…");
                move.getStyleClass().add("button-secondary");
                move.setVisible(false);
                move.setManaged(false);
                Consumer<Signature> placed = s -> {
                    signature[0] = s;
                    chosen.setText("On page " + s.page());
                    create.setText("Make a new one…");
                    move.setVisible(true);
                    move.setManaged(true);
                    refresh();
                };
                create.setOnAction(e -> {
                    if (files.isEmpty()) {
                        window.toast(Toast.info("Add a file first", "The signature is placed on a page of the first file."));
                        return;
                    }
                    window.showDialog(new SignatureDialog(window, png -> placeSignature(png, signature[0], placed)));
                });
                move.setOnAction(e -> {
                    if (signature[0] != null && !files.isEmpty()) placeSignature(signature[0].png(), signature[0], placed);
                });
                optionValues.put(field.getId(), () -> signature[0]);
                HBox buttons = new HBox(8, create, move);
                box.getChildren().addAll(label, buttons, chosen);
            }
            case EDITS -> {
                Label chosen = new Label("No changes yet");
                chosen.getStyleClass().add("small-muted");
                com.ndalamo.models.PdfEdits[] edits = new com.ndalamo.models.PdfEdits[1];
                Button open = new Button("Open the editor…");
                open.getStyleClass().add("button-primary");
                open.setOnAction(e -> {
                    if (files.isEmpty()) {
                        window.toast(Toast.info("Add a file first", "The changes are made on the first file."));
                        return;
                    }
                    Path first = files.get(0);
                    int pages = info.containsKey(first) ? Math.max(1, info.get(first).getPages()) : 1;
                    window.showDialog(new EditDialog(window, thumbnails, first, pages, edits[0], done -> {
                        edits[0] = done.isEmpty() ? null : done;
                        chosen.setText(done.isEmpty() ? "No changes yet" : done.describe()
                                + (files.size() > 1 ? " · the same changes go on every file" : ""));
                        open.setText(done.isEmpty() ? "Open the editor…" : "Keep editing…");
                        refresh();
                    }));
                });
                optionValues.put(field.getId(), () -> edits[0]);
                box.getChildren().addAll(label, open, chosen);
            }
        }
        return box;
    }

    /** Opens the place dialog on the first file, starting where the signature was before (if anywhere). */
    private void placeSignature(byte[] png, Signature before, Consumer<Signature> onPlaced) {
        Path first = files.get(0);
        int pages = info.containsKey(first) ? Math.max(1, info.get(first).getPages()) : 1;
        window.showDialog(new PlaceSignatureDialog(window, thumbnails, first, pages, before, png, onPlaced));
    }

    /**
     * Reads the first file's form fields (off the JavaFX thread: it opens the PDF) and
     * shows them to fill in. Answers given before are kept when the form is reopened.
     */
    private void fillForm(Map<String, String> previous, java.util.function.Consumer<Map<String, String>> onFilled) {
        if (files.isEmpty()) {
            window.toast(Toast.info("Add a form first", "The form's fields are read from the first file."));
            return;
        }
        Path first = files.get(0);
        CompletableFuture.supplyAsync(() -> {
            try {
                return com.ndalamo.tools.FillFormsTool.fields(first);
            } catch (java.io.IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((fields, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                window.showDialog(new MessageDialog(window, "Can't fill in " + first.getFileName(), cause.getMessage()));
            } else if (fields.isEmpty()) {
                window.toast(Toast.info(first.getFileName() + " has no form",
                        "There are no fields to fill in. Your file was not changed."));
            } else {
                window.showDialog(new FormDialog(window, first.getFileName().toString(), fields,
                        previous == null ? Map.of() : previous, onFilled));
            }
        }));
    }

    /**
     * Opens the crop dialog on the first page of the first file. The box drawn comes back
     * as fractions of the page as shown, so the tool can apply it to pages turned any way.
     * Reading the page's turn opens the PDF, so that happens off the JavaFX thread.
     */
    private void drawArea(java.util.function.Consumer<double[]> onDrawn) {
        if (files.isEmpty()) {
            window.toast(Toast.info("Add a file first", "The area is drawn on the first page of the first file."));
            return;
        }
        Path first = files.get(0);
        CompletableFuture.supplyAsync(() -> {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(first.toFile())) {
                return doc.getPage(0).getRotation();
            } catch (java.io.IOException e) {
                return -1;
            }
        }).thenAccept(rotation -> Platform.runLater(() -> {
            if (rotation < 0) {
                window.toast(Toast.error("Couldn't open " + first.getFileName(), "Your file was not changed."));
                return;
            }
            WorkspacePage page = WorkspacePage.of(first, 1, rotation);
            int pages = info.containsKey(first) ? Math.max(1, info.get(first).getPages()) : 1;
            window.showDialog(new CropDialog(window, thumbnails, page, pages,
                    crop -> onDrawn.accept(crop == null ? null : crop.toShown(rotation))));
        }));
    }

    /** One choice as a bordered card: a radio button, the choice and (optionally) a line explaining it. */
    private static HBox choiceCard(String choice, String description, ToggleGroup group) {
        RadioButton radio = new RadioButton();
        radio.setToggleGroup(group);
        radio.setUserData(choice);
        radio.setAccessibleText(choice);
        HBox.setMargin(radio, new Insets(1, 0, 0, 0));
        Label title = new Label(choice);
        title.getStyleClass().add("choice-title");
        VBox text = new VBox(2, title);
        if (description != null && !description.isBlank()) {
            Label line = new Label(description);
            line.getStyleClass().add("file-meta");
            line.setWrapText(true);
            text.getChildren().add(line);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox card = new HBox(10, radio, text);
        card.getStyleClass().add("choice-card");
        card.setAlignment(Pos.TOP_LEFT);
        card.setOnMouseClicked(e -> radio.setSelected(true));    // the whole card is clickable
        return card;
    }

    private static Double parseNumber(String text) {
        try {
            return Double.parseDouble(text.strip().replace(',', '.'));   // accept "2,5" as well as "2.5"
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
