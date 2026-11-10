package com.ndalamo.services;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps Ndalamo to one copy at a time. Two copies would both process the same watch
 * folders, and opening a PDF with "Open with" while Ndalamo runs should show it in the
 * window already open.
 *
 * The first copy holds a lock on a file in %APPDATA%\Ndalamo. A second copy can't get the
 * lock, so it writes the files it was given into the "open" folder there and quits; the first
 * copy sees the note and opens them. Only files are used, never a network connection.
 */
public final class SingleInstance {

    private final Path folder;
    private FileChannel channel;
    private FileLock lock;
    private WatchService watcher;

    public SingleInstance(Path appFolder) {
        this.folder = appFolder;
    }

    /** True if no other Ndalamo is running; this copy then holds the lock until it quits. */
    public boolean claim() {
        try {
            Files.createDirectories(folder);
            channel = FileChannel.open(folder.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            return lock != null;
        } catch (OverlappingFileLockException e) {
            return false;               // held by this same program (only happens in tests)
        } catch (IOException e) {
            return true;                // can't tell: better to start than to do nothing
        }
    }

    /**
     * For a second copy: leaves the files it was asked to open for the running one. With no
     * files the running copy just comes to the front.
     */
    public void handOver(List<String> files) throws IOException {
        Path inbox = Files.createDirectories(folder.resolve("open"));
        List<String> paths = new ArrayList<>();
        for (String file : files) paths.add(Path.of(file).toAbsolutePath().toString());
        // Written under another name first, so the running copy never reads half a note.
        Path temp = inbox.resolve(UUID.randomUUID() + ".writing");
        Files.write(temp, paths, StandardCharsets.UTF_8);
        Files.move(temp, inbox.resolve(UUID.randomUUID() + ".txt"), StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * For the running copy: calls back (on a background thread) with the files from each note a
     * second copy leaves. Notes left while no copy was running are cleared, not acted on.
     */
    public void listen(Consumer<List<Path>> onRequest) throws IOException {
        Path inbox = Files.createDirectories(folder.resolve("open"));
        try (DirectoryStream<Path> old = Files.newDirectoryStream(inbox)) {
            for (Path note : old) Files.deleteIfExists(note);
        }
        watcher = FileSystems.getDefault().newWatchService();
        inbox.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    WatchKey key = watcher.take();
                    key.pollEvents();
                    try (DirectoryStream<Path> notes = Files.newDirectoryStream(inbox, "*.txt")) {
                        for (Path note : notes) {
                            List<Path> files = new ArrayList<>();
                            for (String line : Files.readAllLines(note, StandardCharsets.UTF_8)) {
                                if (!line.isBlank()) files.add(Path.of(line.strip()));
                            }
                            Files.deleteIfExists(note);
                            onRequest.accept(files);
                        }
                    } catch (IOException e) {
                        // try again on the next note
                    }
                    if (!key.reset()) return;
                }
            } catch (ClosedWatchServiceException | InterruptedException e) {
                // quitting
            }
        }, "ndalamo-single-instance");
        thread.setDaemon(true);
        thread.start();
    }

    /** Lets the next copy start (when quitting). */
    public void release() {
        try {
            if (watcher != null) watcher.close();
            if (lock != null) lock.release();
            if (channel != null) channel.close();
        } catch (IOException e) {
            // quitting anyway
        }
    }
}
