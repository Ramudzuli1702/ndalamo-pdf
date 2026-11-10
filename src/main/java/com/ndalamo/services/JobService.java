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

    /** A job that's running or waiting: its tool's name and its latest progress (for the quit dialog). */
    public record Running(String tool, double fraction, String status) {}

    private final Map<Progress, Running> running = new java.util.concurrent.ConcurrentHashMap<>();

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
     * @param onProgress the whole job and each file, about ten times a second at most
     * @param onDone     the results; if the user cancelled, only the files finished so far
     * @param onError    the tool failed as a whole (per-file failures arrive in onDone instead)
     * @return call cancel() on it to stop the job
     */
    public Progress start(PdfTool tool, List<Path> inputs, Map<String, Object> options, SaveTo saveTo,
                          Progress.Listener onProgress,
                          Consumer<List<FileResult>> onDone,
                          Consumer<Throwable> onError) {
        Progress[] self = new Progress[1];
        Progress progress = new Progress(new Progress.Listener() {
            @Override
            public void overall(double fraction, String status) {
                if (self[0] != null) running.computeIfPresent(self[0], (k, v) -> new Running(v.tool(), fraction, status));
                uiThread.execute(() -> onProgress.overall(fraction, status));
            }

            @Override
            public void file(int index, double fraction, String status) {
                uiThread.execute(() -> onProgress.file(index, fraction, status));
            }
        });

        self[0] = progress;
        running.put(progress, new Running(tool.name(), 0, "Waiting to start"));
        workers.execute(() -> {
            try {
                runJob(tool, inputs, options, saveTo, progress, onDone, onError);
            } finally {
                running.remove(progress);
            }
        });
        return progress;
    }

    private void runJob(PdfTool tool, List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress,
                        Consumer<List<FileResult>> onDone, Consumer<Throwable> onError) {
        {
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
        }
    }

    /** The jobs running or waiting now. */
    public List<Running> running() {
        return List.copyOf(running.values());
    }

    public boolean isBusy() {
        return !running.isEmpty();
    }

    /** Asks every job to stop (each finishes its current page and leaves nothing half-written). */
    public void cancelAll() {
        running.keySet().forEach(Progress::cancel);
    }

    /** Waits until no job is running, up to {@code millis}. True if they all finished. */
    public boolean awaitIdle(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (isBusy() && System.currentTimeMillis() < end) Thread.sleep(50);
        return !isBusy();
    }

    /** Stops taking new jobs. Called when the app closes. */
    public void shutdown() {
        workers.shutdown();
    }
}
