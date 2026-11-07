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
    private final boolean askForFolder;  // "Ask every time": the tool screen asks before each run
    private final String toolWord;       // replaces each tool's own word for {tool}, e.g. a pipeline's; null = the tool's

    public SaveTo(Path folder, String pattern, Settings.IfExists ifExists) {
        this(folder, pattern, ifExists, false, null);
    }

    private SaveTo(Path folder, String pattern, Settings.IfExists ifExists, boolean askForFolder) {
        this(folder, pattern, ifExists, askForFolder, null);
    }

    private SaveTo(Path folder, String pattern, Settings.IfExists ifExists, boolean askForFolder, String toolWord) {
        this.toolWord = toolWord;
        this.askForFolder = askForFolder;
        this.folder = folder;
        this.pattern = pattern;
        this.ifExists = ifExists;
    }

    /** The user's saved choices. "Ask each time" starts as "same folder"; the tool screen asks before running. */
    public static SaveTo from(Settings settings) {
        Path folder = settings.getSaveLocation() == Settings.SaveLocation.FIXED_FOLDER && settings.getFixedFolder() != null
                ? Path.of(settings.getFixedFolder())
                : null;
        return new SaveTo(folder, settings.getFileNamePattern(), settings.getIfExists(),
                settings.getSaveLocation() == Settings.SaveLocation.ASK);
    }

    /** The same naming rules, but saving into a folder the user picked for this run. */
    public SaveTo inFolder(Path newFolder) {
        return new SaveTo(newFolder, pattern, ifExists, false, toolWord);
    }

    /** The same, with the answer to "a result with that name exists" chosen for this run. */
    public SaveTo withIfExists(Settings.IfExists rule) {
        return new SaveTo(folder, pattern, rule, askForFolder, toolWord);
    }

    /** The same, but {tool} in names becomes {@code word} whichever tool writes the file (a pipeline's word). */
    public SaveTo withToolWord(String word) {
        return new SaveTo(folder, pattern, ifExists, askForFolder, word);
    }

    private String word(String suffix) {
        return toolWord != null ? toolWord : suffix;
    }

    /** Settings say "Ask every time": the tool screen picks the folder before running. */
    public boolean asksForFolder() {
        return askForFolder;
    }

    /** Settings say "Ask me" when a result's name is taken: the tool screen asks before running. */
    public boolean asksIfExists() {
        return ifExists == Settings.IfExists.ASK;
    }

    /**
     * The name a result would get if names already taken were replaced: for checking
     * beforehand whether running would meet a file that's already there. Never the original.
     */
    public Path plainOutputFor(Path input, String suffix, String extension) {
        return OutputNames.pick(input, folderFor(input), pattern, word(suffix), extension, 0, Settings.IfExists.REPLACE);
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
        // "Ask" is answered on the tool screen before running (see withIfExists); if a name still
        // turns out to be taken here, fall back to the safe choice.
        Settings.IfExists rule = ifExists == Settings.IfExists.ASK ? Settings.IfExists.ADD_NUMBER : ifExists;
        return OutputNames.pick(input, target, pattern, word(suffix), extension, pages, rule);
    }

    /** The folder this input's output goes into: the fixed folder, or the one the input is in. */
    public Path folderFor(Path input) {
        return folder != null ? folder : input.toAbsolutePath().getParent();
    }

    /** Short description for the tool screen, e.g. "Same folder as each original". */
    public String describeFolder(boolean oneOutput) {
        if (askForFolder) return "A folder you choose when you start";
        if (folder != null) return folder.toString();
        return oneOutput ? "Same folder as the first file" : "Same folder as each original";
    }

    /** The name pattern with the tool filled in, e.g. "{name}_merged.pdf". */
    public String describeName(String suffix, String extension) {
        return pattern.replace("{tool}", word(suffix)) + "." + extension;
    }

    public Path getFolder() { return folder; }
}
