package com.ndalamo.screens;

import com.ndalamo.models.Pipeline;
import com.ndalamo.services.PipelineService;
import com.ndalamo.services.PipelineTool;
import com.ndalamo.services.PipelineValidator;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.services.JobService;
import com.ndalamo.services.StagedResults;
import javafx.application.Platform;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.ToolProblem;
import com.ndalamo.utils.FileActions;
import com.ndalamo.utils.Format;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Takes one tool from start to finish:
 *
 *   ToolScreen (files + options) ─run─► ProgressScreen ─done─► PreviewScreen ─save─► ResultScreen
 *                                            │ Run in background
 *                                            └─► Home + a toast with a progress bar,
 *                                                which becomes a result toast at the end
 *
 * With "Preview results before saving" on (the default), the tool writes into a private
 * staging folder (StagedResults) and the preview shows the results; only Save puts them
 * where Settings say. Discard deletes them and goes back to the tool screen. With it off,
 * results are saved straight away and the preview is skipped.
 *
 * Cancel goes back to the tool screen with the same files. If the whole run fails,
 * the tool screen comes back with an error dialog and a "Try again" button.
 */
public class ToolFlow {

    private final MainWindow window;
    private final JobService jobs;
    private final Supplier<SaveTo> saveTo;      // read fresh each time, so Settings changes apply
    private final Runnable showHome;
    private final Supplier<Map<String, String>> toolDefaults;

    public ToolFlow(MainWindow window, JobService jobs, Supplier<SaveTo> saveTo, Runnable showHome) {
        this(window, jobs, saveTo, Map::of, showHome);
    }

    /** @param toolDefaults the choices tools start with, from Settings; read fresh each time a tool opens */
    public ToolFlow(MainWindow window, JobService jobs, Supplier<SaveTo> saveTo, Supplier<Map<String, String>> toolDefaults,
                    Runnable showHome) {
        this.window = window;
        this.jobs = jobs;
        this.saveTo = saveTo;
        this.toolDefaults = toolDefaults;
        this.showHome = showHome;
    }

    /** Opens a tool's first screen, optionally with files already added. */
    /**
     * The tools just used one after another on each other's results (via "Continue with these
     * files"), for "Save as pipeline". A run on other files starts a new chain.
     */
    private List<Pipeline.Step> chain = new ArrayList<>();
    private Set<Path> lastOutputs = Set.of();
    private Consumer<List<Pipeline.Step>> onSaveAsPipeline = steps -> {};
    private Consumer<com.ndalamo.models.HistoryEntry> onFinished = entry -> {};
    private Supplier<Boolean> previewBeforeSaving = () -> false;

    /** Whether runs show their results before saving them (Settings › Saving), read fresh for each run. */
    public void previewWhen(Supplier<Boolean> setting) {
        this.previewBeforeSaving = setting;
    }

    /** Told about each finished run (for History and the recent files and tools on Home). */
    public void onFinished(Consumer<com.ndalamo.models.HistoryEntry> handler) {
        this.onFinished = handler;
    }

    /** What "Save as pipeline" on the results does with the steps just used. */
    public void onSaveAsPipeline(Consumer<List<Pipeline.Step>> handler) {
        this.onSaveAsPipeline = handler;
    }

    public void open(PdfTool tool, List<Path> files) {
        ToolScreen[] screen = new ToolScreen[1];
        screen[0] = new ToolScreen(tool, files, saveTo.get(), toolDefaults.get(), window, showHome,
                (chosen, options, target) -> run(tool, screen[0], chosen, options, target));
        window.show(screen[0]);
    }

    private void run(PdfTool tool, ToolScreen toolScreen, List<Path> files, Map<String, Object> options, SaveTo target) {
        List<Pipeline.Step> steps = chainWith(tool, files, options);
        Path stage = previewBeforeSaving.get() ? stagingFolder() : null;
        SaveTo runTarget = stage != null ? StagedResults.staging(target, stage) : target;
        Progress[] job = new Progress[1];               // filled in once the job starts
        ProgressScreen[] progressScreen = new ProgressScreen[1];
        Toast[] backgroundToast = new Toast[1];

        Runnable cancel = () -> {
            job[0].cancel();
            progressScreen[0].showCancelling();         // the screen changes once the tool has actually stopped
        };
        Runnable runInBackground = () -> {
            showHome.run();
            backgroundToast[0] = Toast.background(tool.workingLabel(files.size()), "",
                    new Toast.Action("Show", () -> window.show(progressScreen[0])),
                    new Toast.Action("Cancel", cancel));
            window.toast(backgroundToast[0]);
        };
        progressScreen[0] = new ProgressScreen(tool.name(), tool.workingLabel(files.size()),
                tool.oneOutput() ? List.of() : files, cancel, runInBackground);
        window.show(progressScreen[0]);

        job[0] = jobs.start(tool, files, options, runTarget,
                new Progress.Listener() {
                    @Override
                    public void overall(double fraction, String status) {
                        progressScreen[0].update(fraction, status);
                        if (backgroundToast[0] != null) backgroundToast[0].setProgress(fraction, status);
                    }

                    @Override
                    public void file(int index, double fraction, String status) {
                        progressScreen[0].updateFile(index, fraction, status);
                    }
                },
                results -> {
                    if (backgroundToast[0] != null) backgroundToast[0].dismiss();
                    boolean cancelled = job[0].isCancelled();
                    boolean anyOutput = results.stream().anyMatch(r -> r.getOutput() != null);
                    if (cancelled && results.isEmpty()) {
                        StagedResults.discard(stage);
                        window.show(toolScreen);
                        window.toast(Toast.info(tool.name() + " cancelled",
                                "Nothing was saved and your files were not changed."));
                    } else if (stage != null && anyOutput) {
                        // Check before saving: nothing is in the user's folders yet.
                        PreviewScreen preview = preview(tool, toolScreen, files, results, stage, target,
                                cancelled ? List.of() : steps);
                        if (window.current() == progressScreen[0]) {
                            window.show(preview);
                            if (cancelled) window.toast(Toast.info("Stopped early",
                                    "The finished files are shown. Nothing is saved until you choose Save."));
                        } else {
                            window.toast(Toast.success(tool.name() + " is ready to check",
                                    "Nothing is saved until you choose Save.",
                                    new Toast.Action("Check and save", () -> window.show(preview))));
                        }
                    } else {
                        StagedResults.discard(stage);
                        finished(tool, files, results, cancelled ? List.of() : steps);
                        if (cancelled) {
                            // Stopped partway through a batch: keep what was finished.
                            showResults(tool, results, files.size(), List.of());
                            window.toast(Toast.info("Stopped early",
                                    "The files listed were finished and saved; the rest were left as they were."));
                        } else if (window.current() == progressScreen[0]) {
                            showResults(tool, results, files.size(), steps);
                        } else {
                            window.toast(resultToast(tool, results, files.size()));
                        }
                    }
                },
                error -> {
                    if (backgroundToast[0] != null) backgroundToast[0].dismiss();
                    window.show(toolScreen);
                    // A tool that knows what went wrong says so in plain words; otherwise a general message.
                    String message = error instanceof ToolProblem problem ? problem.getMessage()
                            : "Something went wrong while working on your files. Your original files were not changed. "
                                    + "You can try again; if it keeps happening, copy the technical details.";
                    window.showDialog(new ErrorDialog(window, "Couldn’t finish " + tool.name(), message,
                            error, "Try again", () -> run(tool, toolScreen, files, options, target)));
                });
    }

    /** A new staging folder, or null (save straight away) if %TEMP% can't be used. */
    private static Path stagingFolder() {
        try {
            return StagedResults.newFolder();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The preview of staged results, whose Save puts them in place and Discard deletes them. */
    private PreviewScreen preview(PdfTool tool, ToolScreen toolScreen, List<Path> files, List<FileResult> staged,
                                  Path stage, SaveTo target, List<Pipeline.Step> steps) {
        PreviewScreen[] screen = new PreviewScreen[1];
        Runnable save = () -> {
            // Copying can take a moment for big files on another drive: off the screen's thread.
            Thread copier = new Thread(() -> {
                try {
                    List<FileResult> saved = StagedResults.save(staged, stage, target, files);
                    Platform.runLater(() -> {
                        finished(tool, files, saved, steps);
                        showResults(tool, saved, files.size(), steps);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> screen[0].saveFailed("Couldn\u2019t save the results (" + e.getMessage()
                            + "). They are still here and your originals are unchanged. Try again, or pick another "
                            + "folder in Settings \u203a Saving."));
                }
            }, "ndalamo-save-results");
            copier.setDaemon(true);
            copier.start();
        };
        Runnable discard = () -> {
            StagedResults.discard(stage);
            window.show(toolScreen);
            window.toast(Toast.info("Nothing was saved",
                    "The results were discarded. Your original files were not changed."));
        };
        Runnable home = () -> {
            StagedResults.discard(stage);
            showHome.run();
        };
        screen[0] = new PreviewScreen(tool, staged, target.describeFolder(tool.oneOutput()), window, home, save, discard);
        return screen[0];
    }

    /** Records a finished run (History, recent files, the chain for "Save as pipeline"). */
    private void finished(PdfTool tool, List<Path> files, List<FileResult> results, List<Pipeline.Step> steps) {
        chain = steps;
        if (!results.isEmpty()) {
            onFinished.accept(new com.ndalamo.models.HistoryEntry(System.currentTimeMillis(), tool.id(), tool.name(),
                    files.stream().map(f -> f.toAbsolutePath().toString()).toList(),
                    results.stream().map(FileResult::getOutput).filter(p -> p != null)
                            .map(p -> p.toAbsolutePath().toString()).toList()));
        }
        lastOutputs = results.stream().map(FileResult::getOutput).filter(p -> p != null)
                .map(p -> p.toAbsolutePath().normalize()).collect(Collectors.toSet());
    }

    private void showResults(PdfTool tool, List<FileResult> results, int inputCount, List<Pipeline.Step> steps) {
        window.show(new ResultScreen(tool, results, inputCount, window, showHome, this::open, steps,
                () -> onSaveAsPipeline.accept(steps)));
    }

    /**
     * The chain including this run: added to the last one if these files are what the last run
     * made, otherwise just this step. Empty for tools that can't be pipeline steps (and pipelines).
     */
    private List<Pipeline.Step> chainWith(PdfTool tool, List<Path> files, Map<String, Object> options) {
        if (tool instanceof PipelineTool || PipelineValidator.whyNotInPipeline(tool) != null) return List.of();
        Pipeline.Step step = new Pipeline.Step(tool.id(), PipelineService.toSaved(tool, options));
        boolean continuing = !chain.isEmpty() && !files.isEmpty()
                             && files.stream().allMatch(f -> lastOutputs.contains(f.toAbsolutePath().normalize()));
        List<Pipeline.Step> steps = new ArrayList<>(continuing ? chain : List.of());
        steps.add(step);
        return steps;
    }

    /** The toast shown when a run finishes while the user is elsewhere in the app. */
    private Toast resultToast(PdfTool tool, List<FileResult> results, int inputCount) {
        List<Path> outputs = results.stream().map(FileResult::getOutput).filter(p -> p != null).toList();
        Toast.Action details = new Toast.Action("View details", () -> showResults(tool, results, inputCount, List.of()));
        if (outputs.isEmpty()) {
            return Toast.error("Couldn’t finish " + tool.name(), "Your original files were not changed.", details);
        }
        long after = results.stream().mapToLong(FileResult::getSizeAfter).sum();
        boolean partial = results.stream().anyMatch(r -> r.getStatus() != FileResult.Status.SUCCESS);
        Path first = outputs.get(0);
        Toast.Action show = new Toast.Action("Show in folder",
                () -> MessageDialog.ifFileExists(window, first, () -> FileActions.showInFolder(first)));
        Toast.Action open = new Toast.Action("Open",
                () -> MessageDialog.ifFileExists(window, first, () -> FileActions.open(first)));
        return partial
                ? Toast.partial(tool.doneLabel(inputCount), "Some files were skipped.", details, show)
                : Toast.success(tool.doneLabel(inputCount), Format.size(after) + " · saved", show, open);
    }
}
