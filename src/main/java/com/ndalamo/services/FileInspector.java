package com.ndalamo.services;

import com.ndalamo.models.FileInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Looks at a file before any tool runs: size, page count, password, damage.
 * Only reads the file — never changes it. Slow for big PDFs, so screens call it
 * on a background thread.
 *
 * Later (chunk 15) this also suggests tools for dropped files (SPEC §4.2).
 */
public class FileInspector {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");

    public FileInfo inspect(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileInfo(0, 0, false, true);
        }

        String ext = extension(file);
        if (IMAGE_EXTENSIONS.contains(ext)) return new FileInfo(size, 1, false, false);
        if (!ext.equals("pdf")) return new FileInfo(size, 0, false, false);

        // Temp-file cache: a 500 MB scan shouldn't need 500 MB of memory just to count pages.
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return new FileInfo(size, doc.getNumberOfPages(), false, false, doc.isEncrypted());
        } catch (InvalidPasswordException e) {
            return new FileInfo(size, 0, true, false);
        } catch (IOException e) {
            return new FileInfo(size, 0, false, true);
        }
    }

    /**
     * Checks a password typed in the password dialog, without changing anything.
     * For a file that opens without a password but has restrictions, only its full
     * ("owner") password counts, since that's what removing restrictions needs.
     */
    public boolean passwordWorks(Path file, String password) {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), password, IOUtils.createTempFileOnlyStreamCache())) {
            if (opensWithoutPassword(file)) return doc.getCurrentAccessPermission().isOwnerPermission();
            return true;     // it needed a password to open, and this one opened it
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean opensWithoutPassword(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Lower-case extension without the dot, e.g. "pdf"; empty if there is none. */
    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
