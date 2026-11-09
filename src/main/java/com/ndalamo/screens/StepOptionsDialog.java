package com.ndalamo.screens;

import com.ndalamo.models.OptionField;
import com.ndalamo.tools.PdfTool;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The options of one pipeline step, set in the builder: the same choices as the tool's own
 * screen, built from its option fields. Passwords aren't set here (they're asked for each
 * time the pipeline runs), and areas drawn on a page can't be, since there's no page yet.
 */
public class StepOptionsDialog extends VBox {

    private final Map<String, Supplier<Object>> values = new LinkedHashMap<>();
    private final Map<String, Supplier<Object>> choiceValues = new LinkedHashMap<>();
    private final Map<OptionField, Node> rows = new LinkedHashMap<>();

    /** @param current the step's options now; {@code onSave} gets the new ones */
    public StepOptionsDialog(MainWindow window, int stepNumber, PdfTool tool, Map<String, Object> current,
                             Consumer<Map<String, Object>> onSave) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(560);
        setMaxHeight(USE_PREF_SIZE);
        Label title = new Label("Step " + stepNumber + ": " + tool.name());
        title.getStyleClass().add("dialog-title");
        VBox fields = new VBox(16);
        for (OptionField field : tool.optionFields()) {
            Node row = row(field, current.containsKey(field.getId()) ? current.get(field.getId()) : field.getDefaultValue());
            rows.put(field, row);
            fields.getChildren().add(row);
        }
        if (tool.optionFields().isEmpty()) {
            Label none = new Label(tool.name() + " has no options. It runs the same way every time.");
            none.getStyleClass().add("dialog-text");
            fields.getChildren().add(none);
        }
        ScrollPane scroll = new ScrollPane(fields);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setMaxHeight(460);
        updateVisibility();

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button save = new Button("Done");
        save.getStyleClass().addAll("button-primary", "button-dialog");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Map<String, Object> chosen = new LinkedHashMap<>();
            values.forEach((id, value) -> {
                Object v = value.get();
                if (v != null) chosen.put(id, v);
            });
            window.closeDialog();
            onSave.accept(chosen);
        });
        HBox buttons = new HBox(8, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(title, scroll, buttons);
    }

    private Node row(OptionField field, Object value) {
        Label label = new Label(field.getLabel());
        label.getStyleClass().add("small-heading");
        label.setWrapText(true);
        VBox box = new VBox(6, label);
        switch (field.getType()) {
            case CHOICE -> {
                ToggleGroup group = new ToggleGroup();
                for (int c = 0; c < field.getChoices().size(); c++) {
                    String choice = field.getChoices().get(c);
                    RadioButton radio = new RadioButton(choice);
                    radio.setToggleGroup(group);
                    radio.setUserData(choice);
                    radio.setSelected(choice.equals(value));
                    box.getChildren().add(radio);
                    if (c < field.getChoiceDescriptions().size() && field.getChoiceDescriptions().get(c) != null) {
                        Label about = new Label(field.getChoiceDescriptions().get(c));
                        about.getStyleClass().add("small-muted");
                        about.setWrapText(true);
                        about.setPadding(new javafx.geometry.Insets(0, 0, 0, 26));
                        box.getChildren().add(about);
                    }
                }
                group.selectedToggleProperty().addListener((obs, was, now) -> updateVisibility());
                Supplier<Object> get = () -> group.getSelectedToggle() == null ? null : group.getSelectedToggle().getUserData();
                values.put(field.getId(), get);
                choiceValues.put(field.getId(), get);
            }
            case CHECKBOX -> {
                CheckBox check = new CheckBox(field.getLabel());
                check.setSelected(Boolean.TRUE.equals(value));
                values.put(field.getId(), check::isSelected);
                box.getChildren().setAll(check);
            }
            case NUMBER -> {
                TextField number = new TextField(value instanceof Number n ? clean(n) : value == null ? "" : value.toString());
                number.setMaxWidth(100);
                number.setAccessibleText(field.getLabel());
                values.put(field.getId(), () -> {
                    try {
                        return Double.parseDouble(number.getText().strip().replace(',', '.'));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });
                box.getChildren().add(number);
            }
            case TEXT -> {
                TextField text = new TextField(value == null ? "" : value.toString());
                text.setPromptText(field.getHint());
                text.setAccessibleText(field.getLabel());
                values.put(field.getId(), text::getText);
                box.getChildren().add(text);
            }
            case FILE -> {
                Path[] picked = {value instanceof Path p ? p : value instanceof String s ? Path.of(s) : null};
                Label chosen = new Label(picked[0] == null ? "No file chosen" : picked[0].getFileName().toString());
                chosen.getStyleClass().add("small-muted");
                Button pick = new Button("Choose…");
                pick.getStyleClass().add("button-secondary");
                pick.setOnAction(e -> {
                    File file = new FileChooser().showOpenDialog(getScene().getWindow());
                    if (file == null) return;
                    picked[0] = file.toPath();
                    chosen.setText(file.getName());
                });
                values.put(field.getId(), () -> picked[0]);
                HBox line = new HBox(10, pick, chosen);
                line.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(line);
            }
            case PASSWORD -> {
                Label note = new Label("Asked for each time the pipeline runs, and never saved.");
                note.getStyleClass().add("small-muted");
                box.getChildren().add(note);
            }
            default -> {
                Label note = new Label("This is drawn on a page, so it can't be set in a pipeline. Choose another way above if there is one.");
                note.getStyleClass().add("small-muted");
                note.setWrapText(true);
                box.getChildren().add(note);
            }
        }
        return box;
    }

    /** Fields that only apply to one choice (e.g. "MB per file" for Target size) show only then. */
    private void updateVisibility() {
        rows.forEach((field, row) -> {
            if (field.getShownWhenId() == null) return;
            Supplier<Object> choice = choiceValues.get(field.getShownWhenId());
            boolean show = choice != null && field.getShownWhenValue().equals(choice.get());
            row.setVisible(show);
            row.setManaged(show);
        });
    }

    private static String clean(Number n) {
        double d = n.doubleValue();
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
