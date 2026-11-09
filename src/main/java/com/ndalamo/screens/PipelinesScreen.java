package com.ndalamo.screens;

import com.ndalamo.models.Pipeline;
import com.ndalamo.services.PipelineValidator;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Screen 7, the saved pipelines (mockup: docs/ui/screens/Pipelines.dc.html): one card each
 * with its steps as chips, badges, how often it has run, and Edit · Duplicate · Run. The
 * header switches between Pipelines and Watch folders.
 */
public class PipelinesScreen extends BorderPane {

    /** What the screen asks the app to do; the app owns the list and saves it. */
    public interface Actions {
        void home();
        void pipelines();
        void watchFolders();
        void create();
        void edit(Pipeline pipeline);
        void run(Pipeline pipeline);
        void duplicate(Pipeline pipeline);
        void delete(Pipeline pipeline);
    }

    private final MainWindow window;
    private final Actions actions;

    public PipelinesScreen(MainWindow window, List<Pipeline> pipelines, Actions actions) {
        this.window = window;
        this.actions = actions;
        setTop(header(true, actions));

        Label title = new Label("Pipelines");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Saved chains of tools. Pinned pipelines appear on the home screen.");
        subtitle.getStyleClass().add("muted");
        VBox content = new VBox(16, new VBox(4, title, subtitle));
        content.setPadding(new Insets(28, 24, 40, 24));
        content.setMaxWidth(960);
        if (pipelines.isEmpty()) {
            content.getChildren().add(empty());
        } else {
            for (Pipeline pipeline : pipelines) content.getChildren().add(card(pipeline));
        }
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        setCenter(scroll);
    }

    /** The header shared with the Watch folders screen: Home, then Pipelines | Watch folders, then + New pipeline. */
    static HBox header(boolean pipelinesShowing, Actions actions) {
        Button home = new Button("Home", Icons.back(16, Theme.TEXT));
        home.getStyleClass().add("button-secondary");
        home.setGraphicTextGap(6);
        home.setOnAction(e -> actions.home());
        Button pipelines = new Button("Pipelines");
        Button watch = new Button("Watch folders");
        pipelines.getStyleClass().add("header-nav-item");
        watch.getStyleClass().add("header-nav-item");
        (pipelinesShowing ? pipelines : watch).getStyleClass().add("header-nav-selected");
        pipelines.setOnAction(e -> {
            if (!pipelinesShowing) actions.pipelines();
        });
        watch.setOnAction(e -> actions.watchFolders());
        HBox nav = new HBox(4, pipelines, watch);
        nav.setAccessibleText("Automation");
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button create = new Button("+ New pipeline");
        create.getStyleClass().add("button-primary");
        create.setOnAction(e -> actions.create());
        HBox header = new HBox(16, Header.brand(), home, nav, push, create);
        header.getStyleClass().add("screen-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node empty() {
        Label heading = new Label("No pipelines yet");
        heading.getStyleClass().add("semibold");
        heading.setStyle("-fx-font-size: 17px;");
        Label text = new Label("A pipeline runs several tools one after the other with one click, for example "
                               + "OCR → Compress → Protect for scanned client documents. Make one here, or "
                               + "after using a tool, choose “Save as pipeline” on the results.");
        text.setWrapText(true);
        text.getStyleClass().add("muted");
        Button create = new Button("+ New pipeline");
        create.getStyleClass().add("button-primary");
        create.setOnAction(e -> actions.create());
        VBox box = new VBox(12, heading, text, create);
        box.getStyleClass().add("card");
        return box;
    }

    private Node card(Pipeline pipeline) {
        Label name = new Label(pipeline.getName());
        name.getStyleClass().add("semibold");
        name.setStyle("-fx-font-size: 16px;");
        HBox titleRow = new HBox(8, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (pipeline.isPinned()) titleRow.getChildren().add(badge("Pinned", "badge-accent"));
        if (!PipelineValidator.canRun(pipeline)) titleRow.getChildren().add(badge("Needs fixing", "badge-warning"));

        FlowPane steps = new FlowPane(6, 6);
        List<String> ids = pipeline.toolIds();
        for (int i = 0; i < ids.size(); i++) {
            Label chip = new Label(Tools.find(ids.get(i)).map(PdfTool::name).orElse("Unknown tool"));
            chip.getStyleClass().add("step-chip");
            steps.getChildren().add(chip);
            if (i < ids.size() - 1) {
                Label arrow = new Label("→");
                arrow.getStyleClass().add("small-muted");
                steps.getChildren().add(arrow);
            }
        }
        if (ids.isEmpty()) steps.getChildren().add(new Label("No steps yet"));
        Label used = new Label(used(pipeline));
        used.getStyleClass().add("small-muted");
        VBox left = new VBox(10, titleRow, steps, used);
        HBox.setHgrow(left, Priority.ALWAYS);

        Button edit = new Button("Edit");
        edit.getStyleClass().add("button-secondary");
        edit.setOnAction(e -> actions.edit(pipeline));
        Button duplicate = new Button("Duplicate");
        duplicate.getStyleClass().add("button-secondary");
        duplicate.setOnAction(e -> actions.duplicate(pipeline));
        Button delete = new Button();
        delete.setGraphic(Icons.close(14, Theme.TEXT_MUTED));
        delete.getStyleClass().addAll("button-secondary", "icon-button");
        delete.setAccessibleText("Delete " + pipeline.getName());
        delete.setTooltip(new javafx.scene.control.Tooltip("Delete this pipeline"));
        delete.setOnAction(e -> new ChoiceDialog(window, "Delete “" + pipeline.getName() + "”?",
                "The pipeline is removed. Files it made before stay where they are.", null)
                .button("Cancel", ChoiceDialog.Style.PLAIN, x -> {})
                .button("Delete pipeline", ChoiceDialog.Style.DANGER, x -> actions.delete(pipeline)).show());
        Button run = new Button("Run");
        run.getStyleClass().add("button-primary");
        run.setDisable(!PipelineValidator.canRun(pipeline));
        run.setOnAction(e -> actions.run(pipeline));
        HBox buttons = new HBox(8, edit, duplicate, delete, run);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        HBox card = new HBox(16, left, buttons);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("card");
        return card;
    }

    private static Label badge(String text, String style) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("small-badge", style);
        return badge;
    }

    /** "Last run 3 days ago · 12 runs", or "Not run yet". */
    static String used(Pipeline pipeline) {
        if (pipeline.getRuns() == 0) return "Not run yet";
        long minutes = Duration.ofMillis(System.currentTimeMillis() - pipeline.getLastRun()).toMinutes();
        String when = minutes < 2 ? "just now"
                : minutes < 60 ? minutes + " minutes ago"
                : minutes < 60 * 24 ? Format.count((int) (minutes / 60), "hour", "hours") + " ago"
                : minutes < 60 * 24 * 2 ? "yesterday"
                : Format.count((int) (minutes / (60 * 24)), "day", "days") + " ago";
        return "Last run " + when + " · " + Format.count(pipeline.getRuns(), "run", "runs");
    }
}
