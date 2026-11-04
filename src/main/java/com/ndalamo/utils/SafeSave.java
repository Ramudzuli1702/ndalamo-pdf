package com.ndalamo.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Saves an output file safely: the tool writes to a temp file next to the target,
 * and only when that has fully succeeded is it moved to the real name. If writing
 * fails or is cancelled halfway, the temp file is deleted and nothing else changes.
 *
 * Usage: {@code SafeSave.write(target, temp -> document.save(temp.toFile()));}
 */
public final class SafeSave {

    /** Writes the file content to the path it is given. */
    @FunctionalInterface
    public interface Writer {
        void write(Path temp) throws IOException;
    }

    private SafeSave() {}

    public static void write(Path target, Writer writer) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        // Same folder as the target, so the final move is a quick rename, not a copy.
        Path temp = Files.createTempFile(folder, ".ndalamo-", ".tmp");
        try {
            writer.write(temp);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
