package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.utils.SafeSave;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Preview before saving: a tool run from the app writes its results into a private
 * folder under %TEMP% first. The preview screen shows them from there, and only "Save"
 * puts them where Settings say, with the same names a direct run would have used;
 * "Discard" deletes the folder. Nothing reaches the user's folders until they choose.
 *
 * The tool gets the user's own SaveTo pointed at the staging folder, so names come out
 * exactly as they would have (subfolders like Split's "name_split" included). Saving
 * copies each file into place with {@link SafeSave} and applies the "a file with that
 * name exists" rule; it never writes over an original. Input files are never touched.
 */
public final class StagedResults {

    /** Staging folders left behind (app closed with a preview open) are removed after this long. */
    private static final Duration KEEP_LEFTOVERS = Duration.ofDays(1);

    private StagedResults() {}

    /** All staging folders live here, one per run. */
    static Path root() {
        return Path.of(System.getProperty("java.io.tmpdir"), "NdalamoPDF-preview");
    }

    /** A new, empty staging folder for one run. Old leftovers are cleared on the way. */
    public static Path newFolder() throws IOException {
        Path root = root();
        Files.createDirectories(root);
        clearLeftovers(root);
        return Files.createTempDirectory(root, "run-");
    }

    /** The user's naming rules, writing into the staging folder (which starts empty, so names are never taken). */
    public static SaveTo staging(SaveTo target, Path folder) {
        return target.inFolder(folder).withIfExists(Settings.IfExists.ADD_NUMBER);
    }

    /**
     * Copies each staged result to where the user's settings put it and returns the
     * results with their final paths. The staging folder is deleted afterwards.
     *
     * @param inputs the files the tool was run on; a result never replaces one of them
     */
    public static List<FileResult> save(List<FileResult> staged, Path folder, SaveTo target, List<Path> inputs)
            throws IOException {
        Settings.IfExists rule = target.asksIfExists() ? Settings.IfExists.ADD_NUMBER : ifExists(target);
        List<FileResult> saved = new ArrayList<>();
        for (FileResult result : staged) {
            Path from = result.getOutput();
            if (from == null) {
                saved.add(result);
                continue;
            }
            Path relative = folder.toAbsolutePath().normalize().relativize(from.toAbsolutePath().normalize());
            Path wanted = target.folderFor(result.getInput()).resolve(relative.toString());
            Path to = freeName(wanted, rule, inputs);
            SafeSave.write(to, temp -> Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING));
            saved.add(new FileResult(result.getInput(), to, result.getSizeBefore(), result.getSizeAfter(),
                    result.getStatus(), result.getNote()));
        }
        discard(folder);
        return saved;
    }

    /** Deletes a staging folder and everything in it. Files still open elsewhere are left for the next clean-up. */
    public static void discard(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // e.g. still open in Word after "Open to check": cleared with the leftovers later
                }
            });
        } catch (IOException ignored) {
            // nothing more to do: leftovers are cleared on a later run
        }
    }

    /** The name to save under: the wanted one, or "name (2).pdf" when it's taken and the rule says number it. */
    static Path freeName(Path wanted, Settings.IfExists rule, List<Path> inputs) {
        boolean original = isInput(wanted, inputs);
        if (!Files.exists(wanted) || (rule == Settings.IfExists.REPLACE && !original)) return wanted;
        String name = wanted.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            Path numbered = wanted.resolveSibling(base + " (" + n + ")" + ext);
            if (!Files.exists(numbered)) return numbered;
        }
    }

    private static boolean isInput(Path file, List<Path> inputs) {
        for (Path input : inputs) {
            try {
                if (Files.exists(file) && Files.exists(input) && Files.isSameFile(file, input)) return true;
            } catch (IOException e) {
                return true;                     // can't tell: treat it as the original and pick another name
            }
        }
        return false;
    }

    private static Settings.IfExists ifExists(SaveTo target) {
        // SaveTo keeps its rule private; a REPLACE target reports its plain name as the output name.
        return target.replaces() ? Settings.IfExists.REPLACE : Settings.IfExists.ADD_NUMBER;
    }

    private static void clearLeftovers(Path root) {
        Instant cutoff = Instant.now().minus(KEEP_LEFTOVERS);
        try (Stream<Path> runs = Files.list(root)) {
            runs.filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(StagedResults::discard);
        } catch (IOException ignored) {
            // not important: try again next time
        }
    }
}
