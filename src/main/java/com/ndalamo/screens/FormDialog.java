package com.ndalamo.screens;

import com.ndalamo.models.FormField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The form's fields, to fill in: one row per field, in page order, with the control
 * its kind needs (a text box, a tick box, a list to choose from). Answers already in
 * the PDF are filled in to start with. Read-only fields are shown but can't be changed.
 * Fill forms writes the answers into the PDF when it runs.
 */
public class FormDialog extends VBox {

    private final Map<String, Supplier<String>> answers = new LinkedHashMap<>();
    private final Label count = new Label();

    /**
     * @param fields   the form's fields (FillFormsTool.fields)
     * @param previous answers given before, by field name, so reopening keeps them; may be empty
     * @param onSave   gets the answers by field name
     */
    public FormDialog(MainWindow window, String fileName, List<FormField> fields, Map<String, String> previous,
                      Consumer<Map<String, String>> onSave) {
        super(14);
        getStyleClass().add("dialog-card");
        setMaxWidth(620);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label("Fill in " + fileName);
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);
        count.getStyleClass().add("dialog-text");

        VBox rows = new VBox(14);
        rows.setPadding(new Insets(4, 12, 4, 2));
        for (FormField field : fields) rows.getChildren().add(row(field, previous.getOrDefault(field.name(), field.value())));
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(Math.min(440, fields.size() * 62 + 10));

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> window.closeDialog());
        Button save = new Button("Use these answers");
        save.getStyleClass().addAll("button-primary", "button-dialog");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Map<String, String> result = new LinkedHashMap<>();
            answers.forEach((name, value) -> result.put(name, value.get()));
            window.closeDialog();
            onSave.accept(result);
        });
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(8, count, push, cancel, save);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, scroll, buttons);
        updateCount();
    }

    private VBox row(FormField field, String value) {
        Label label = new Label(field.label() + (field.readOnly() ? "  (can't be changed)" : ""));
        label.getStyleClass().add("field-label");
        label.setWrapText(true);
        Control control;
        switch (field.kind()) {
            case TICK_BOX -> {
                CheckBox box = new CheckBox(field.label());
                box.setSelected(Boolean.parseBoolean(value));
                box.selectedProperty().addListener((obs, old, now) -> updateCount());
                answers.put(field.name(), () -> String.valueOf(box.isSelected()));
                control = box;
                label = null;                                   // the tick box carries its own label
            }
            case RADIO, LIST -> {
                ComboBox<String> list = new ComboBox<>();
                list.getItems().add("");                         // no answer
                list.getItems().addAll(field.choices());
                list.setValue(field.choices().contains(value) ? value : "");
                list.setMaxWidth(Double.MAX_VALUE);
                list.valueProperty().addListener((obs, old, now) -> updateCount());
                answers.put(field.name(), () -> list.getValue() == null ? "" : list.getValue());
                control = list;
            }
            case LONG_TEXT -> {
                TextArea text = new TextArea(value);
                text.setPrefRowCount(3);
                text.setWrapText(true);
                text.textProperty().addListener((obs, old, now) -> updateCount());
                answers.put(field.name(), text::getText);
                control = text;
            }
            default -> {
                TextField text = new TextField(value);
                text.textProperty().addListener((obs, old, now) -> updateCount());
                answers.put(field.name(), text::getText);
                control = text;
            }
        }
        control.setDisable(field.readOnly());
        if (field.readOnly()) answers.remove(field.name());       // never written back
        control.setAccessibleText(field.label());
        VBox row = label == null ? new VBox(control) : new VBox(4, label, control);
        return row;
    }

    /** "5 of 12 answered": tick boxes count when ticked, everything else when filled in. */
    private void updateCount() {
        long answered = answers.values().stream().map(Supplier::get).filter(v -> !v.isBlank() && !v.equals("false")).count();
        count.setText(answered + " of " + answers.size() + " answered");
    }
}
