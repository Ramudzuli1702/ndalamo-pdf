package com.ndalamo.utils;

import java.util.Locale;

/** Turns numbers into the short text the screens show, e.g. "4.1 MB" or "56% smaller". */
public final class Format {

    private Format() {}

    /** File size, e.g. "820 KB", "4.1 MB". Always uses "." as the decimal point, like the mockups. */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** "4.1 → 1.8 MB" when both are in MB, otherwise "820 KB → 1.2 MB". */
    public static String sizeChange(long before, long after) {
        String a = size(before);
        String b = size(after);
        if (a.endsWith(" MB") && b.endsWith(" MB")) return a.replace(" MB", "") + " → " + b;
        return a + " → " + b;
    }

    /** "56% smaller", or empty when the file didn't get smaller. */
    public static String saving(long before, long after) {
        if (before <= 0 || after >= before) return "";
        return Math.round(100.0 * (before - after) / before) + "% smaller";
    }

    /** "1 file", "3 files". */
    public static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
