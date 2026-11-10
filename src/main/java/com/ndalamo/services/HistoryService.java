package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.ndalamo.models.HistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Ndalamo has done, newest first, kept in history.json in %APPDATA%\Ndalamo (the last 300
 * runs; nothing leaves the PC). Home's recent files and recent tools come from it. It's only
 * written while Settings › Privacy › "Remember recent files" is on, and Clear empties it.
 */
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int KEPT = 300;

    /** A file worked on lately, and what was last done with it. */
    public record RecentFile(String path, String lastTool, long time) {}

    private final Path file;
    private final List<HistoryEntry> entries = new ArrayList<>();

    public HistoryService() {
        this(SettingsService.appDataFolder());
    }

    /** Uses history.json in the given folder; tests point this at a temp folder. */
    public HistoryService(Path folder) {
        this.file = folder.resolve("history.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            List<HistoryEntry> saved = GSON.fromJson(Files.readString(file), new TypeToken<List<HistoryEntry>>() {}.getType());
            if (saved != null) entries.addAll(saved);
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read {}; starting with an empty history", file, e);
        }
    }

    /** Newest first. */
    public synchronized List<HistoryEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized void add(HistoryEntry entry) {
        entries.add(0, entry);
        if (entries.size() > KEPT) entries.subList(KEPT, entries.size()).clear();
        save();
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    /** Files given to tools lately, newest first, each once, with the tool last used on it. */
    public synchronized List<RecentFile> recentFiles(int count) {
        Map<String, RecentFile> recent = new LinkedHashMap<>();
        for (HistoryEntry entry : entries) {
            for (String input : entry.inputs()) {
                recent.putIfAbsent(input.toLowerCase(java.util.Locale.ROOT), new RecentFile(input, entry.toolName(), entry.time()));
                if (recent.size() >= count) return new ArrayList<>(recent.values());
            }
        }
        return new ArrayList<>(recent.values());
    }

    /** Tool ids used lately, newest first, each once (pipeline runs aren't tools, so they're left out). */
    public synchronized List<String> recentTools(int count) {
        List<String> tools = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            if (!entry.toolId().equals("pipeline") && !tools.contains(entry.toolId())) tools.add(entry.toolId());
            if (tools.size() >= count) break;
        }
        return tools;
    }

    /** Writes to a temp file and swaps it in, so a crash can't leave half a history. */
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "history-", ".tmp");
            try {
                Files.writeString(temp, GSON.toJson(entries));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.warn("Could not save the history", e);
        }
    }
}
