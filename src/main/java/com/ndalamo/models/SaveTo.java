package com.ndalamo.models;

import com.ndalamo.utils.OutputNames;

import java.nio.file.Path;

/**
 * Where a tool saves its output and how the file is named, taken from Settings
 * (SPEC §3.5). Tools ask this for each output path instead of choosing names
 * themselves, so the "never the original file" rule lives in one place.
 */
public class SaveTo {

    private final Path folder;          // null = next to each original file
    private final String pattern;       // e.g. "{name}_{tool}"
    private final Settings.IfExists ifExists;

    public SaveTo(Path folder, String pattern, Settings.IfExists ifExists) {
        this.folder = folder;
        this.pattern = pattern;
        this.ifExists = ifExists;
    }

    /** The user's saved choices. "Ask each time" starts as "same folder"; the tool screen asks before running. */
    public static SaveTo from(Settings settings) {
        Path folder = settings.getSaveLocation() == Settings.SaveLocation.FIXED_FOLDER && settings.getFixedFolder() != null
                ? Path.of(settings.getFixedFolder())
                : null;
        return new SaveTo(folder, settings.getFileNamePattern(), settings.getIfExists());
    }

    /** The same naming rules, but saving into a folder the user picked for this run. */
    public SaveTo inFolder(Path newFolder) {
        return new SaveTo(newFolder, pattern, ifExists);
    }

    /**
     * The path for one output file — never the original file.
     *
     * @param suffix    the tool's word for {tool}, e.g. "merged"
     * @param extension e.g. "pdf"
     * @param pages     for {pages}; 0 if unknown
     */
    public Path outputFor(Path input, String suffix, String extension, int pages) {
        Path target = folderFor(input);
        // Asking "replace or add a number?" comes with the Settings screen (chunk 18);
        // until then "ask" behaves like the safe default.
        Settings.IfExists rule = ifExists == Settings.IfExists.ASK ? Settings.IfExists.ADD_NUMBER : ifExists;
        return OutputNames.pick(input, target, pattern, suffix, extension, pages, rule);
    }

    /** The folder this input's output goes into: the fixed folder, or the one the input is in. */
    public Path folderFor(Path input) {
        return folder != null ? folder : input.toAbsolutePath().getParent();
    }

    /** Short description for the tool screen, e.g. "Same folder as each original". */
    public String describeFolder(boolean oneOutput) {
        if (folder != null) return folder.toString();
        return oneOutput ? "Same folder as the first file" : "Same folder as each original";
    }

    /** The name pattern with the tool filled in, e.g. "{name}_merged.pdf". */
    public String describeName(String suffix, String extension) {
        return pattern.replace("{tool}", suffix) + "." + extension;
    }

    public Path getFolder() { return folder; }
}
