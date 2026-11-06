package com.ndalamo.services;

import com.ndalamo.models.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {

    @TempDir
    Path dir;

    @Test
    void toolDefaultsAndRecentListsAreKept() throws Exception {
        SettingsService service = new SettingsService(dir);
        Settings settings = service.load();
        settings.getToolDefaults().put("compress.mode", "Strong");
        settings.getRecentFiles().add("C:/Docs/Report.pdf");
        service.save(settings);

        Settings again = new SettingsService(dir).load();
        assertEquals("Strong", again.getToolDefaults().get("compress.mode"));
        assertEquals(1, again.getRecentFiles().size());
    }

    @Test
    void noFileGivesDefaults() {
        Settings settings = new SettingsService(dir).load();
        assertEquals("{name}_{tool}", settings.getFileNamePattern());
        assertEquals(Settings.SaveLocation.SAME_FOLDER, settings.getSaveLocation());
    }

    @Test
    void savedSettingsLoadBack() throws IOException {
        SettingsService service = new SettingsService(dir.resolve("Ndalamo"));
        Settings settings = new Settings();
        settings.setFileNamePattern("{date}_{name}");
        settings.setParallelism(Settings.Parallelism.TWO);
        settings.setLowMemoryMode(true);

        service.save(settings);
        Settings loaded = service.load();

        assertEquals("{date}_{name}", loaded.getFileNamePattern());
        assertEquals(Settings.Parallelism.TWO, loaded.getParallelism());
        assertTrue(loaded.isLowMemoryMode());
    }

    @Test
    void damagedFileGivesDefaultsAndIsLeftAlone() throws IOException {
        SettingsService service = new SettingsService(dir);
        Files.writeString(service.getFile(), "{ not json");

        assertEquals("{name}_{tool}", service.load().getFileNamePattern());
        assertEquals("{ not json", Files.readString(service.getFile()));
    }

    @Test
    void missingOrMisspelledValuesFallBackToDefaults() throws IOException {
        SettingsService service = new SettingsService(dir);
        Files.writeString(service.getFile(), "{\"appearance\":\"DARK\",\"ifExists\":\"SOMETIMES\"}");

        Settings loaded = service.load();

        assertEquals(Settings.Appearance.DARK, loaded.getAppearance());
        assertEquals(Settings.IfExists.ADD_NUMBER, loaded.getIfExists());
        assertTrue(loaded.isRememberRecentFiles());
    }

    @Test
    void lowMemoryModeUsesOneThread() {
        Settings settings = new Settings();
        settings.setParallelism(Settings.Parallelism.FOUR);
        settings.setLowMemoryMode(true);
        assertEquals(1, settings.workerThreads());
    }
}
