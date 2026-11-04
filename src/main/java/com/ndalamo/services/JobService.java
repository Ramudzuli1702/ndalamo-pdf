package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PdfTool;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs tools in the background so the window never freezes (SPEC §3.4).
 *
 * A screen calls {@link #start} and gets three callbacks — progress, done and
 * error — which always arrive on the JavaFX thread, so the screen can update
 * itself directly. The returned Progress is how the screen's Cancel button
 * stops the job.
 */
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ExecutorService workers;
    private final Executor uiThread;

    /** @param threads how many jobs may run at once — from Settings.workerThreads() */
    public JobService(int threads) {
        this(threads, Platform::runLater);
    }

    /** Tests pass {@code Runnable::run} as uiThread because there is no JavaFX window. */
    JobService(int threads, Executor uiThread) {
        // Daemon threads: a stuck job can never keep the app running after the window closes.
        this.workers = Executors.newFixedThreadPool(threads, task -> {
            Thread t = new Thread(task, "ndalamo-job");
            t.setDaemon(true);
            return t;
        });
        this.uiThread = uiThread;
    }

    /**
     * Runs a tool on the given files in the background.
     *
     * @param onProgress (fraction 0–1, status text), about ten times a second at most
     * @param onDone     the results; if the user cancelled, only the files finished so far
     * @param onError    the tool failed as a whole (per-file failures arrive in onDone instead)
     * @return call cancel() on it to stop the job
     */
    public Progress start(PdfTool tool, List<Path> inputs, Map<String, Object> options, SaveTo saveTo,
                          BiConsumer<Double, String> onProgress,
                          Consumer<List<FileResult>> onDone,
                          Consumer<Throwable> onError) {
        Progress progress = new Progress((fraction, status) ->
                uiThread.execute(() -> onProgress.accept(fraction, status)));

        workers.execute(() -> {
            if (progress.isCancelled()) {          // cancelled while still waiting in the queue
                uiThread.execute(() -> onDone.accept(List.of()));
                return;
            }
            log.info("Started {} on {} file(s)", tool.id(), inputs.size());
            try {
                List<FileResult> results = tool.run(inputs, options, saveTo, progress);
                log.info("Finished {}{}", tool.id(), progress.isCancelled() ? " (cancelled)" : "");
                uiThread.execute(() -> onDone.accept(results));
            } catch (Throwable e) {
                // Throwable, not just Exception: running out of memory on a huge PDF must
                // still reach the screen, or the progress bar would wait forever.
                if (progress.isCancelled()) {      // a tool stopping because of Cancel isn't a failure
                    uiThread.execute(() -> onDone.accept(List.of()));
                    return;
                }
                log.error("{} failed", tool.id(), e);
                uiThread.execute(() -> onError.accept(e));
            }
        });
        return progress;
    }

    /** Stops taking new jobs. Called when the app closes. */
    public void shutdown() {
        workers.shutdown();
    }
}
