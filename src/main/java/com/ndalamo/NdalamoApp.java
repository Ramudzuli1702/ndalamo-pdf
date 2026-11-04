package com.ndalamo;

import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.screens.ComingSoonScreen;
import com.ndalamo.screens.HomeScreen;
import com.ndalamo.screens.LogoMark;
import com.ndalamo.screens.MainWindow;
import com.ndalamo.screens.ToolFlow;
import com.ndalamo.screens.ToolScreen;
import com.ndalamo.services.JobService;
import com.ndalamo.services.SettingsService;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.utils.Theme;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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
    private JobService jobService;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                log.error("Uncaught exception on {}", thread.getName(), e));

        settings = new SettingsService().load();
        jobService = new JobService(settings.workerThreads());
        log.info("Ndalamo started with {} worker thread(s)", settings.workerThreads());

        Theme.loadFonts();   // must happen before any screen is built
        home = new HomeScreen(this::showPipelines, this::showSettings, this::chooseFiles, this::openTool);
        toolFlow = new ToolFlow(window, jobService, () -> SaveTo.from(settings), this::showHome);
        window.show(home);

        Scene scene = new Scene(window, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        // Ctrl+K: jump to the tool search from any screen
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), () -> {
            showHome();
            home.focusSearch();
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

    private void showPipelines() {
        window.show(new ComingSoonScreen("Pipelines",
                "Saved chains of tools, like OCR → Compress → Protect, are built in chunk 16.",
                List.of(), this::showHome));
    }

    private void showSettings() {
        window.show(new ComingSoonScreen("Settings",
                "The full settings screen is built in chunk 18. Your settings are already saved in "
                        + SettingsService.appDataFolder() + ".",
                List.of(), this::showHome));
    }

    private void openTool(PdfTool tool) {
        toolFlow.open(tool, List.of());
    }

    // ── Files ────────────────────────────────────────────────────────────

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PDF, Office and image files",
                        "*.pdf", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.ppt", "*.pptx",
                        "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.tif", "*.tiff"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) filesChosen(files);
    }

    /** Files from the Choose files button or dropped on the window. */
    private void filesChosen(List<File> files) {
        // On a tool screen, dropped files join that tool's list.
        if (window.current() instanceof ToolScreen toolScreen) {
            toolScreen.addFiles(files.stream().map(File::toPath).toList());
            return;
        }
        // Suggested tools for these files (screen 3) are built in chunk 15.
        window.show(new ComingSoonScreen("Suggested tools",
                "Ndalamo will suggest the right tools for these files. That screen is built in chunk 15. "
                        + "Your files have not been changed.",
                files.stream().map(File::getName).toList(), this::showHome));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
