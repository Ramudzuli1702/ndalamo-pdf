package com.ndalamo.screens;

import com.ndalamo.models.Activity;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.WatchFolder;
import com.ndalamo.services.WatchFolderService;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Screen 9, watch folders (mockup: docs/ui/screens/WatchFolders.dc.html): one row per folder
 * (watched folder → pipeline → output folder, status, Pause/Resume, remove), then the
 * activity log with Export log. The app calls {@link #refresh()} when something happens.
 */
public class WatchFoldersScreen extends BorderPane {

    private final MainWindow window;
    private final WatchFolderService service;
    private final List<Pipeline> pipelines;
    private final VBox rows = new VBox(10);
    private final VBox log = new VBox(0);

    public WatchFoldersScreen(MainWindow window, WatchFolderService service, List<Pipeline> pipelines,
                              PipelinesScreen.Actions actions) {
        this.window = window;
        this.service = service;
        this.pipelines = pipelines;
        setTop(PipelinesScreen.header(false, actions, "+ Add watch folder", () -> add(null)));

        Label title = new Label("Watch folders");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Files dropped into a watched folder run through its pipeline automatically while the app "
                                   + "is open or in the system tray.");
        subtitle.getStyleClass().add("muted");
        subtitle.setWrapText(true);

        Label activityTitle = new Label("Activity");
        activityTitle.getStyleClass().add("section-title");
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button export = new Button("Export log");
        export.getStyleClass().add("button-secondary");
        export.setOnAction(e -> exportLog());
        HBox activityHeader = new HBox(10, activityTitle, push, export);
        activityHeader.setAlignment(Pos.CENTER_LEFT);
        log.getStyleClass().add("card");
        log.setPadding(new Insets(4, 16, 4, 16));

        VBox content = new VBox(18, new VBox(4, title, subtitle), rows, new VBox(10, activityHeader, log));
        content.setPadding(new Insets(28, 24, 40, 24));
        content.setMaxWidth(1000);
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
        refresh();
    }

    /** Opens "Add a watch folder", with a pipeline already chosen if one is given. */
    public void add(Pipeline chosen) {
        window.showDialog(new WatchFolderDialog(window, pipelines, chosen, service.folders(), folder -> {
            service.add(folder);
            refresh();
            window.toast(Toast.success("Watching " + shortName(folder.getInputFolder()),
                    "Files put in it now run through the pipeline. Files already there are done first."));
        }));
    }

    /** Redraws the folders and the log (after a file is processed, or a change here). */
    public void refresh() {
        rows.getChildren().clear();
        List<WatchFolder> folders = service.folders();
        if (folders.isEmpty()) rows.getChildren().add(empty());
        for (WatchFolder folder : folders) rows.getChildren().add(row(folder));

        log.getChildren().clear();
        List<Activity> entries = service.activity();
        if (entries.isEmpty()) {
            Label none = new Label("Nothing yet. Files done by watch folders show up here, newest first.");
            none.getStyleClass().add("small-muted");
            none.setPadding(new Insets(12, 0, 12, 0));
            log.getChildren().add(none);
        }
        for (Activity entry : entries.subList(0, Math.min(100, entries.size()))) log.getChildren().add(logRow(entry));
    }

    private Node empty() {
        Label text = new Label(pipelines.isEmpty()
                ? "Make a pipeline first (Pipelines › + New pipeline), then add a folder for it to watch."
                : "No folders are being watched. Add one, and every file put in it runs through a pipeline by itself, "
                  + "for example scans from a network scanner.");
        text.setWrapText(true);
        text.getStyleClass().add("muted");
        VBox box = new VBox(12, text);
        if (!pipelines.isEmpty()) {
            Button add = new Button("+ Add watch folder");
            add.getStyleClass().add("button-primary");
            add.setOnAction(e -> add(null));
            box.getChildren().add(add);
        }
        box.getStyleClass().add("card");
        return box;
    }

    private Node row(WatchFolder folder) {
        String pipelineName = pipelines.stream().filter(p -> p.getId().equals(folder.getPipelineId()))
                .map(Pipeline::getName).findFirst().orElse("(deleted pipeline)");
        FlowPane route = new FlowPane(8, 6, chip(folder.getInputFolder(), true), arrow(), chip(pipelineName, false), arrow(),
                chip(folder.getOutputFolder(), true));
        route.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(route, Priority.ALWAYS);
        Label status = new Label(folder.isEnabled() ? "Watching · " + folder.getProcessed() + " processed" : "Paused");
        status.getStyleClass().add(folder.isEnabled() ? "status-good" : "small-muted");
        status.setMinWidth(Region.USE_PREF_SIZE);
        Button toggle = new Button(folder.isEnabled() ? "Pause" : "Resume");
        toggle.getStyleClass().add("button-secondary");
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.setOnAction(e -> {
            service.setEnabled(folder, !folder.isEnabled());
            refresh();
        });
        Button remove = new Button();
        remove.setGraphic(Icons.close(14, Theme.TEXT_MUTED));
        remove.getStyleClass().addAll("button-secondary", "icon-button");
        remove.setAccessibleText("Stop watching " + folder.getInputFolder());
        remove.setTooltip(new javafx.scene.control.Tooltip("Stop watching this folder"));
        remove.setOnAction(e -> new ChoiceDialog(window, "Stop watching " + shortName(folder.getInputFolder()) + "?",
                "Files put in it won't be processed any more. Nothing in the folder is changed or deleted.", null)
                .button("Cancel", ChoiceDialog.Style.PLAIN, x -> {})
                .button("Stop watching", ChoiceDialog.Style.DANGER, x -> {
                    service.remove(folder);
                    refresh();
                }).show());
        HBox row = new HBox(14, route, status, toggle, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card");
        row.setPadding(new Insets(14, 16, 14, 16));
        return row;
    }

    private Node logRow(Activity entry) {
        Label time = new Label(when(entry.time()));
        time.getStyleClass().add("small-muted");
        Label file = new Label(entry.file());
        file.getStyleClass().add("semibold");
        Label pipeline = new Label(entry.pipeline());
        pipeline.getStyleClass().add("small-muted");
        Label result = new Label(entry.result());
        result.getStyleClass().add(entry.ok() ? "status-good" : "status-error");
        result.setWrapText(true);
        GridPane grid = new GridPane();
        grid.setHgap(14);
        ColumnConstraints c1 = new ColumnConstraints(110), c2 = new ColumnConstraints(), c3 = new ColumnConstraints(160),
                c4 = new ColumnConstraints();
        c2.setPercentWidth(28);
        c4.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2, c3, c4);
        grid.addRow(0, time, file, pipeline, result);
        grid.getStyleClass().add("log-row");
        if (entry.output() != null) {
            grid.setOnMouseClicked(e -> com.ndalamo.utils.FileActions.showInFolder(java.nio.file.Path.of(entry.output())));
            grid.setStyle("-fx-cursor: hand;");
            javafx.scene.control.Tooltip.install(grid, new javafx.scene.control.Tooltip("Show the result in its folder"));
        }
        return grid;
    }

    private void exportLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export the activity log");
        chooser.setInitialFileName("NdalamoPDF activity " + LocalDate.now() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (opens in Excel)", "*.csv"));
        File target = chooser.showSaveDialog(getScene().getWindow());
        if (target == null) return;
        try {
            service.exportLog(target.toPath());
            window.toast(Toast.success("Log exported", target.getName() + " is saved."));
        } catch (IOException e) {
            window.toast(Toast.error("The log wasn't exported", "NdalamoPDF couldn't write " + target.getName()
                    + ". Choose another folder and try again."));
        }
    }

    private static Label chip(String text, boolean path) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("step-chip", path ? "mono-text" : "semibold");
        chip.setMaxWidth(320);
        return chip;
    }

    private static Label arrow() {
        Label arrow = new Label("→");
        arrow.getStyleClass().add("small-muted");
        return arrow;
    }

    /** "14:02" today, "Yesterday 14:02", else "3 Nov 14:02". */
    static String when(long millis) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        LocalDate day = time.toLocalDate(), today = LocalDate.now();
        String clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        if (day.equals(today)) return clock;
        if (day.equals(today.minusDays(1))) return "Yesterday " + clock;
        return time.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " " + clock;
    }

    private static String shortName(String folder) {
        java.nio.file.Path name = java.nio.file.Path.of(folder).getFileName();
        return name == null ? folder : name.toString();
    }
}
