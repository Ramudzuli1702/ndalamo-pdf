package com.ndalamo.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only one Ndalamo at a time; a second copy passes its files to the first. */
class SingleInstanceTest {

    @TempDir
    Path dir;

    @Test
    void aSecondCopyHandsItsFilesToTheFirst() throws Exception {
        SingleInstance first = new SingleInstance(dir);
        assertTrue(first.claim());
        Files.createDirectories(dir.resolve("open"));
        Files.writeString(dir.resolve("open").resolve("old.txt"), "C:\\left\\over.pdf");    // from a crash: ignored
        LinkedBlockingQueue<List<Path>> requests = new LinkedBlockingQueue<>();
        first.listen(requests::add);
        try {
            SingleInstance second = new SingleInstance(dir);
            assertFalse(second.claim(), "the first copy holds the lock");
            second.handOver(List.of("C:\\Docs\\Invoice.pdf", "C:\\Docs\\Quote.pdf"));

            List<Path> files = requests.poll(10, TimeUnit.SECONDS);
            assertEquals(List.of(Path.of("C:\\Docs\\Invoice.pdf"), Path.of("C:\\Docs\\Quote.pdf")), files);

            second.handOver(List.of());
            assertEquals(List.of(), requests.poll(10, TimeUnit.SECONDS), "no files: just come to the front");
            assertTrue(requests.isEmpty(), "the leftover note wasn't acted on");
            try (var notes = Files.list(dir.resolve("open"))) {
                assertEquals(0, notes.count(), "notes are cleared once read");
            }
        } finally {
            first.release();
        }
        SingleInstance next = new SingleInstance(dir);
        assertTrue(next.claim(), "after the first quits, the next can start");
        next.release();
    }
}
