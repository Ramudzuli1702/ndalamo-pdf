package com.ndalamo.models;

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
    public void setRememberRecentFiles(boolean rememberRecentFiles) { this.rememberRecentFiles = rememberRecentFiles; }

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
    }
}
