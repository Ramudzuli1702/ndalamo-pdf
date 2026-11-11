package com.ndalamo;

import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.screens.FeedbackDialog;
import com.ndalamo.screens.HomeScreen;
import com.ndalamo.screens.SettingsScreen;
import com.ndalamo.screens.HistoryScreen;
import com.ndalamo.screens.WelcomeQuestionDialog;
import com.ndalamo.screens.WelcomeTour;
import com.ndalamo.screens.PipelineBuilderScreen;
import com.ndalamo.screens.PipelinesScreen;
import com.ndalamo.screens.Toast;
import com.ndalamo.models.Pipeline;
import com.ndalamo.services.FeedbackService;
import com.ndalamo.services.PipelineService;
import com.ndalamo.services.SingleInstance;
import com.ndalamo.services.WatchFolderService;
import com.ndalamo.screens.WatchFoldersScreen;
import com.ndalamo.screens.Tray;
import com.ndalamo.screens.ChoiceDialog;
import com.ndalamo.utils.Format;
import javafx.scene.image.Image;
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
import com.ndalamo.tools.Tools;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
    private com.ndalamo.services.HistoryService history;
    private FeedbackService feedback;
    private static SingleInstance instance;  // null when started another way (tests, mvn javafx:run without it)
    private Tray tray;                      // null when Windows has no notification area for us
    private boolean toldAboutTray;
    // Read by the watch-folder threads too, so a list that's safe to read while it changes.
    private final List<Pipeline> pipelines = new CopyOnWriteArrayList<>();
    private final WatchFolderService watchFolders =
            new WatchFolderService(() -> pipelines, () -> SaveTo.from(settings));

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                log.error("Uncaught exception on {}", thread.getName(), e));

        settings = settingsService.load();
        history = new com.ndalamo.services.HistoryService();
        // Reviews waiting from an offline moment are sent soon after start-up (and every 15 minutes).
        feedback = new FeedbackService(SettingsService.appDataFolder(), FeedbackService.configuredEndpoint());
        feedback.startRetrying();
        pipelines.addAll(pipelineService.load());
        watchFolders.load();
        watchFolders.setListener(new WatchFolderService.Listener() {
            @Override
            public void activity(com.ndalamo.models.Activity entry) {
                Platform.runLater(() -> {
                    if (window.current() instanceof WatchFoldersScreen screen) screen.refresh();
                });
            }

            @Override
            public void burstFinished(int done, int failed) {
                Platform.runLater(() -> notifyWatchResults(done, failed));
            }
        });
        watchFolders.start();
        LibreOffice.setChosenLocation(settings.getLibreOfficePath());
        jobService = new JobService(settings.workerThreads());
        log.info("Ndalamo started with {} worker thread(s)", settings.workerThreads());

        Theme.loadFonts();   // must happen before any screen is built
        home = new HomeScreen(this::showPipelines, this::showSettings, this::chooseFiles, this::openTool);
        home.showPinned(pipelines, p -> runPipeline(p, List.of()));
        refreshRecent();
        toolFlow = new ToolFlow(window, jobService, () -> SaveTo.from(settings), () -> settings.getToolDefaults(), this::showHome);
        toolFlow.previewWhen(() -> settings.isPreviewBeforeSaving());
        toolFlow.onFinished(entry -> {
            if (!entry.outputs().isEmpty()) {                      // a run that made something
                settings.setSuccessfulRuns(settings.getSuccessfulRuns() + 1);
                saveSettings();
                maybeAskForFeedback();
            }
            if (!settings.isRememberRecentFiles()) return;        // Settings › Privacy: keep no history
            history.add(entry);
            refreshRecent();
        });
        // "Save as pipeline" on the results: the builder opens with the tools just used, named after them.
        toolFlow.onSaveAsPipeline(steps -> editPipeline(new Pipeline(String.join(" + ", steps.stream()
                .map(s -> Tools.find(s.getToolId()).map(PdfTool::name).orElse(s.getToolId()).replace(" PDF", "")).toList()), steps)));
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

        stage.setTitle("NdalamoPDF");
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
        // Closing the window goes through the quit checks; with a tray icon, the app can keep watching folders.
        stage.setOnCloseRequest(e -> {
            e.consume();
            requestQuit();
        });
        if (Tray.supported()) {
            Platform.setImplicitExit(false);
            Image[] icons = LogoMark.windowIcons();
            tray = new Tray(icons[2], this::showWindow, () -> {
                showWindow();
                showWatchFolders();
            }, () -> {
                showWindow();
                requestQuit();
            });
        }
        // Opens filling the screen (the taskbar stays visible); un-maximised it's 1280 × 800.
        stage.setMaximized(true);
        stage.show();
        // Windows can swap the first "show" for the way the program was started (a shortcut says
        // "normal window"), which undoes the maximise. If the window came up smaller than the
        // screen, maximise it again; this time Windows keeps it.
        Platform.runLater(() -> {
            javafx.geometry.Rectangle2D area = javafx.stage.Screen.getScreensForRectangle(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).stream().findFirst()
                    .orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
            if (stage.getWidth() < area.getWidth() * 0.95 || stage.getHeight() < area.getHeight() * 0.95) {
                stage.setMaximized(false);
                stage.setMaximized(true);
            }
        });
        Launcher.closeSplash();                               // the logo shown while Ndalamo loaded
        openFromOutside(getParameters().getRaw().stream().map(Path::of).toList());
        // The first time Ndalamo opens (just after installing), the welcome tour shows Home and every tool.
        if (!settings.isTourSeen() && getParameters().getRaw().isEmpty()) Platform.runLater(this::startTour);
        else if (!settings.isWelcomeQuestionAsked()) {
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            wait.setOnFinished(e -> askWelcomeQuestion());
            wait.play();
        }
        if (instance != null) {
            try {
                instance.listen(files -> Platform.runLater(() -> {
                    showWindow();
                    openFromOutside(files);
                }));
            } catch (IOException e) {
                log.warn("Files opened while Ndalamo runs will start a second window", e);
            }
        }
    }

    // ── Quitting, and the tray ───────────────────────────────────────────

    /**
     * Closing the window or Quit (SPEC §6.3): unsaved workspace edits are asked about first; a task
     * still running gets "Cancel task and quit · Minimise to tray"; watch folders keep running in the
     * tray; otherwise the app quits.
     */
    private void requestQuit() {
        Runnable afterWorkspace = () -> {
            if (jobService.isBusy() || watchFolders.isBusy()) askAboutRunningTasks();
            else if (tray != null && watchFolders.folders().stream().anyMatch(com.ndalamo.models.WatchFolder::isEnabled)) hideToTray();
            else quitNow();
        };
        if (workspace != null) workspace.leave(afterWorkspace);
        else afterWorkspace.run();
    }

    private void askAboutRunningTasks() {
        List<String> lines = new java.util.ArrayList<>();
        for (JobService.Running job : jobService.running()) {
            lines.add(job.tool() + " \u00b7 " + Math.round(job.fraction() * 100) + "% \u00b7 " + job.status());
        }
        int waiting = watchFolders.waitingOrWorking();
        if (waiting > 0) lines.add(Format.count(waiting, "file", "files") + " in watch folders, waiting or being done");
        String message = String.join("\n", lines) + "\n\nQuitting now stops "
                         + (lines.size() == 1 ? "it" : "them") + ". Nothing half-finished is saved, and your original files are not changed.";
        ChoiceDialog dialog = new ChoiceDialog(window, "A task is still running", message, null);
        dialog.button("Keep working", ChoiceDialog.Style.PLAIN, x -> {});
        if (tray != null) dialog.button("Minimise to tray", ChoiceDialog.Style.PLAIN, x -> hideToTray());
        dialog.button("Cancel task and quit", ChoiceDialog.Style.DANGER, x -> cancelAndQuit());
        dialog.width(600).show();
    }

    private void cancelAndQuit() {
        jobService.cancelAll();
        window.toast(Toast.info("Stopping\u2026", "NdalamoPDF closes as soon as the task has stopped."));
        Thread waiter = new Thread(() -> {
            try {
                jobService.awaitIdle(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(this::quitNow);
        }, "ndalamo-quit");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void quitNow() {
        if (tray != null) tray.remove();
        Platform.exit();
    }

    private void hideToTray() {
        stage.hide();
        if (!toldAboutTray) {
            toldAboutTray = true;
            long watching = watchFolders.folders().stream().filter(com.ndalamo.models.WatchFolder::isEnabled).count();
            tray.notify("NdalamoPDF is still running", (watching > 0 ? "Watching " + Format.count((int) watching, "folder", "folders") + ". " : "")
                    + "Right-click the icon to open NdalamoPDF or quit.");
        }
    }

    private void showWindow() {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
    }

    /** After a burst of watch-folder files: a Windows notification, but only if the window isn't in front. */
    private void notifyWatchResults(int done, int failed) {
        if (tray == null || (stage.isShowing() && stage.isFocused() && !stage.isIconified())) return;
        List<String> parts = new java.util.ArrayList<>();
        if (done > 0) parts.add(Format.count(done, "file", "files") + " processed");
        if (failed > 0) parts.add(failed + " failed");
        tray.notify("Watch folder", String.join(" \u00b7 ", parts) + (failed > 0 ? ". Click to see why." : ""));
    }

    @Override
    public void stop() {
        watchFolders.stop(java.time.Duration.ofSeconds(10));
        if (jobService != null) jobService.shutdown();
        if (instance != null) instance.release();
        log.info("Ndalamo closed");
    }

    // ── Navigation ───────────────────────────────────────────────────────

    private void showHome() {
        window.show(home);
    }

    // ── Pipelines ────────────────────────────────────────────────────────

    private void showPipelines() {
        window.show(new PipelinesScreen(window, pipelines, watchedPipelineIds(), pipelineActions()));
    }

    private void showWatchFolders() {
        window.show(new WatchFoldersScreen(window, watchFolders, pipelines, pipelineActions()));
    }

    private java.util.Set<String> watchedPipelineIds() {
        return watchFolders.folders().stream().map(com.ndalamo.models.WatchFolder::getPipelineId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private PipelinesScreen.Actions pipelineActions() {
        return new PipelinesScreen.Actions() {
            @Override public void home() { showHome(); }
            @Override public void pipelines() { showPipelines(); }
            @Override public void watchFolders() { showWatchFolders(); }
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
        };
    }

    /** Opens the builder on a copy; saving puts it in the list (replacing the one with the same id). */
    private void editPipeline(Pipeline copy) {
        window.show(new PipelineBuilderScreen(window, copy, SaveTo.from(settings), this::showPipelines, this::linkWatchFolder, saved -> {
            int at = -1;
            for (int i = 0; i < pipelines.size(); i++) if (pipelines.get(i).getId().equals(saved.getId())) at = i;
            if (at >= 0) pipelines.set(at, saved);
            else pipelines.add(saved);
            savePipelines();
            showPipelines();
            window.toast(Toast.success("Pipeline saved", "\u201c" + saved.getName() + "\u201d is ready to run."));
        }));
    }

    /** "Link to a watch folder" in the builder: the pipeline has to be saved first, so the folder can find it. */
    private void linkWatchFolder(Pipeline pipeline) {
        boolean saved = pipelines.stream().anyMatch(p -> p.getId().equals(pipeline.getId()));
        if (!saved) {
            window.toast(Toast.info("Save the pipeline first", "Then a folder can be set to run it."));
            return;
        }
        WatchFoldersScreen screen = new WatchFoldersScreen(window, watchFolders, pipelines, pipelineActions());
        window.show(screen);
        screen.add(pipeline);
    }

    /** Runs a pipeline through the usual tool screens; each finished run is counted. */
    private void runPipeline(Pipeline pipeline, List<Path> files) {
        toolFlow.open(new PipelineTool(pipeline, () -> Platform.runLater(() -> {
            pipeline.ranOnce(System.currentTimeMillis());
            savePipelines();
        })), files);
    }

    private void savePipelines() {
        home.showPinned(pipelines, p -> runPipeline(p, List.of()));
        try {
            pipelineService.save(pipelines);
        } catch (IOException e) {
            log.warn("Could not save pipelines", e);
            window.toast(Toast.error("Pipelines weren't saved", "NdalamoPDF couldn't write its pipelines file. Try again; if it keeps "
                    + "happening, check that the disk isn't full."));
        }
    }

    private void showSettings() {
        SettingsScreen screen = new SettingsScreen(window, settings, this::saveSettings, this::applyAppearance, new WordAddInCard(window),
                version(), this::showHome);
        screen.setOnClearRecent(() -> {
            history.clear();
            refreshRecent();
        });
        screen.setOnShowTour(() -> {
            showHome();
            Platform.runLater(this::startTour);
        });
        if (feedback.available()) screen.setOnFeedback(() -> showFeedback(false));
        window.show(screen);
    }

    // ── Tell us what you think ───────────────────────────────────────────

    /**
     * After a successful run, now and then (FeedbackService.shouldAsk): the review box, a moment
     * after the results appear so they're seen first, and only if nothing else is open.
     */
    private void maybeAskForFeedback() {
        if (!feedback.available() || !FeedbackService.shouldAsk(settings, java.time.LocalDate.now())) return;
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        wait.setOnFinished(e -> {
            if (window.hasDialog()) return;
            settings.setFeedbackLastAsked(java.time.LocalDate.now().toString());
            settings.setRunsAtLastAsk(settings.getSuccessfulRuns());
            saveSettings();
            showFeedback(true);
        });
        wait.play();
    }

    private void showFeedback(boolean offered) {
        window.showDialog(new FeedbackDialog(window, offered, this::submitFeedback, () -> {}, () -> {
            settings.setFeedbackNeverAsk(true);
            saveSettings();
        }));
    }

    /** Queues the review and tries to send it straight away, off the JavaFX thread. */
    private void submitFeedback(int stars, String text) {
        settings.setFeedbackGiven(true);
        saveSettings();
        FeedbackService.Review review = FeedbackService.review(stars, text, version());
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                feedback.add(review);
            } catch (IOException e) {
                log.warn("Could not keep the review", e);
                Platform.runLater(() -> window.toast(Toast.error("Your review couldn't be saved",
                        "Nothing was sent. Try again from Settings › Help › Tell us what you think.")));
                return;
            }
            feedback.sendWaiting();
            boolean waiting = !feedback.waiting().isEmpty();
            Platform.runLater(() -> window.toast(Toast.success("Thank you for your review",
                    waiting ? "You seem to be offline, so it'll be sent as soon as you're connected."
                            : "It's on its way to the NdalamoPDF team.")));
        });
    }

    // ── The welcome tour ─────────────────────────────────────────────────

    private void startTour() {
        if (window.current() != home) showHome();
        new WelcomeTour(window, home, () -> {
            settings.setTourSeen(true);
            saveSettings();
            Platform.runLater(this::askWelcomeQuestion);           // right after the first tour
        }).start();
    }

    /**
     * The welcome question, once: who's using NdalamoPDF and what for. Shown after the first tour;
     * people who saw the tour in an earlier version get it once, a moment after start-up.
     */
    private void askWelcomeQuestion() {
        if (settings.isWelcomeQuestionAsked() || !feedback.available() || window.hasDialog()) return;
        settings.setWelcomeQuestionAsked(true);
        saveSettings();
        window.showDialog(new WelcomeQuestionDialog(window, (role, uses) -> {
            FeedbackService.About answers = FeedbackService.about(role, uses, version());
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    feedback.add(answers);
                } catch (IOException e) {
                    log.warn("Could not keep the welcome answers", e);
                    return;
                }
                feedback.sendWaiting();
            });
            window.toast(Toast.success("Thank you", "That helps make NdalamoPDF better for people like you."));
        }, () -> {}));
    }

    // ── History and recent files ─────────────────────────────────────────

    /** Fills Home's recent files and tools from History. */
    private void refreshRecent() {
        if (home == null) return;
        List<PdfTool> tools = history.recentTools(6).stream()
                .map(Tools::find).filter(java.util.Optional::isPresent).map(java.util.Optional::get).toList();
        home.showRecent(history.recentFiles(6), tools, path -> {
            if (java.nio.file.Files.isRegularFile(path)) filesChosen(List.of(path.toFile()));
            else window.toast(Toast.info(path.getFileName() + " isn't there any more",
                    "It was moved, renamed or deleted. Nothing else was changed."));
        }, this::showHistory);
    }

    private void showHistory() {
        window.show(new HistoryScreen(window, history.entries(), settings.isRememberRecentFiles(), new HistoryScreen.Actions() {
            @Override public void home() { showHome(); }

            @Override public void clear() {
                history.clear();
                refreshRecent();
                showHistory();
                window.toast(Toast.success("History cleared", "Your files themselves were not touched."));
            }

            @Override public void useAgain(com.ndalamo.models.HistoryEntry entry) {
                List<Path> files = entry.inputs().stream().map(Path::of).filter(java.nio.file.Files::isRegularFile).toList();
                if (files.isEmpty()) {
                    window.toast(Toast.info("The files aren't there any more", "They were moved, renamed or deleted."));
                    return;
                }
                if (entry.toolId().equals("pipeline")) {
                    pipelines.stream().filter(p -> p.getName().equals(entry.toolName())).findFirst()
                            .ifPresentOrElse(p -> runPipeline(p, files),
                                    () -> window.toast(Toast.info("That pipeline is gone", "It was deleted or renamed.")));
                    return;
                }
                Tools.find(entry.toolId()).ifPresent(tool -> openToolWith(tool, files));
            }
        }));
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
        home.showPinned(pipelines, p -> runPipeline(p, List.of()));
        refreshRecent();
        if (showing) window.show(home);
    }

    /** The version from the app's files, or the one in the pom while running from the build. */
    private static String version() {
        String version = NdalamoApp.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.1";
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
        window.show(new SuggestScreen(window, paths, this::showHome, this::openToolWith, this::showHome,
                pipelines, this::runPipeline));
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
        // One copy at a time: a second one (say, "Open with" on a PDF) hands its files to the running one.
        SingleInstance only = new SingleInstance(SettingsService.appDataFolder());
        if (!only.claim()) {
            Launcher.closeSplash();                           // no window from this copy
            try {
                only.handOver(List.of(args));
            } catch (IOException e) {
                log.warn("Could not pass files to the running Ndalamo", e);
            }
            return;
        }
        instance = only;
        // Apache POI (PDF to Excel) logs through Log4j, which isn't set up in this app. Without this
        // it writes "could not find a logging provider" as an error; Log4j's own simple logger is enough.
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        launch(args);
    }

    /** Files given when starting (Explorer's "Open with Ndalamo"), or by a second copy later. */
    private void openFromOutside(List<Path> files) {
        List<File> existing = files.stream().filter(java.nio.file.Files::isRegularFile).map(Path::toFile).toList();
        if (!existing.isEmpty()) filesChosen(existing);
    }
}
