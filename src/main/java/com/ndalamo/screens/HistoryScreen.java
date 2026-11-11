package com.ndalamo.screens;

import com.ndalamo.models.HistoryEntry;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Format;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * History: everything Ndalamo has done lately, newest first and grouped by day. Each run shows
 * the tool, the files given and what came out, with Open, Show in folder and Use again (the
 * same tool with the same files). Kept on this PC only; Clear history empties it.
 */
public class HistoryScreen extends BorderPane {

    /** What the screen asks the app to do. */
    public interface Actions {
        void home();
        void useAgain(HistoryEntry entry);
        void clear();
    }

    private final MainWindow window;
    private final Actions actions;
    private final VBox list = new VBox(0);

    public HistoryScreen(MainWindow window, List<HistoryEntry> entries, boolean remembering, Actions actions) {
        this.window = window;
        this.actions = actions;
        ScreenHeader header = new ScreenHeader("History", actions::home);
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button clear = new Button("Clear history");
        clear.getStyleClass().add("button-secondary");
        clear.setDisable(entries.isEmpty());
        clear.setOnAction(e -> new ChoiceDialog(window, "Clear the history?",
                "The list of what NdalamoPDF has done is emptied, along with the recent files on Home. "
                + "The files themselves are not touched.", null)
                .button("Cancel", ChoiceDialog.Style.PLAIN, x -> {})
                .button("Clear history", ChoiceDialog.Style.DANGER, x -> actions.clear()).show());
        header.getChildren().addAll(push, clear);
        setTop(header);

        Label title = new Label("History");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(remembering
                ? "What NdalamoPDF has done lately, on this PC only. Turn it off in Settings › Privacy."
                : "NdalamoPDF isn't keeping a history: “Remember recent files” is off in Settings › Privacy.");
        subtitle.getStyleClass().add("muted");
        subtitle.setWrapText(true);

        if (entries.isEmpty()) {
            Label none = new Label("Nothing yet. Files you work on, and what came out, show up here.");
            none.getStyleClass().add("muted");
            none.setPadding(new Insets(16));
            list.getChildren().add(none);
        } else {
            LocalDate shownDay = null;
            for (HistoryEntry entry : entries) {
                LocalDate day = Instant.ofEpochMilli(entry.time()).atZone(ZoneId.systemDefault()).toLocalDate();
                if (!day.equals(shownDay)) {
                    Label heading = new Label(dayName(day));
                    heading.getStyleClass().add("history-day");
                    heading.setPadding(new Insets(14, 16, 4, 16));
                    list.getChildren().add(heading);
                    shownDay = day;
                }
                list.getChildren().add(row(entry));
            }
        }
        list.getStyleClass().add("card");
        list.setPadding(new Insets(4, 0, 8, 0));

        VBox content = new VBox(16, new VBox(4, title, subtitle), list);
        content.setPadding(new Insets(28, 24, 40, 24));
        content.setMaxWidth(900);
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
    }

    private Node row(HistoryEntry entry) {
        Label time = new Label(Instant.ofEpochMilli(entry.time()).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)));
        time.getStyleClass().add("small-muted");
        time.setMinWidth(44);
        Label tool = new Label(entry.toolName());
        tool.getStyleClass().add("tool-name");
        Label files = new Label(describe(entry));
        files.getStyleClass().add("small-muted");
        files.setWrapText(true);
        VBox words = new VBox(2, tool, files);
        HBox.setHgrow(words, Priority.ALWAYS);

        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Path first = entry.outputs().isEmpty() ? null : Path.of(entry.outputs().get(0));
        if (first != null) {
            buttons.getChildren().add(small("Open", () -> ifThere(first, FileActions::open)));
            buttons.getChildren().add(small("Show in folder", () -> ifThere(first, FileActions::showInFolder)));
        }
        if (!entry.inputs().isEmpty() && !entry.toolId().equals("workspace")) {
            buttons.getChildren().add(small("Use again", () -> actions.useAgain(entry)));
        }
        HBox row = new HBox(14, time, words, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("history-row");
        return row;
    }

    /** "Invoice.pdf → Invoice_compressed.pdf", or "3 files → 3 results". */
    private static String describe(HistoryEntry entry) {
        String in = entry.inputs().size() == 1 ? Path.of(entry.inputs().get(0)).getFileName().toString()
                : Format.count(entry.inputs().size(), "file", "files");
        String out = entry.outputs().isEmpty() ? "nothing saved"
                : entry.outputs().size() == 1 ? Path.of(entry.outputs().get(0)).getFileName().toString()
                : Format.count(entry.outputs().size(), "result", "results");
        return in + " → " + out;
    }

    private void ifThere(Path file, Consumer<Path> action) {
        if (Files.exists(file)) {
            action.accept(file);
        } else {
            window.toast(Toast.info(file.getFileName() + " isn't there any more",
                    "It was moved, renamed or deleted after NdalamoPDF made it."));
        }
    }

    private static Button small(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("button-secondary", "button-small");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static String dayName(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return "Today";
        if (day.equals(today.minusDays(1))) return "Yesterday";
        return day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
    }
}
