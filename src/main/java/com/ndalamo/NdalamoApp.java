package com.ndalamo;

import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.screens.ComingSoonScreen;
import com.ndalamo.screens.HomeScreen;
import com.ndalamo.screens.SettingsScreen;
import com.ndalamo.screens.PipelineBuilderScreen;
import com.ndalamo.screens.PipelinesScreen;
import com.ndalamo.screens.Toast;
import com.ndalamo.models.Pipeline;
import com.ndalamo.services.PipelineService;
import com.ndalamo.services.PipelineTool;
import com.ndalamo.screens.SuggestScreen;
import com.ndalamo.screens.LibreOfficeDialog;
import com.ndalamo.screens.LogoMark;
import com.ndalamo.screens.MainWindow;
import com.ndalamo.screens.ToolFlow;
import com.ndalamo.screens.ToolScreen;
import com.ndalamo.screens.WordAddInCard;
import com.ndalamo.screens.WorkspaceScreen;
import com.ndalamo.tools.WorkspaceTool;
import com.ndalamo.services.JobService;
import com.ndalamo.services.SettingsService;
import com.ndalamo.tools.LibreOffice;
import com.ndalamo.tools.OfficeToPdfTool;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.utils.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts Ndalamo and switches between screens.
 *
 * Every screen is a whole page (header included); showing one just swaps what
 * MainWindow displays. Things that work on every screen live here: Ctrl+K search
 * and dropping files anywhere on the window. Running a tool is ToolFlow's job.
 *
 * Run during development with: mvn javafx:run
 */
public class NdalamoApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(NdalamoApp.class);

    private Stage stage;
    private final MainWindow window = new MainWindow();
    private HomeScreen home;
    private ToolFlow toolFlow;
    private Settings settings;
    private final SettingsService settingsService = new SettingsService();
    private JobService jobService;
    private WorkspaceScreen workspace;      // kept, so files left open are still there next time
    private final PipelineService pipelineService = new PipelineService();
    private List<Pipeline> pipelines = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                log.error("Uncaught exception on {}", thread.getName(), e));

        settings = settingsService.load();
        pipelines = pipelineService.load();
        LibreOffice.setChosenLocation(settings.getLibreOfficePath());
        jobService = new JobService(settings.workerThreads());
        log.info("Ndalamo started with {} worker thread(s)", settings.workerThreads());

        Theme.loadFonts();   // must happen before any screen is built
        home = new HomeScreen(this::showPipelines, this::showSettings, this::chooseFiles, this::openTool);
        toolFlow = new ToolFlow(window, jobService, () -> SaveTo.from(settings), () -> settings.getToolDefaults(), this::showHome);
        window.show(home);

        Scene scene = new Scene(window, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        // Ctrl+K: jump to the tool search from any screen
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), () -> {
            Runnable goSearch = () -> {
                showHome();
                home.focusSearch();
            };
            if (window.current() == workspace) workspace.leave(goSearch);   // asks about unsaved edits first
            else goSearch.run();
        });

        // Ctrl+O: open files, for whichever screen is showing (SPEC §6.3)
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), () -> {
            if (window.hasDialog()) return;
            if (window.current() instanceof ToolScreen toolScreen) toolScreen.chooseFiles();
            else if (window.current() == workspace) workspace.chooseFilesToOpen();
            else chooseFiles();
        });
        // Esc: closes a dialog (MainWindow does that), otherwise the newest toast.
        scene.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !window.hasDialog() && window.dismissNewestToast()) e.consume();
        });

        // Dropping files anywhere on the window (SPEC §6.2, screen 1)
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean hasFiles = e.getDragboard().hasFiles();
            if (hasFiles) filesChosen(e.getDragboard().getFiles());
            e.setDropCompleted(hasFiles);
            e.consume();
        });

        stage.setTitle("Ndalamo");
        stage.getIcons().addAll(LogoMark.windowIcons());
        stage.setMinWidth(1024);
        stage.setMinHeight(700);
        stage.setScene(scene);
        Theme.apply(scene, settings.getAppearance());
        if (Theme.isDark()) rebuildHome();                    // Home was built before the theme was known
        // "Follow system": switch when Windows switches between light and dark.
        Platform.getPreferences().colorSchemeProperty().addListener((obs, old, now) -> {
            if (settings.getAppearance() == Settings.Appearance.FOLLOW_SYSTEM) applyAppearance(settings.getAppearance());
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (jobService != null) jobService.shutdown();
        log.info("Ndalamo closed");
    }

    // ── Navigation ───────────────────────────────────────────────────────

    private void showHome() {
        window.show(home);
    }

    // ── Pipelines ────────────────────────────────────────────────────────

    private void showPipelines() {
        window.show(new PipelinesScreen(window, pipelines, new PipelinesScreen.Actions() {
            @Override public void home() { showHome(); }
            @Override public void pipelines() { showPipelines(); }
            @Override public void watchFolders() {
                window.show(new ComingSoonScreen("Watch folders",
                        "Folders that run a pipeline on every file put in them come in the next update (chunk 17).",
                        List.of(), NdalamoApp.this::showPipelines));
            }
            @Override public void create() { editPipeline(new Pipeline()); }
            @Override public void edit(Pipeline pipeline) { editPipeline(pipeline.editableCopy()); }
            @Override public void run(Pipeline pipeline) { runPipeline(pipeline, List.of()); }
            @Override public void duplicate(Pipeline pipeline) {
                pipelines.add(pipelines.indexOf(pipeline) + 1, pipeline.duplicate());
                savePipelines();
                showPipelines();
            }
            @Override public void delete(Pipeline pipeline) {
                pipelines.remove(pipeline);
                savePipelines();
                showPipelines();
                window.toast(Toast.success("Pipeline deleted", "\u201c" + pipeline.getName() + "\u201d was removed."));
            }
        }));
    }

    /** Opens the builder on a copy; saving puts it in the list (replacing the one with the same id). */
    private void editPipeline(Pipeline copy) {
        window.show(new PipelineBuilderScreen(window, copy, SaveTo.from(settings), this::showPipelines, saved -> {
            int at = -1;
            for (int i = 0; i < pipelines.size(); i++) if (pipelines.get(i).getId().equals(saved.getId())) at = i;
            if (at >= 0) pipelines.set(at, saved);
            else pipelines.add(saved);
            savePipelines();
            showPipelines();
            window.toast(Toast.success("Pipeline saved", "\u201c" + saved.getName() + "\u201d is ready to run."));
        }));
    }

    /** Runs a pipeline through the usual tool screens; each finished run is counted. */
    private void runPipeline(Pipeline pipeline, List<Path> files) {
        toolFlow.open(new PipelineTool(pipeline, () -> Platform.runLater(() -> {
            pipeline.ranOnce(System.currentTimeMillis());
            savePipelines();
        })), files);
    }

    private void savePipelines() {
        try {
            pipelineService.save(pipelines);
        } catch (IOException e) {
            log.warn("Could not save pipelines", e);
            window.toast(Toast.error("Pipelines weren't saved", "Ndalamo couldn't write its pipelines file. Try again; if it keeps "
                    + "happening, check that the disk isn't full."));
        }
    }

    private void showSettings() {
        window.show(new SettingsScreen(window, settings, this::saveSettings, this::applyAppearance, new WordAddInCard(window),
                version(), this::showHome));
    }

    /**
     * Light, dark or the Windows setting. Colours switch at once through the stylesheet;
     * Home and an open Settings screen are rebuilt so the logo, drawn in fixed colours,
     * matches too.
     */
    private void applyAppearance(Settings.Appearance appearance) {
        Theme.apply(stage.getScene(), appearance);
        boolean onSettings = window.current() instanceof SettingsScreen;
        boolean onHome = window.current() == home;
        rebuildHome();
        if (onHome) showHome();
        if (onSettings) showSettings();
    }

    private void rebuildHome() {
        boolean showing = window.current() == home;
        home = new HomeScreen(this::showPipelines, this::showSettings, this::chooseFiles, this::openTool);
        if (showing) window.show(home);
    }

    /** The version from the app's files, or the one in the pom while running from the build. */
    private static String version() {
        String version = NdalamoApp.class.getPackage().getImplementationVersion();
        return version != null ? version : "0.1.0";
    }

    private void openTool(PdfTool tool) {
        if (tool instanceof WorkspaceTool) {
            window.show(workspace());
            return;
        }
        if (tool instanceof OfficeToPdfTool) {
            // Only this tool needs LibreOffice; if it's missing, explain before showing the tool.
            LibreOfficeDialog.whenFound(window, this::rememberLibreOffice, () -> toolFlow.open(tool, List.of()));
            return;
        }
        toolFlow.open(tool, List.of());
    }

    private WorkspaceScreen workspace() {
        if (workspace == null) {
            workspace = new WorkspaceScreen(window, settings, this::saveSettings, SaveTo.from(settings), this::showHome);
        }
        return workspace;
    }

    private void rememberLibreOffice(Path program) {
        settings.setLibreOfficePath(program.toString());
        LibreOffice.setChosenLocation(program.toString());
        saveSettings();
    }

    private void saveSettings() {
        try {
            settingsService.save(settings);
        } catch (IOException e) {
            log.warn("Could not save settings", e);
        }
    }

    // ── Files ────────────────────────────────────────────────────────────

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PDF, Office, image and Markdown files",
                        "*.pdf", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.ppt", "*.pptx",
                        "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.tif", "*.tiff", "*.md"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) filesChosen(files);
    }

    /** Files from the Choose files button or dropped on the window. */
    private void filesChosen(List<File> files) {
        // On a tool screen, dropped files join that tool's list; in the workspace they open as tabs.
        if (window.current() instanceof ToolScreen toolScreen) {
            toolScreen.addFiles(files.stream().map(File::toPath).toList());
            return;
        }
        if (window.current() == workspace && workspace != null) {
            workspace.open(files.stream().map(File::toPath).toList());
            return;
        }
        List<Path> paths = files.stream().map(File::toPath).toList();
        // Dropped again while suggestions are showing: add them and suggest for the whole set.
        if (window.current() instanceof SuggestScreen suggestions) {
            suggestions.addFiles(paths);
            return;
        }
        window.show(new SuggestScreen(window, paths, this::showHome, this::openToolWith, this::showHome));
    }

    /** A suggestion was picked: open that tool with the files it accepts (the workspace opens them as tabs). */
    private void openToolWith(PdfTool tool, List<Path> files) {
        List<Path> accepted = files.stream()
                .filter(f -> tool.fileTypes().contains(com.ndalamo.services.FileInspector.extension(f))).toList();
        if (tool instanceof WorkspaceTool) {
            window.show(workspace());
            workspace.open(accepted);
            return;
        }
        if (tool instanceof OfficeToPdfTool) {
            LibreOfficeDialog.whenFound(window, this::rememberLibreOffice, () -> toolFlow.open(tool, accepted));
            return;
        }
        toolFlow.open(tool, accepted);
    }

    public static void main(String[] args) {
        // Apache POI (PDF to Excel) logs through Log4j, which isn't set up in this app. Without this
        // it writes "could not find a logging provider" as an error; Log4j's own simple logger is enough.
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        launch(args);
    }
}
