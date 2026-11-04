package com.ndalamo.models;

import java.util.HashMap;
import java.util.Map;

/**
 * How a running tool reports progress and learns that the user pressed Cancel.
 *
 * Tools call {@code update(...)} for the whole job and {@code file(...)} for each
 * input file, as often as they like (e.g. once per page). Only about ten updates
 * a second are passed on, so a 500-page file doesn't flood the screen with
 * redraws. A finished update (100%) always gets through.
 */
public class Progress {

    /** Receives progress. Only {@link #overall} is required, so a lambda works for simple cases. */
    public interface Listener {
        /** The whole job: fraction 0–1 and a status line like "Compressing images · 62%". */
        void overall(double fraction, String status);

        /** One input file (numbered from 0): fraction 0–1 and e.g. "Done · 4.1 → 1.8 MB". */
        default void file(int index, double fraction, String status) {}
    }

    private static final long MIN_GAP_NANOS = 100_000_000; // 100 ms

    private final Listener listener;
    private volatile boolean cancelled;
    private long lastSent;
    private boolean sentAny;
    private final Map<Integer, Long> lastSentForFile = new HashMap<>();

    /** Progress that nobody listens to — for tests and for steps inside a pipeline. */
    public Progress() {
        this(null);
    }

    public Progress(Listener listener) {
        this.listener = listener;
    }

    public void update(double fraction, String status) {
        if (listener == null) return;
        double clamped = clamp(fraction);
        long now = System.nanoTime();
        if (sentAny && clamped < 1 && now - lastSent < MIN_GAP_NANOS) return;
        sentAny = true;
        lastSent = now;
        listener.overall(clamped, status);
    }

    /** Progress for one input file, numbered from 0 in the order the user listed them. */
    public void file(int index, double fraction, String status) {
        if (listener == null) return;
        double clamped = clamp(fraction);
        long now = System.nanoTime();
        Long last = lastSentForFile.get(index);
        if (last != null && clamped > 0 && clamped < 1 && now - last < MIN_GAP_NANOS) return;
        lastSentForFile.put(index, now);
        listener.file(index, clamped, status);
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

    private static double clamp(double fraction) {
        return Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
    }
}
