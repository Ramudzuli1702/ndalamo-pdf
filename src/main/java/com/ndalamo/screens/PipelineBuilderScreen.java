package com.ndalamo.screens;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.services.PipelineService;
import com.ndalamo.services.PipelineTool;
import com.ndalamo.services.PipelineValidator;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Screen 8, the pipeline builder (mockup: docs/ui/screens/PipelineBuilder.dc.html): the
 * steps as numbered cards (drag or use the arrows to reorder, Options, remove), problems
 * under the step they're about with a one-click fix, and a tool list on the right to add
 * steps. Works on a copy: nothing is saved until "Save pipeline".
 */
public class PipelineBuilderScreen extends BorderPane {

    private final MainWindow window;
    private final Pipeline pipeline;
    private final SaveTo saveTo;
    private final VBox stepsBox = new VBox(10);
    private final VBox toolList = new VBox(4);
    private final Label inputLine = new Label();
    private final Label outputLine = new Label();
    private final TextField nameField = new TextField();

    /**
     * @param pipeline an editable copy (see Pipeline.editableCopy) or a new pipeline
     * @param onSave   saves it and goes back to the list
     */
    public PipelineBuilderScreen(MainWindow window, Pipeline pipeline, SaveTo saveTo, Runnable onBack,
                                 Consumer<Pipeline> onSave) {
        this.window = window;
        this.pipeline = pipeline;
        this.saveTo = saveTo;
        setTop(header(onBack, onSave));

        inputLine.getStyleClass().add("small-muted");
        Button addStep = new Button("+ Add step");
        addStep.getStyleClass().add("add-step");
        addStep.setMaxWidth(Double.MAX_VALUE);
        TextField search = new TextField();
        addStep.setOnAction(e -> search.requestFocus());
        Label outputTitle = new Label("Output");
        outputTitle.getStyleClass().add("small-heading");
        outputLine.getStyleClass().add("small-muted");
        outputLine.setWrapText(true);
        VBox output = new VBox(4, outputTitle, outputLine);
        output.getStyleClass().add("card");
        output.setPadding(new Insets(14, 16, 14, 16));
        VBox main = new VBox(14, inputLine, stepsBox, addStep, output);
        main.setPadding(new Insets(24, 24, 40, 24));
        main.setMaxWidth(760);
        VBox centred = new VBox(main);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane mainScroll = new ScrollPane(centred);
        mainScroll.setFitToWidth(true);
        mainScroll.getStyleClass().add("plain-scroll");
        setCenter(mainScroll);
        setRight(aside(search));
        redraw();
    }

    private HBox header(Runnable onBack, Consumer<Pipeline> onSave) {
        Button back = new Button("Pipelines", Icons.back(16, Theme.TEXT));
        back.getStyleClass().add("button-secondary");
        back.setGraphicTextGap(6);
        back.setOnAction(e -> onBack.run());
        nameField.setText(pipeline.getName());
        nameField.setAccessibleText("Pipeline name");
        nameField.setPrefWidth(320);
        nameField.getStyleClass().add("name-field");
        nameField.textProperty().addListener((obs, was, now) -> {
            pipeline.setName(now.strip());
            updateOutput();
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button test = new Button("Test on a file");
        test.getStyleClass().add("button-secondary");
        test.setOnAction(e -> testOnAFile());
        Button save = new Button("Save pipeline");
        save.getStyleClass().add("button-primary");
        save.setOnAction(e -> {
            if (pipeline.getName().isBlank()) {
                window.toast(Toast.error("Give the pipeline a name", "Type a name at the top, then save again."));
                nameField.requestFocus();
                return;
            }
            onSave.accept(pipeline);
            if (!PipelineValidator.canRun(pipeline)) {
                window.toast(Toast.info("Saved, but it can't run yet", "Fix the steps marked in red, then it can run."));
            }
        });
        HBox header = new HBox(16, Header.brand(), back, nameField, push, test, save);
        header.getStyleClass().add("screen-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    // ── The steps ────────────────────────────────────────────────────────

    private void redraw() {
        stepsBox.getChildren().clear();
        List<PipelineValidator.Problem> problems = PipelineValidator.check(pipeline);
        List<Pipeline.Step> steps = pipeline.getSteps();
        if (steps.isEmpty()) {
            Label hint = new Label("No steps yet. Pick a tool on the right to add the first step.");
            hint.getStyleClass().add("muted");
            stepsBox.getChildren().add(hint);
        }
        for (int i = 0; i < steps.size(); i++) {
            int index = i;
            VBox block = new VBox(0, stepCard(i, steps.get(i)));
            problems.stream().filter(p -> p.step() == index).forEach(p -> block.getChildren().add(problemBox(p)));
            stepProblem(i, steps.get(i)).ifPresent(block.getChildren()::add);
            stepsBox.getChildren().add(block);
        }
        String first = steps.isEmpty() ? null : steps.get(0).getToolId();
        inputLine.setText("Input: " + Tools.find(first == null ? "" : first).map(PipelineBuilderScreen::kinds).orElse("any PDF")
                          + " · steps run top to bottom");
        updateOutput();
    }

    private Node stepCard(int index, Pipeline.Step step) {
        PdfTool tool = Tools.find(step.getToolId()).orElse(null);
        Label handle = new Label("⠇⠇");
        handle.getStyleClass().add("drag-handle");
        handle.setAccessibleText("Drag to move step " + (index + 1));
        Label number = new Label(Integer.toString(index + 1));
        number.getStyleClass().add("step-number");
        Label name = new Label(tool == null ? "Unknown tool" : tool.name());
        name.getStyleClass().add("tool-name");
        Label summary = new Label(tool == null ? "" : summary(tool, step.getOptions()));
        summary.getStyleClass().add("tool-desc");
        summary.setWrapText(true);
        VBox words = new VBox(2, name, summary);
        HBox.setHgrow(words, Priority.ALWAYS);

        Button up = iconButton("↑", "Move step " + (index + 1) + " up", () -> move(index, index - 1));
        up.setDisable(index == 0);
        Button down = iconButton("↓", "Move step " + (index + 1) + " down", () -> move(index, index + 1));
        down.setDisable(index == pipeline.getSteps().size() - 1);
        Button options = new Button("Options");
        options.getStyleClass().add("button-secondary");
        options.setDisable(tool == null);
        options.setOnAction(e -> window.showDialog(new StepOptionsDialog(window, index + 1, tool,
                PipelineService.toRun(tool, step.getOptions()), chosen -> {
                    replace(index, new Pipeline.Step(step.getToolId(), PipelineService.toSaved(tool, chosen)));
                })));
        Button remove = new Button();
        remove.setGraphic(Icons.close(14, Theme.TEXT_MUTED));
        remove.getStyleClass().addAll("button-secondary", "icon-button");
        remove.setAccessibleText("Remove step " + (index + 1));
        remove.setTooltip(new javafx.scene.control.Tooltip("Remove this step"));
        remove.setOnAction(e -> {
            List<Pipeline.Step> steps = new ArrayList<>(pipeline.getSteps());
            steps.remove(index);
            pipeline.setSteps(steps);
            redraw();
        });

        HBox card = new HBox(12, handle, number, words, up, down, options, remove);
        // Only the name and summary give way when space is short; the handle and buttons keep their size.
        card.getChildren().stream().filter(n -> n != words).forEach(n -> ((Region) n).setMinWidth(Region.USE_PREF_SIZE));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("step-card");
        // Drag a card onto another to move it there.
        card.setOnDragDetected(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("step:" + index);
            card.startDragAndDrop(TransferMode.MOVE).setContent(content);
            e.consume();
        });
        card.setOnDragOver(e -> {
            if (e.getDragboard().hasString() && e.getDragboard().getString().startsWith("step:")) e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        card.setOnDragDropped(e -> {
            String text = e.getDragboard().getString();
            boolean ok = text != null && text.startsWith("step:");
            if (ok) move(Integer.parseInt(text.substring(5)), index);
            e.setDropCompleted(ok);
            e.consume();
        });
        return card;
    }

    /** A problem found by the checks, in warning (or error) colours, with its fix button. */
    private Node problemBox(PipelineValidator.Problem problem) {
        Label text = new Label(problem.message());
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(10, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add(problem.severity() == PipelineValidator.Severity.ERROR ? "step-error" : "step-warning");
        String fix = switch (problem.fix()) {
            case MOVE_TO_END -> "Move to end";
            case MOVE_TO_START -> "Move to start";
            case REMOVE -> "Remove step";
            case NONE -> null;
        };
        if (fix != null) {
            Button button = new Button(fix);
            button.getStyleClass().addAll("button-secondary", "button-small");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(e -> {
                PipelineValidator.fix(pipeline, problem);
                redraw();
            });
            box.getChildren().add(button);
        }
        return box;
    }

    /** The tool's own check of the step's options (e.g. Redact with nothing to look for), as a warning. */
    private java.util.Optional<Node> stepProblem(int index, Pipeline.Step step) {
        PdfTool tool = Tools.find(step.getToolId()).orElse(null);
        if (tool == null) return java.util.Optional.empty();
        Map<String, Object> options = new LinkedHashMap<>(PipelineService.toRun(tool, step.getOptions()));
        // Passwords are typed when it runs, so don't complain about them here.
        for (OptionField field : tool.optionFields()) {
            if (field.getType() == OptionField.Type.PASSWORD) options.put(field.getId(), "asked-when-run");
        }
        String problem = tool.needsPasswords() ? null : tool.checkOptions(options);
        if (problem == null) return java.util.Optional.empty();
        return java.util.Optional.of(problemBox(new PipelineValidator.Problem(index, PipelineValidator.Severity.WARNING,
                problem + " Change it under Options.", PipelineValidator.Fix.NONE)));
    }

    private void move(int from, int to) {
        List<Pipeline.Step> steps = new ArrayList<>(pipeline.getSteps());
        if (from == to || from < 0 || to < 0 || from >= steps.size() || to >= steps.size()) return;
        steps.add(to, steps.remove(from));
        pipeline.setSteps(steps);
        redraw();
    }

    private void replace(int index, Pipeline.Step step) {
        List<Pipeline.Step> steps = new ArrayList<>(pipeline.getSteps());
        steps.set(index, step);
        pipeline.setSteps(steps);
        redraw();
    }

    private void add(PdfTool tool) {
        List<Pipeline.Step> steps = new ArrayList<>(pipeline.getSteps());
        steps.add(new Pipeline.Step(tool.id(), PipelineService.toSaved(tool, PipelineService.toRun(tool, Map.of()))));
        pipeline.setSteps(steps);
        redraw();
    }

    private void updateOutput() {
        SaveTo named = saveTo.withToolWord(pipeline.getSuffix());
        PipelineTool asTool = new PipelineTool(pipeline);
        outputLine.setText(named.describeFolder(asTool.oneOutput()) + " · "
                           + named.describeName(pipeline.getSuffix(), asTool.outputExtension()));
    }

    // ── The tool list on the right ───────────────────────────────────────

    private Node aside(TextField search) {
        Label title = new Label("Add a step");
        title.getStyleClass().add("section-title");
        search.setPromptText("Search tools");
        search.setAccessibleText("Search tools");
        search.textProperty().addListener((obs, was, now) -> fillToolList(now));
        fillToolList("");
        ScrollPane tools = new ScrollPane(toolList);
        tools.setFitToWidth(true);
        tools.getStyleClass().add("plain-scroll");
        VBox.setVgrow(tools, Priority.ALWAYS);
        Label handWork = new Label("Sign, Fill forms, Compare and the page workspace need you each time, so they can't be steps.");
        handWork.getStyleClass().add("small-muted");
        handWork.setWrapText(true);
        handWork.setMinHeight(Region.USE_PREF_SIZE);
        CheckBox pin = new CheckBox("Pin to home screen");
        pin.setSelected(pipeline.isPinned());
        pin.selectedProperty().addListener((obs, was, now) -> pipeline.setPinned(now));
        Label watch = new Label("Link to a watch folder: comes with watch folders in the next update.");
        watch.getStyleClass().add("small-muted");
        watch.setWrapText(true);
        watch.setMinHeight(Region.USE_PREF_SIZE);
        VBox aside = new VBox(12, title, search, tools, handWork, new VBox(8, pin, watch));
        aside.getStyleClass().add("builder-aside");
        aside.setPrefWidth(300);
        aside.setMinWidth(300);
        aside.setPadding(new Insets(24, 20, 24, 20));
        return aside;
    }

    private void fillToolList(String query) {
        toolList.getChildren().clear();
        String q = query.strip().toLowerCase(Locale.ROOT);
        for (PdfTool tool : Tools.all()) {
            if (PipelineValidator.whyNotInPipeline(tool) != null) continue;
            if (tool.id().equals("compress-to-size")) continue;     // the same tool as Compress; choose Target size in its options
            if (!q.isEmpty() && !tool.name().toLowerCase(Locale.ROOT).contains(q)
                && !tool.description().toLowerCase(Locale.ROOT).contains(q)) continue;
            Button item = new Button(tool.name());
            item.getStyleClass().add("palette-item");
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setTooltip(new javafx.scene.control.Tooltip(tool.description()));
            item.setOnAction(e -> add(tool));
            toolList.getChildren().add(item);
        }
        if (toolList.getChildren().isEmpty()) {
            Label none = new Label("No tool matches “" + query.strip() + "”.");
            none.getStyleClass().add("small-muted");
            toolList.getChildren().add(none);
        }
    }

    // ── Test on a file ───────────────────────────────────────────────────

    /**
     * Runs the pipeline as it is now on one chosen file, into a temp folder, and opens the
     * result. Steps that need a password ask for it first. Nothing is saved.
     */
    private void testOnAFile() {
        if (!PipelineValidator.canRun(pipeline)) {
            window.toast(Toast.error("Fix the steps first", "The steps marked in red stop the pipeline from running."));
            return;
        }
        PipelineTool tool = new PipelineTool(pipeline.editableCopy());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Test “" + pipeline.getName() + "” on a file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Files the first step uses",
                tool.fileTypes().stream().map(t -> "*." + t).toList()));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        List<OptionField> passwords = tool.optionFields();
        if (passwords.isEmpty()) {
            startTest(tool, file.toPath(), Map.of());
            return;
        }
        // Ask for the passwords the steps need.
        VBox dialog = new VBox(12);
        dialog.getStyleClass().add("dialog-card");
        dialog.setMaxWidth(480);
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        Label title = new Label("Passwords for this test");
        title.getStyleClass().add("dialog-title");
        dialog.getChildren().add(title);
        Map<String, PasswordField> boxes = new LinkedHashMap<>();
        for (OptionField field : passwords) {
            Label label = new Label(field.getLabel());
            label.setWrapText(true);
            PasswordField box = new PasswordField();
            box.setAccessibleText(field.getLabel());
            boxes.put(field.getId(), box);
            dialog.getChildren().addAll(label, box);
        }
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        problem.setWrapText(true);
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button run = new Button("Run test");
        run.getStyleClass().addAll("button-primary", "button-dialog");
        run.setDefaultButton(true);
        run.setOnAction(e -> {
            Map<String, Object> answers = new LinkedHashMap<>();
            boxes.forEach((id, box) -> answers.put(id, box.getText()));
            String wrong = tool.checkOptions(answers);
            if (wrong != null) {
                problem.setText(wrong);
                return;
            }
            window.closeDialog();
            startTest(tool, file.toPath(), answers);
        });
        HBox buttons = new HBox(8, cancel, run);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        dialog.getChildren().addAll(problem, buttons);
        window.showDialog(dialog);
    }

    private void startTest(PipelineTool tool, Path file, Map<String, Object> answers) {
        window.toast(Toast.info("Testing on " + file.getFileName(), "The result opens when it's ready. Your file isn't changed."));
        CompletableFuture.supplyAsync(() -> {
            try {
                Path folder = Files.createTempDirectory("ndalamo-test-");
                return tool.run(List.of(file), answers, new SaveTo(folder, "{name}_{tool}", Settings.IfExists.ADD_NUMBER), new Progress());
            } catch (Exception e) {
                return List.of(FileResult.failed(file, 0, e.getMessage() == null ? "it stopped unexpectedly" : e.getMessage()));
            }
        }).thenAccept(results -> Platform.runLater(() -> {
            FileResult made = results.stream().filter(r -> r.getOutput() != null).findFirst().orElse(null);
            if (made != null) {
                FileActions.open(made.getOutput());
                window.toast(Toast.success("Test finished", made.getOutput().getFileName() + " opened. It's a test copy in a temp folder."));
            } else {
                String note = results.isEmpty() ? "Nothing came out." : results.get(0).getNote();
                window.toast(Toast.error("The test didn't finish", note));
            }
        }));
    }

    // ── Words ────────────────────────────────────────────────────────────

    /** "Recommended · Remove metadata", from the options that are set; "Default options" if none. */
    static String summary(PdfTool tool, Map<String, Object> saved) {
        Map<String, Object> options = PipelineService.toRun(tool, saved);
        List<String> parts = new ArrayList<>();
        for (OptionField field : tool.optionFields()) {
            if (field.getShownWhenId() != null && !field.getShownWhenValue().equals(options.get(field.getShownWhenId()))) continue;
            Object value = options.get(field.getId());
            switch (field.getType()) {
                case CHOICE, TEXT -> {
                    if (value instanceof String s && !s.isBlank()) parts.add(s);
                }
                case NUMBER -> {
                    if (value instanceof Number n) parts.add(field.getLabel() + " " + (n.doubleValue() == Math.rint(n.doubleValue())
                            ? Long.toString(n.longValue()) : n.toString()));
                }
                case CHECKBOX -> {
                    if (Boolean.TRUE.equals(value)) parts.add(field.getLabel());
                }
                case PASSWORD -> {
                    if (!parts.contains("password asked when it runs")) parts.add("password asked when it runs");
                }
                default -> { }
            }
            if (parts.size() >= 4) break;
        }
        if (tool.needsPasswords()) parts.add("password asked when it runs");
        return parts.isEmpty() ? "Default options" : String.join(" · ", parts);
    }

    private static String kinds(PdfTool tool) {
        if (tool.fileTypes().equals(java.util.Set.of("pdf"))) return "any PDF";
        return String.join(", ", tool.fileTypes().stream().sorted().map(t -> t.toUpperCase(Locale.ROOT)).toList()) + " files";
    }

    private static Button iconButton(String text, String accessible, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("button-secondary", "icon-button");
        button.setAccessibleText(accessible);
        button.setTooltip(new javafx.scene.control.Tooltip(accessible));
        button.setOnAction(e -> action.run());
        return button;
    }
}
