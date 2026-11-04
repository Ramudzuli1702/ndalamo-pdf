package com.ndalamo.screens;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.services.JobService;
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
 *   ToolScreen (files + options) ─run─► ProgressScreen ─done─► ResultScreen
 *                                            │ Run in background
 *                                            └─► Home + a toast with a progress bar,
 *                                                which becomes a result toast at the end
 *
 * Cancel goes back to the tool screen with the same files. If the whole run fails,
 * the tool screen comes back with an error dialog and a "Try again" button.
 */
public class ToolFlow {

    private final MainWindow window;
    private final JobService jobs;
    private final Supplier<SaveTo> saveTo;      // read fresh each time, so Settings changes apply
    private final Runnable showHome;

    public ToolFlow(MainWindow window, JobService jobs, Supplier<SaveTo> saveTo, Runnable showHome) {
        this.window = window;
        this.jobs = jobs;
        this.saveTo = saveTo;
        this.showHome = showHome;
    }

    /** Opens a tool's first screen, optionally with files already added. */
    public void open(PdfTool tool, List<Path> files) {
        ToolScreen[] screen = new ToolScreen[1];
        screen[0] = new ToolScreen(tool, files, saveTo.get(), window, showHome,
                (chosen, options, target) -> run(tool, screen[0], chosen, options, target));
        window.show(screen[0]);
    }

    private void run(PdfTool tool, ToolScreen toolScreen, List<Path> files, Map<String, Object> options, SaveTo target) {
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

        job[0] = jobs.start(tool, files, options, target,
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
                    if (job[0].isCancelled() && results.isEmpty()) {
                        window.show(toolScreen);
                        window.toast(Toast.info(tool.name() + " cancelled",
                                "Nothing was saved and your files were not changed."));
                    } else if (job[0].isCancelled()) {
                        // Stopped partway through a batch: keep what was finished.
                        showResults(tool, results, files.size());
                        window.toast(Toast.info("Stopped early",
                                "The files listed were finished and saved; the rest were left as they were."));
                    } else if (window.current() == progressScreen[0]) {
                        showResults(tool, results, files.size());
                    } else {
                        window.toast(resultToast(tool, results, files.size()));
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

    private void showResults(PdfTool tool, List<FileResult> results, int inputCount) {
        window.show(new ResultScreen(tool, results, inputCount, window, showHome, this::open));
    }

    /** The toast shown when a run finishes while the user is elsewhere in the app. */
    private Toast resultToast(PdfTool tool, List<FileResult> results, int inputCount) {
        List<Path> outputs = results.stream().map(FileResult::getOutput).filter(p -> p != null).toList();
        Toast.Action details = new Toast.Action("View details", () -> showResults(tool, results, inputCount));
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
