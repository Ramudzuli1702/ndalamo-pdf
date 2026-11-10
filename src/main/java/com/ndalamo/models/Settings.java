package com.ndalamo.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's settings, saved to settings.json by SettingsService.
 *
 * Every field starts with its default value, so a settings file from an older
 * version that is missing a field still loads with a sensible value.
 */
public class Settings {

    /** Where results are saved. */
    public enum SaveLocation { SAME_FOLDER, FIXED_FOLDER, ASK }

    /** What to do when the output name is already taken. Never applies to the original file. */
    public enum IfExists { ADD_NUMBER, ASK, REPLACE }

    /** How many files are processed at the same time. */
    public enum Parallelism { AUTOMATIC, ONE, TWO, FOUR }

    /** Light or dark colours. */
    public enum Appearance { LIGHT, DARK, FOLLOW_SYSTEM }

    private SaveLocation saveLocation = SaveLocation.SAME_FOLDER;
    private String fixedFolder;
    private String fileNamePattern = "{name}_{tool}";
    private IfExists ifExists = IfExists.ADD_NUMBER;
    private Parallelism parallelism = Parallelism.AUTOMATIC;
    private boolean lowMemoryMode;
    private Appearance appearance = Appearance.LIGHT;
    private boolean rememberRecentFiles = true;
    private boolean tourSeen;            // the welcome tour has been shown (or skipped) once
    private boolean askBeforeDeletingPages = true;
    private String libreOfficePath;
    private Map<String, String> toolDefaults = new HashMap<>();
    private List<String> recentFiles = new ArrayList<>();
    private List<String> recentTools = new ArrayList<>();

    public SaveLocation getSaveLocation() { return saveLocation; }
    public void setSaveLocation(SaveLocation saveLocation) { this.saveLocation = saveLocation; }

    public String getFixedFolder() { return fixedFolder; }
    public void setFixedFolder(String fixedFolder) { this.fixedFolder = fixedFolder; }

    public String getFileNamePattern() { return fileNamePattern; }
    public void setFileNamePattern(String fileNamePattern) { this.fileNamePattern = fileNamePattern; }

    public IfExists getIfExists() { return ifExists; }
    public void setIfExists(IfExists ifExists) { this.ifExists = ifExists; }

    public Parallelism getParallelism() { return parallelism; }
    public void setParallelism(Parallelism parallelism) { this.parallelism = parallelism; }

    public boolean isLowMemoryMode() { return lowMemoryMode; }
    public void setLowMemoryMode(boolean lowMemoryMode) { this.lowMemoryMode = lowMemoryMode; }

    public Appearance getAppearance() { return appearance; }
    public void setAppearance(Appearance appearance) { this.appearance = appearance; }

    public boolean isRememberRecentFiles() { return rememberRecentFiles; }
    public boolean isTourSeen() { return tourSeen; }
    public void setTourSeen(boolean tourSeen) { this.tourSeen = tourSeen; }
    public void setRememberRecentFiles(boolean rememberRecentFiles) { this.rememberRecentFiles = rememberRecentFiles; }

    /** Page workspace: show "Delete 2 pages?" before deleting, until the user ticks "Don't ask again". */
    public boolean isAskBeforeDeletingPages() { return askBeforeDeletingPages; }
    public void setAskBeforeDeletingPages(boolean ask) { this.askBeforeDeletingPages = ask; }

    /** soffice.exe picked with "Locate it"; null to look in the usual places. Only Office to PDF uses it. */
    public String getLibreOfficePath() { return libreOfficePath; }
    public void setLibreOfficePath(String libreOfficePath) { this.libreOfficePath = libreOfficePath; }

    /**
     * The choice each tool starts with, by "tool id.option id", e.g. "compress.mode" → "Strong".
     * Set on the Settings screen; a tool without an entry starts with its own default.
     */
    public Map<String, String> getToolDefaults() { return toolDefaults; }
    public void setToolDefaults(Map<String, String> toolDefaults) { this.toolDefaults = toolDefaults; }

    /** Files and tools used lately, newest first, for the Home sidebar; kept only while rememberRecentFiles is on. */
    public List<String> getRecentFiles() { return recentFiles; }
    public List<String> getRecentTools() { return recentTools; }

    /** Number of background threads for jobs. Low-memory mode always uses one (SPEC §3.4). */
    public int workerThreads() {
        if (lowMemoryMode) return 1;
        return switch (parallelism) {
            case ONE -> 1;
            case TWO -> 2;
            case FOUR -> 4;
            case AUTOMATIC -> Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        };
    }

    /**
     * Puts back defaults for anything left empty — e.g. a hand-edited file with a
     * misspelled option, which Gson reads as null.
     */
    public void fillMissingValues() {
        Settings defaults = new Settings();
        if (saveLocation == null) saveLocation = defaults.saveLocation;
        if (fileNamePattern == null || fileNamePattern.isBlank()) fileNamePattern = defaults.fileNamePattern;
        if (ifExists == null) ifExists = defaults.ifExists;
        if (parallelism == null) parallelism = defaults.parallelism;
        if (appearance == null) appearance = defaults.appearance;
        if (toolDefaults == null) toolDefaults = new HashMap<>();
        if (recentFiles == null) recentFiles = new ArrayList<>();
        if (recentTools == null) recentTools = new ArrayList<>();
    }
}
