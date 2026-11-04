package com.ndalamo.models;

/**
 * What the app knows about a file before any tool runs: its size, page count and
 * whether it can be opened. Filled in by FileInspector.
 */
public class FileInfo {

    private final long size;
    private final int pages;                 // 0 if unknown or not a PDF/image
    private final boolean passwordProtected;
    private final boolean damaged;
    private final boolean restricted;        // opens without a password, but printing/copying is limited

    public FileInfo(long size, int pages, boolean passwordProtected, boolean damaged) {
        this(size, pages, passwordProtected, damaged, false);
    }

    public FileInfo(long size, int pages, boolean passwordProtected, boolean damaged, boolean restricted) {
        this.size = size;
        this.pages = pages;
        this.passwordProtected = passwordProtected;
        this.damaged = damaged;
        this.restricted = restricted;
    }

    public long getSize() { return size; }
    public int getPages() { return pages; }
    public boolean isPasswordProtected() { return passwordProtected; }
    public boolean isDamaged() { return damaged; }
    public boolean isRestricted() { return restricted; }
}
