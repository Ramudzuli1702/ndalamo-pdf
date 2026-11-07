package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.PageRef;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return e instanceof InvalidPasswordException ? "needs a password" : "is damaged or not a readable PDF";
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

    /**
     * Puts a document's pages in the order dragged into place in the preview. The files
     * are put together normally first (keeping links and bookmarks working), then the
     * finished pages are rearranged. Pages of files that were skipped are left out.
     *
     * @param firstPageOf where each input's pages start in the document (0-based)
     */
    static void reorderPages(PDDocument doc, List<PageRef> order, Map<Path, Integer> firstPageOf) {
        List<PDPage> current = new ArrayList<>();
        doc.getPages().forEach(current::add);
        List<PDPage> wanted = new ArrayList<>();
        for (PageRef ref : order) {
            Integer start = firstPageOf.get(ref.file());
            if (start != null && start + ref.page() - 1 < current.size()) wanted.add(current.get(start + ref.page() - 1));
        }
        for (PDPage page : current) {
            if (!wanted.contains(page)) wanted.add(page);    // anything the order didn't mention stays, at the end
        }
        for (PDPage page : current) doc.getPages().remove(page);
        for (PDPage page : wanted) doc.getPages().add(page);
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
