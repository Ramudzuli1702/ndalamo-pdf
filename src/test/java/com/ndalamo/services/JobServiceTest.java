package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.tools.PdfTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobServiceTest {

    // No JavaFX window in tests, so callbacks run straight on the worker thread.
    private final JobService jobs = new JobService(1, Runnable::run);

    @AfterEach
    void stop() {
        jobs.shutdown();
    }

    /** A stand-in tool whose work is whatever the test passes in. */
    private interface Work {
        List<FileResult> run(Progress progress) throws Exception;
    }

    private static PdfTool fakeTool(Work work) {
        return new PdfTool() {
            public String id() { return "fake"; }
            public String name() { return "Fake tool"; }
            public String description() { return "For tests"; }
            public String category() { return "Organize"; }
            public List<OptionField> optionFields() { return List.of(); }
            public List<FileResult> run(List<Path> inputs, Map<String, Object> options, Progress progress)
                    throws Exception {
                return work.run(progress);
            }
        };
    }

    private CompletableFuture<List<FileResult>> start(PdfTool tool) {
        CompletableFuture<List<FileResult>> done = new CompletableFuture<>();
        jobs.start(tool, List.of(), Map.of(), (f, s) -> {}, done::complete, done::completeExceptionally);
        return done;
    }

    @Test
    void deliversTheToolsResults() throws Exception {
        FileResult result = new FileResult(Path.of("a.pdf"), Path.of("a_out.pdf"), 100, 50,
                FileResult.Status.SUCCESS, null);

        List<FileResult> results = start(fakeTool(p -> List.of(result))).get(10, TimeUnit.SECONDS);

        assertEquals(List.of(result), results);
    }

    @Test
    void cancelStopsARunningTool() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger pagesDone = new AtomicInteger();
        CompletableFuture<List<FileResult>> done = new CompletableFuture<>();

        Progress progress = jobs.start(fakeTool(p -> {
            started.countDown();
            for (int page = 0; page < 1_000_000 && !p.isCancelled(); page++) {
                pagesDone.incrementAndGet();
                Thread.sleep(1);
            }
            return List.of();
        }), List.of(), Map.of(), (f, s) -> {}, done::complete, done::completeExceptionally);

        assertTrue(started.await(10, TimeUnit.SECONDS));
        progress.cancel();

        done.get(10, TimeUnit.SECONDS);
        assertTrue(pagesDone.get() < 1_000_000, "tool should have stopped early");
    }

    @Test
    void cancelledJobThatHasNotStartedNeverRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();

        // One worker thread: the second job waits in the queue behind the first.
        start(fakeTool(p -> {
            release.await();
            return List.of();
        }));
        CompletableFuture<List<FileResult>> done = new CompletableFuture<>();
        Progress queued = jobs.start(fakeTool(p -> {
            secondRan.set(true);
            return List.of();
        }), List.of(), Map.of(), (f, s) -> {}, done::complete, done::completeExceptionally);

        queued.cancel();
        release.countDown();

        assertEquals(List.of(), done.get(10, TimeUnit.SECONDS));
        assertFalse(secondRan.get());
    }

    @Test
    void reportsAToolThatFails() {
        IllegalStateException boom = new IllegalStateException("boom");

        CompletableFuture<List<FileResult>> done = start(fakeTool(p -> { throw boom; }));

        Exception e = assertThrows(Exception.class, () -> done.get(10, TimeUnit.SECONDS));
        assertSame(boom, e.getCause());
    }

    @Test
    void progressIsThrottledButTheFinalUpdateArrives() throws Exception {
        List<Double> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<List<FileResult>> done = new CompletableFuture<>();

        jobs.start(fakeTool(p -> {
            for (int i = 1; i <= 10_000; i++) p.update(i / 10_000.0, "Page " + i);
            return List.of();
        }), List.of(), Map.of(), (f, s) -> received.add(f), done::complete, done::completeExceptionally);

        done.get(10, TimeUnit.SECONDS);
        assertTrue(received.size() < 10_000);
        assertEquals(1.0, received.get(received.size() - 1));
    }
}
