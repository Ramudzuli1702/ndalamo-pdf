package com.ndalamo.models;

/**
 * One line of the watch-folder activity log: when, which file, which pipeline, and how it
 * went ("Done · 4.1 MB → 1.8 MB" or the reason it failed).
 *
 * @param time     milliseconds since 1970
 * @param output   the result's full path, or null if nothing was made
 */
public record Activity(long time, String file, String pipeline, boolean ok, String result, String output) {}
