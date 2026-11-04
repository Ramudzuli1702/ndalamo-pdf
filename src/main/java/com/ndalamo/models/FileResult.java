package com.ndalamo.models;

import java.nio.file.Path;

/**
 * What happened to one file when a tool ran: where the output went, the sizes
 * before and after, and a note for the result screen ("Under 2 MB").
 */
public class FileResult {

    public enum Status { SUCCESS, PARTIAL, FAILED, SKIPPED }

    private final Path input;
    private final Path output;       // null when nothing was written
    private final long sizeBefore;
    private final long sizeAfter;
    private final Status status;
    private final String note;       // plain-language explanation, may be null

    public FileResult(Path input, Path output, long sizeBefore, long sizeAfter, Status status, String note) {
        this.input = input;
        this.output = output;
        this.sizeBefore = sizeBefore;
        this.sizeAfter = sizeAfter;
        this.status = status;
        this.note = note;
    }

    /** A file that could not be processed. No output was written and the original is unchanged. */
    public static FileResult failed(Path input, long sizeBefore, String note) {
        return new FileResult(input, null, sizeBefore, 0, Status.FAILED, note);
    }

    public Path getInput() { return input; }
    public Path getOutput() { return output; }
    public long getSizeBefore() { return sizeBefore; }
    public long getSizeAfter() { return sizeAfter; }
    public Status getStatus() { return status; }
    public String getNote() { return note; }
}
