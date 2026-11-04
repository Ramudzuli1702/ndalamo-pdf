package com.ndalamo.utils;

import com.ndalamo.models.Settings.IfExists;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the file name for a tool's output from the user's pattern (SPEC §3.5).
 *
 * Tokens: {name} = original name without extension, {tool} = e.g. "compressed",
 * {date} = yyyy-MM-dd, {pages} = page count. The default "{name}_{tool}" turns
 * Assignment2.pdf into Assignment2_compressed.pdf.
 *
 * The name returned is NEVER the original file, whatever the pattern or setting —
 * if it would be, a number is added instead. This class only picks names; it
 * never creates, writes or deletes files.
 */
public final class OutputNames {

    private static final String NOT_ALLOWED_IN_NAMES = "<>:\"/\\|?*";
    private static final Set<String> RESERVED_BY_WINDOWS = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private OutputNames() {}

    /**
     * The full output path for one input file.
     *
     * @param extension e.g. "pdf" or ".docx"
     * @param pages     page count for {pages}; 0 if unknown
     * @param ifExists  ADD_NUMBER or REPLACE — the screen asks the user first when the setting is ASK
     */
    public static Path pick(Path input, Path folder, String pattern, String tool, String extension,
                            int pages, IfExists ifExists) {
        if (ifExists == IfExists.ASK) {
            throw new IllegalArgumentException("Ask the user first, then pass ADD_NUMBER or REPLACE");
        }
        String base = fillPattern(pattern, input, tool, pages, LocalDate.now());
        String ext = extension.isEmpty() || extension.startsWith(".") ? extension : "." + extension;

        Path candidate = folder.resolve(base + ext);
        if (!Files.exists(candidate)) return candidate;
        if (ifExists == IfExists.REPLACE && !isSameFile(candidate, input)) return candidate;

        // Name taken (or it is the original file itself): count up to a free name.
        for (int n = 2; ; n++) {
            Path numbered = folder.resolve(base + " (" + n + ")" + ext);
            if (!Files.exists(numbered)) return numbered;
        }
    }

    /** Fills in the pattern, without folder or extension, and makes the result safe for Windows. */
    public static String fillPattern(String pattern, Path input, String tool, int pages, LocalDate date) {
        String name = pattern
                .replace("{name}", nameWithoutExtension(input))
                .replace("{tool}", tool)
                .replace("{date}", date.toString())   // ISO format: yyyy-MM-dd
                .replace("{pages}", pages > 0 ? String.valueOf(pages) : "");
        return makeSafe(name);
    }

    private static String nameWithoutExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String makeSafe(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(c < 32 || NOT_ALLOWED_IN_NAMES.indexOf(c) >= 0 ? '_' : c);
        }
        // Windows silently drops trailing dots and spaces, which would change the name.
        String result = sb.toString().strip().replaceAll("[. ]+$", "");
        if (result.isEmpty()) return "output";
        if (RESERVED_BY_WINDOWS.contains(result.toUpperCase(Locale.ROOT))) return result + "_";
        return result;
    }

    private static boolean isSameFile(Path a, Path b) {
        try {
            return Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
