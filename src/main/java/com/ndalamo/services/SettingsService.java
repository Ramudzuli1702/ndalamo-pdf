package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.ndalamo.models.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves the user's settings as settings.json in %APPDATA%\Ndalamo (SPEC §3.6).
 *
 * A missing or damaged file never stops the app: it falls back to defaults and
 * leaves the damaged file alone until the user saves new settings.
 */
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /** Uses %APPDATA%\Ndalamo\settings.json (or ~/.ndalamo when APPDATA isn't set). */
    public SettingsService() {
        this(appDataFolder());
    }

    /** Uses settings.json in the given folder — tests point this at a temp folder. */
    public SettingsService(Path folder) {
        this.file = folder.resolve("settings.json");
    }

    public static Path appDataFolder() {
        String appData = System.getenv("APPDATA");
        return appData != null && !appData.isBlank()
                ? Path.of(appData, "Ndalamo")
                : Path.of(System.getProperty("user.home"), ".ndalamo");
    }

    public Path getFile() {
        return file;
    }

    public Settings load() {
        if (!Files.exists(file)) return new Settings();
        try {
            Settings settings = GSON.fromJson(Files.readString(file), Settings.class);
            if (settings == null) return new Settings();   // empty file
            settings.fillMissingValues();
            return settings;
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read {}, using default settings", file, e);
            return new Settings();
        }
    }

    /**
     * Writes to a temp file first and then swaps it in, so a crash halfway through
     * saving can never leave a half-written settings file.
     */
    public void save(Settings settings) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(settings));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
