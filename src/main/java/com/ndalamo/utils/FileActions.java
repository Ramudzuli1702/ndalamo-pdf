package com.ndalamo.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/**
 * "Show in folder" and "Open" for result files. Both hand the file to Windows
 * on a background thread, so a slow Explorer never freezes the window.
 */
public final class FileActions {

    private static final Logger log = LoggerFactory.getLogger(FileActions.class);

    private FileActions() {}

    /** Opens Explorer with the file selected. */
    public static void showInFolder(Path file) {
        runInBackground(() -> new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start());
    }

    /** Opens the file in its default app (e.g. the PDF reader). */
    public static void open(Path file) {
        runInBackground(() -> Desktop.getDesktop().open(file.toFile()));
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void runInBackground(Action action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Could not hand a file to Windows", e);
            }
        }, "ndalamo-open-file");
        thread.setDaemon(true);
        thread.start();
    }
}
