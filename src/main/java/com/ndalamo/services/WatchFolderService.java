package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.ndalamo.models.Activity;
import com.ndalamo.models.FileResult;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.models.WatchFolder;
import com.ndalamo.utils.Format;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Watches folders and runs each new file through the folder's pipeline (SPEC §5).
 *
 * - Windows reports new files through a WatchService; every 10 s the folders are also
 *   scanned, because network drives don't always report changes.
 * - A file is only touched once it's finished arriving: the same size for 2 s and openable
 *   with nobody else holding it (a scanner or copy still writing keeps it locked).
 * - Results go to the output folder. The original then moves to "processed" inside the
 *   watched folder (or stays, if the folder is set to leave it); it is never changed.
 * - A file that fails stays where it is, is logged with the reason, and isn't tried again
 *   until it changes.
 *
 * Works on its own threads (never the JavaFX thread); tell it what happened with a Listener.
 */
public class WatchFolderService {

    /** Told about each result and, after a burst of files, a summary for an OS notification. Called on a worker thread. */
    public interface Listener {
        void activity(Activity entry);

        default void burstFinished(int done, int failed) {}
    }

    private static final Logger log = LoggerFactory.getLogger(WatchFolderService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson LINE = new Gson();
    private static final int LOG_KEPT = 500;
    /** Files still being written by other programs often have these names; they're never picked up. */
    private static final Set<String> TEMP_EXTENSIONS = Set.of("tmp", "part", "partial", "crdownload", "download", "filepart");

    private final Path appFolder;
    private final Supplier<List<Pipeline>> pipelines;
    private final Supplier<SaveTo> saveTo;
    private final Duration settle;
    private final Duration scanEvery;

    private final List<WatchFolder> folders = new CopyOnWriteArrayList<>();
    private final Deque<Activity> activity = new ArrayDeque<>();
    private final Map<Path, Candidate> pending = new ConcurrentHashMap<>();
    private final Set<Path> working = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private volatile Listener listener = entry -> {};
    private int burstDone, burstFailed;

    private ScheduledExecutorService ticker;
    private ExecutorService worker;
    private WatchService watcher;
    private final Map<WatchKey, WatchFolder> keys = new ConcurrentHashMap<>();
    private long lastScan;

    /** A file seen in a watched folder, waiting until it has finished arriving. */
    private record Candidate(String folderId, long size, long modified, long sameSince) {}

    public WatchFolderService(Supplier<List<Pipeline>> pipelines, Supplier<SaveTo> saveTo) {
        this(SettingsService.appDataFolder(), pipelines, saveTo, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    /** For tests: another folder for its files, and shorter waits. */
    public WatchFolderService(Path appFolder, Supplier<List<Pipeline>> pipelines, Supplier<SaveTo> saveTo,
                              Duration settle, Duration scanEvery) {
        this.appFolder = appFolder;
        this.pipelines = pipelines;
        this.saveTo = saveTo;
        this.settle = settle;
        this.scanEvery = scanEvery;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? entry -> {} : listener;
    }

    // ── The list of folders ──────────────────────────────────────────────

    /** Reads the saved watch folders and the activity log. */
    public void load() {
        Path file = appFolder.resolve("watchfolders.json");
        if (Files.exists(file)) {
            try {
                List<WatchFolder> saved = GSON.fromJson(Files.readString(file), new TypeToken<List<WatchFolder>>() {}.getType());
                if (saved != null) folders.addAll(saved);
            } catch (IOException | JsonParseException e) {
                log.warn("Could not read {}; starting with no watch folders", file, e);
            }
        }
        Path logFile = appFolder.resolve("activity.log");
        if (Files.exists(logFile)) {
            try {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                for (String line : lines.subList(Math.max(0, lines.size() - LOG_KEPT), lines.size())) {
                    try {
                        Activity entry = LINE.fromJson(line, Activity.class);
                        if (entry != null) synchronized (activity) { activity.addFirst(entry); }
                    } catch (JsonParseException e) {
                        // a damaged line: skip it
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read {}", logFile, e);
            }
        }
    }

    public List<WatchFolder> folders() {
        return List.copyOf(folders);
    }

    /** The log, newest first. */
    public List<Activity> activity() {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    public void add(WatchFolder folder) {
        folders.add(folder);
        saveQuietly();
        if (watcher != null) register(folder);
    }

    public void remove(WatchFolder folder) {
        folders.removeIf(f -> f.getId().equals(folder.getId()));
        keys.entrySet().removeIf(e -> {
            if (e.getValue().getId().equals(folder.getId())) e.getKey().cancel();
            return e.getValue().getId().equals(folder.getId());
        });
        saveQuietly();
    }

    /** Pause (false) or resume (true) a folder. */
    public void setEnabled(WatchFolder folder, boolean enabled) {
        folder.setEnabled(enabled);
        saveQuietly();
        if (enabled && watcher != null) {
            register(folder);
            scan(folder);
        }
    }

    /**
     * What's wrong with a watch folder before it's added, as a sentence for the dialog;
     * null if it's fine.
     */
    public static String check(WatchFolder folder, List<Pipeline> pipelines, List<WatchFolder> others) {
        if (folder.getInputFolder() == null || !Files.isDirectory(Path.of(folder.getInputFolder()))) {
            return "Choose the folder to watch.";
        }
        if (folder.getOutputFolder() == null || folder.getOutputFolder().isBlank()) return "Choose where the results go.";
        Path input = Path.of(folder.getInputFolder()).toAbsolutePath().normalize();
        Path output = Path.of(folder.getOutputFolder()).toAbsolutePath().normalize();
        if (input.equals(output)) {
            return "Results can't go into the watched folder itself, or they'd be run through the pipeline again. "
                   + "Choose another folder (a folder inside it is fine).";
        }
        for (WatchFolder other : others) {
            if (!other.getId().equals(folder.getId()) && Path.of(other.getInputFolder()).toAbsolutePath().normalize().equals(input)) {
                return "That folder is already being watched. Choose another, or change the one that watches it.";
            }
        }
        Optional<Pipeline> pipeline = pipelines.stream().filter(p -> p.getId().equals(folder.getPipelineId())).findFirst();
        if (pipeline.isEmpty()) return "Choose the pipeline to run.";
        if (!PipelineValidator.canRun(pipeline.get())) return "“" + pipeline.get().getName() + "” can't run yet. Fix it in the builder first.";
        if (!new PipelineTool(pipeline.get()).optionFields().isEmpty()) {
            return "“" + pipeline.get().getName() + "” asks for a password each time it runs, so it can't run by itself in a "
                   + "watch folder. Choose a pipeline without password steps.";
        }
        return null;
    }

    // ── Running ──────────────────────────────────────────────────────────

    /** Starts watching every folder that isn't paused, and picks up files already waiting in them. */
    public synchronized void start() {
        if (ticker != null) return;
        try {
            watcher = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("No file-change events; scanning folders every {} s only", scanEvery.toSeconds(), e);
        }
        ticker = Executors.newSingleThreadScheduledExecutor(daemon("ndalamo-watch-ticker"));
        worker = Executors.newSingleThreadExecutor(daemon("ndalamo-watch-worker"));
        for (WatchFolder folder : folders) if (folder.isEnabled()) register(folder);
        if (watcher != null) {
            Thread events = daemon("ndalamo-watch-events").newThread(this::readEvents);
            events.start();
        }
        lastScan = 0;
        long tick = Math.max(100, Math.min(500, settle.toMillis() / 2));
        ticker.scheduleWithFixedDelay(this::tick, 0, tick, TimeUnit.MILLISECONDS);
    }

    /** Stops watching. A file being processed finishes first (up to the given wait). */
    public synchronized void stop(Duration wait) {
        if (ticker == null) return;
        ticker.shutdownNow();
        worker.shutdown();
        try {
            worker.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (watcher != null) watcher.close();
        } catch (IOException e) {
            // closing anyway
        }
        ticker = null;
        watcher = null;
        keys.clear();
        pending.clear();
    }

    /** True while files are waiting or being processed (the quit dialog asks about this). */
    public boolean isBusy() {
        return !working.isEmpty() || !pending.isEmpty();
    }

    public int waitingOrWorking() {
        return working.size() + pending.size();
    }

    private void register(WatchFolder folder) {
        if (watcher == null) return;
        try {
            Path input = Path.of(folder.getInputFolder());
            WatchKey key = input.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            keys.put(key, folder);
        } catch (IOException | RuntimeException e) {
            log.warn("Can't get change events for {}; it's still scanned every {} s", folder.getInputFolder(), scanEvery.toSeconds(), e);
        }
    }

    private void readEvents() {
        try {
            while (true) {
                WatchKey key = watcher.take();
                WatchFolder folder = keys.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (folder != null && event.context() instanceof Path name) consider(folder, Path.of(folder.getInputFolder()).resolve(name));
                }
                if (!key.reset()) keys.remove(key);
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            // stopped
        }
    }

    /** Every half second or so: scan if it's time, then hand over files that have finished arriving. */
    private void tick() {
        try {
            long now = System.nanoTime();
            if (lastScan == 0 || now - lastScan >= scanEvery.toNanos()) {
                lastScan = now;
                for (WatchFolder folder : folders) if (folder.isEnabled()) scan(folder);
            }
            for (Map.Entry<Path, Candidate> entry : pending.entrySet()) {
                Path file = entry.getKey();
                Candidate seen = entry.getValue();
                if (!Files.isRegularFile(file)) {
                    pending.remove(file);
                    continue;
                }
                long size = Files.size(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (size != seen.size() || modified != seen.modified()) {
                    pending.put(file, new Candidate(seen.folderId(), size, modified, now));
                } else if (now - seen.sameSince() >= settle.toNanos() && size > 0 && nobodyElseHasIt(file)) {
                    pending.remove(file);
                    folderById(seen.folderId()).filter(WatchFolder::isEnabled).ifPresent(folder -> {
                        working.add(file);
                        worker.submit(() -> process(folder, file));
                    });
                }
            }
            reportBurst();
        } catch (IOException | RuntimeException e) {
            log.debug("Watch-folder check skipped a beat", e);
        }
    }

    private void scan(WatchFolder folder) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(folder.getInputFolder()))) {
            for (Path file : files) consider(folder, file);
        } catch (IOException | RuntimeException e) {
            log.debug("Couldn't scan {}", folder.getInputFolder(), e);
        }
    }

    /** Adds a file to the waiting list if it's one this folder's pipeline takes and it isn't done already. */
    private void consider(WatchFolder folder, Path file) {
        try {
            if (!folder.isEnabled() || working.contains(file) || pending.containsKey(file) || !Files.isRegularFile(file)) return;
            String name = file.getFileName().toString();
            String ext = FileInspector.extension(file);
            if (name.startsWith("~$") || name.startsWith(".") || Files.isHidden(file) || TEMP_EXTENSIONS.contains(ext)) return;
            Optional<Pipeline> pipeline = pipelineFor(folder);
            if (pipeline.isEmpty() || !new PipelineTool(pipeline.get()).fileTypes().contains(ext)) return;
            String key = key(file);
            if (folder.isDone(key) || failed.contains(key)) return;
            pending.put(file, new Candidate(folder.getId(), -1, -1, System.nanoTime()));
        } catch (IOException e) {
            // gone already
        }
    }

    private void process(WatchFolder folder, Path file) {
        String key;
        try {
            key = key(file);
        } catch (IOException e) {
            working.remove(file);
            return;
        }
        Optional<Pipeline> pipeline = pipelineFor(folder);
        String pipelineName = pipeline.map(Pipeline::getName).orElse("(deleted pipeline)");
        try {
            if (pipeline.isEmpty()) {
                fail(folder, file, key, pipelineName, "Its pipeline was deleted. Choose another for this folder.");
                return;
            }
            Path output = Path.of(folder.getOutputFolder());
            Files.createDirectories(output);
            SaveTo target = saveTo.get().inFolder(output).withIfExists(Settings.IfExists.ADD_NUMBER);
            List<FileResult> results = PipelineRunner.run(pipeline.get(), List.of(file), Map.of(), target, new Progress());
            List<FileResult> made = results.stream().filter(r -> r.getOutput() != null
                    && (r.getStatus() == FileResult.Status.SUCCESS || r.getStatus() == FileResult.Status.PARTIAL)).toList();
            if (made.isEmpty()) {
                String why = results.isEmpty() ? "Nothing was made." : results.get(0).getNote();
                fail(folder, file, key, pipelineName, why);
                return;
            }
            long before = Files.size(file);
            long after = made.stream().mapToLong(FileResult::getSizeAfter).sum();
            String note = "Done · " + Format.size(before) + " → " + Format.size(after)
                          + (made.size() > 1 ? " · " + made.size() + " files" : "");
            if (folder.getAfterwards() == WatchFolder.Afterwards.MOVE_TO_PROCESSED) {
                if (!moveToProcessed(file)) {
                    folder.remember(key);       // couldn't move it: at least don't do it again
                    note += " · the original couldn't be moved to “processed” (it's in use)";
                }
            } else {
                folder.remember(key);
            }
            folder.countOne();
            saveQuietly();
            record(new Activity(System.currentTimeMillis(), file.getFileName().toString(), pipelineName, true, note,
                    made.get(0).getOutput().toString()));
            synchronized (this) {
                burstDone++;
            }
        } catch (Exception e) {
            log.warn("Watch folder couldn't process {}", file, e);
            fail(folder, file, key, pipelineName, e.getMessage() == null ? "It stopped unexpectedly." : e.getMessage());
        } finally {
            working.remove(file);
        }
    }

    private void fail(WatchFolder folder, Path file, String key, String pipelineName, String why) {
        failed.add(key);     // not again until the file changes
        record(new Activity(System.currentTimeMillis(), file.getFileName().toString(), pipelineName, false,
                why + " The file was left in the folder.", null));
        synchronized (this) {
            burstFailed++;
        }
    }

    /** When the folders go quiet after some files, one summary for the notification. */
    private void reportBurst() {
        int done, failedCount;
        synchronized (this) {
            if (burstDone + burstFailed == 0 || isBusy()) return;
            done = burstDone;
            failedCount = burstFailed;
            burstDone = 0;
            burstFailed = 0;
        }
        listener.burstFinished(done, failedCount);
    }

    /** Moves the original into "processed" in its folder, numbering the name if one is already there. */
    private static boolean moveToProcessed(Path file) {
        try {
            Path folder = Files.createDirectories(file.getParent().resolve("processed"));
            String name = file.getFileName().toString();
            Path target = folder.resolve(name);
            int dot = name.lastIndexOf('.');
            for (int n = 2; Files.exists(target); n++) {
                target = folder.resolve(dot < 0 ? name + " (" + n + ")" : name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
            }
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * True if no other program is holding the file. A file being written by a scanner or a copy
     * can't be locked; trying never changes it.
     */
    static boolean nobodyElseHasIt(Path file) {
        boolean writable = Files.isWritable(file);
        try (FileChannel channel = writable ? FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(file, StandardOpenOption.READ)) {
            if (!writable) return true;
            FileLock lock = channel.tryLock();
            if (lock == null) return false;
            lock.release();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void record(Activity entry) {
        synchronized (activity) {
            activity.addFirst(entry);
            while (activity.size() > LOG_KEPT) activity.removeLast();
        }
        try {
            Files.createDirectories(appFolder);
            Files.writeString(appFolder.resolve("activity.log"), LINE.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write the activity log", e);
        }
        listener.activity(entry);
    }

    /** Writes the log as a CSV file for a spreadsheet (Export log). */
    public void exportLog(Path target) throws IOException {
        DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
        StringBuilder csv = new StringBuilder("Time,File,Pipeline,Result,Saved as\r\n");
        List<Activity> entries = new ArrayList<>(activity());
        java.util.Collections.reverse(entries);     // oldest first in a spreadsheet
        for (Activity a : entries) {
            csv.append(String.join(",", cell(when.format(Instant.ofEpochMilli(a.time()))), cell(a.file()), cell(a.pipeline()),
                    cell(a.result()), cell(a.output() == null ? "" : a.output()))).append("\r\n");
        }
        Files.writeString(target, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String cell(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private void saveQuietly() {
        try {
            Files.createDirectories(appFolder);
            Path file = appFolder.resolve("watchfolders.json");
            Path temp = Files.createTempFile(appFolder, "watchfolders-", ".tmp");
            try {
                Files.writeString(temp, GSON.toJson(new ArrayList<>(folders)));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.warn("Could not save the watch folders", e);
        }
    }

    private Optional<WatchFolder> folderById(String id) {
        return folders.stream().filter(f -> f.getId().equals(id)).findFirst();
    }

    private Optional<Pipeline> pipelineFor(WatchFolder folder) {
        return pipelines.get().stream().filter(p -> p.getId().equals(folder.getPipelineId())).findFirst();
    }

    /** One version of a file: name, size and time changed. A changed file counts as new. */
    private static String key(Path file) throws IOException {
        return file.getFileName() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
