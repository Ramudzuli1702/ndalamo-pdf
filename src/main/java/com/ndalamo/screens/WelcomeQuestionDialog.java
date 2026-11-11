package com.ndalamo.screens;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The welcome question, asked once after the first-start tour: who's using NdalamoPDF (one
 * choice) and what for (any number). Send hands the answers to the caller, which queues and
 * sends them like a review (FeedbackService); Skip closes it for good. Nothing is sent on Skip.
 */
public final class WelcomeQuestionDialog extends VBox {

    /** The choices, as shown and as stored in the spreadsheet. */
    public static final List<String> ROLES = List.of("Student", "Teacher or lecturer", "Employee or office worker",
            "Business owner or self-employed", "Government or public sector", "Job seeker", "Other");
    public static final List<String> USES = List.of("Studies and assignments", "Work documents", "Running a business",
            "Applications and forms (jobs, bursaries, IDs)", "Personal paperwork", "Other");

    private final ToggleGroup role = new ToggleGroup();
    private final List<CheckBox> uses = new ArrayList<>();
    private final Button send = new Button("Send");

    public WelcomeQuestionDialog(MainWindow window, BiConsumer<String, List<String>> onSend, Runnable onSkip) {
        super(14);
        getStyleClass().addAll("dialog-card", "welcome-question");
        setMaxWidth(560);
        setMaxHeight(USE_PREF_SIZE);

        Label title = new Label("Quick question");
        title.getStyleClass().add("dialog-title");
        Label ask = new Label("Help make NdalamoPDF fit the people who use it. It takes ten seconds, and you can skip it.");
        ask.getStyleClass().add("dialog-text");
        ask.setWrapText(true);

        Label whoLabel = new Label("Which best describes you?");
        whoLabel.getStyleClass().add("question-label");
        GridPane who = new GridPane();
        who.setHgap(18);
        who.setVgap(8);
        for (int i = 0; i < ROLES.size(); i++) {
            RadioButton choice = new RadioButton(ROLES.get(i));
            choice.setToggleGroup(role);
            choice.setUserData(ROLES.get(i));
            who.add(choice, i % 2, i / 2);
        }

        Label forLabel = new Label("What will you use NdalamoPDF for? Tick any that apply.");
        forLabel.getStyleClass().add("question-label");
        forLabel.setWrapText(true);
        VBox what = new VBox(8);
        for (String use : USES) {
            CheckBox box = new CheckBox(use);
            box.selectedProperty().addListener((obs, was, now) -> refresh());
            uses.add(box);
            what.getChildren().add(box);
        }
        role.selectedToggleProperty().addListener((obs, was, now) -> refresh());

        Label privacy = new Label("Only your answers and NdalamoPDF's version are sent, to the NdalamoPDF team. "
                + "No files or details about your PC. If you're offline, they're sent once you're connected.");
        privacy.getStyleClass().add("small-muted");
        privacy.setWrapText(true);

        Button skip = new Button("Skip");
        skip.getStyleClass().addAll("button-secondary", "button-dialog");
        skip.setCancelButton(true);
        skip.setOnAction(e -> {
            window.closeDialog();
            onSkip.run();
        });
        send.getStyleClass().addAll("button-primary", "button-dialog");
        send.setDefaultButton(true);
        send.setDisable(true);
        send.setOnAction(e -> {
            window.closeDialog();
            onSend.accept(chosenRole(), chosenUses());
        });
        HBox buttons = new HBox(8, skip, send);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(title, ask, whoLabel, who, forLabel, what, privacy, buttons);
    }

    private void refresh() {
        send.setDisable(chosenRole() == null && chosenUses().isEmpty());
    }

    private String chosenRole() {
        return role.getSelectedToggle() == null ? null : (String) role.getSelectedToggle().getUserData();
    }

    private List<String> chosenUses() {
        return uses.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
    }
}
