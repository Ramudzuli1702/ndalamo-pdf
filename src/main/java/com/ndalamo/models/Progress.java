package com.ndalamo.models;

import java.util.function.BiConsumer;

/**
 * How a running tool reports progress and learns that the user pressed Cancel.
 *
 * Tools call {@code update(...)} as often as they like (e.g. once per page). Only
 * about ten updates a second are passed on, so a 500-page file doesn't flood the
 * screen with redraws. The final update (100%) always gets through.
 */
public class Progress {

    private static final long MIN_GAP_NANOS = 100_000_000; // 100 ms

    private final BiConsumer<Double, String> listener;
    private volatile boolean cancelled;
    private long lastSent;
    private boolean sentAny;

    /** Progress that nobody listens to — for tests and for steps inside a pipeline. */
    public Progress() {
        this(null);
    }

    /** @param listener receives (fraction 0–1, status text such as "Compressing images · 62%") */
    public Progress(BiConsumer<Double, String> listener) {
        this.listener = listener;
    }

    public void update(double fraction, String status) {
        if (listener == null) return;
        double clamped = Math.max(0, Math.min(1, fraction));
        long now = System.nanoTime();
        if (sentAny && clamped < 1 && now - lastSent < MIN_GAP_NANOS) return;
        sentAny = true;
        lastSent = now;
        listener.accept(clamped, status);
    }

    /**
     * Asks the tool to stop. Tools check {@link #isCancelled()} between pages, then
     * stop and delete any half-written output. The thread is never interrupted,
     * because cutting off file writes midway can leave broken files behind.
     */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
