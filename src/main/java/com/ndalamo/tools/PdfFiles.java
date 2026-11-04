package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small helpers every tool uses for opening PDFs and describing problems the same way. */
final class PdfFiles {

    private PdfFiles() {}

    /**
     * Opens a PDF for reading. Uses a temp-file cache, so big scans don't run a
     * 4 GB laptop out of memory. A PDF that opened without a password can still
     * carry copy/print restrictions; the tool's output is a new file without them.
     */
    static PDDocument open(Path file) throws IOException {
        PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache());
        doc.setAllSecurityToBeRemoved(true);
        return doc;
    }

    /** Why a file couldn't be opened, in a few words: "needs a password". */
    static String problem(Exception e) {
        return e instanceof InvalidPasswordException ? "needs a password" : "damaged or not a readable PDF";
    }

    /** The result for an input that couldn't be processed. Nothing was written and the original is unchanged. */
    static FileResult failed(Path input, String reason) {
        long size;
        try {
            size = Files.size(input);
        } catch (IOException e) {
            size = 0;
        }
        return FileResult.failed(input, size, reason + ". The original wasn't changed.");
    }

    /** How big the document would be if saved now, without writing anything to disk. */
    static long savedSize(PDDocument doc) throws IOException {
        CountingStream counter = new CountingStream();
        doc.save(counter);
        return counter.count;
    }

    private static final class CountingStream extends OutputStream {
        long count;
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
    }
}
