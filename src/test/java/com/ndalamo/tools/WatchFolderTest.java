package com.ndalamo.tools;

import com.ndalamo.models.Activity;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.models.WatchFolder;
import com.ndalamo.services.WatchFolderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Watch folders (SPEC §5): files put in a folder run through its pipeline by themselves. */
class WatchFolderTest {

    @TempDir
    Path dir;

    private WatchFolderService service;
    private final List<Pipeline> pipelines = new ArrayList<>();
    private final List<Activity> heard = new CopyOnWriteArrayList<>();
    private final List<int[]> bursts = new CopyOnWriteArrayList<>();

    private Path in, out;
    private Pipeline compress;

    private void setUp() throws Exception {
        in = Files.createDirectories(dir.resolve("Scans"));
        out = Files.createDirectories(dir.resolve("Done"));
        compress = new Pipeline("Shrink", List.of(new Pipeline.Step("compress", Map.of())));
        pipelines.add(compress);
        service = new WatchFolderService(dir.resolve("app"), () -> pipelines,
                () -> new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER), Duration.ofMillis(200), Duration.ofMillis(300));
        service.setListener(new WatchFolderService.Listener() {
            @Override public void activity(Activity entry) { heard.add(entry); }
            @Override public void burstFinished(int done, int failed) { bursts.add(new int[] {done, failed}); }
        });
    }

    @AfterEach
    void stop() {
        if (service != null) service.stop(Duration.ofSeconds(5));
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    @Test
    void aFilePutInTheFolderIsProcessedAndTheOriginalMovedAside() throws Exception {
        setUp();
        service.add(new WatchFolder(in.toString(), compress.getId(), out.toString()));
        service.start();
        Path made = TestFiles.pdf(dir, "Invoice.pdf", "Invoice", 2);
        byte[] original = TestFiles.bytes(made);
        Files.move(made, in.resolve("Invoice.pdf"));

        waitFor(() -> Files.exists(out.resolve("Invoice_shrink.pdf")) && Files.exists(in.resolve("processed").resolve("Invoice.pdf")),
                "the result and the moved original");

        assertFalse(Files.exists(in.resolve("Invoice.pdf")));
        assertArrayEquals(original, TestFiles.bytes(in.resolve("processed").resolve("Invoice.pdf")), "the original isn't changed");
        assertEquals(2, TestFiles.pageCount(out.resolve("Invoice_shrink.pdf")));
        waitFor(() -> !heard.isEmpty() && !bursts.isEmpty(), "the log line and the summary");
        Activity entry = heard.get(0);
        assertTrue(entry.ok() && entry.result().startsWith("Done · "), entry.result());
        assertEquals("Invoice.pdf", entry.file());
        assertEquals("Shrink", entry.pipeline());
        assertEquals(1, bursts.get(0)[0]);
        assertEquals(1, service.folders().get(0).getProcessed());
    }

    @Test
    void filesAlreadyWaitingAreDoneOtherFilesIgnored() throws Exception {
        setUp();
        Files.move(TestFiles.pdf(dir, "Early.pdf", "Early", 1), in.resolve("Early.pdf"));
        Files.writeString(in.resolve("notes.txt"), "not a PDF");
        Files.writeString(in.resolve("Upload.pdf.part"), "still arriving");
        service.add(new WatchFolder(in.toString(), compress.getId(), out.toString()));
        service.start();

        waitFor(() -> Files.exists(out.resolve("Early_shrink.pdf")), "the file that was already there");
        Thread.sleep(800);
        assertTrue(Files.exists(in.resolve("notes.txt")) && Files.exists(in.resolve("Upload.pdf.part")), "other files are left alone");
        try (var outputs = Files.list(out)) {
            assertEquals(1, outputs.count());
        }
    }

    @Test
    void aFileStillBeingWrittenWaits() throws Exception {
        setUp();
        service.add(new WatchFolder(in.toString(), compress.getId(), out.toString()));
        service.start();
        Path made = TestFiles.pdf(dir, "Big.pdf", "Big", 1);
        Path arriving = in.resolve("Big.pdf");
        try (RandomAccessFile file = new RandomAccessFile(arriving.toFile(), "rw"); FileLock lock = file.getChannel().lock()) {
            file.write(Files.readAllBytes(made));
            Thread.sleep(1200);      // well past the settle time, but another program still has it
            assertFalse(Files.exists(out.resolve("Big_shrink.pdf")), "not touched while it's held");
        }
        waitFor(() -> Files.exists(out.resolve("Big_shrink.pdf")), "the file once released");
    }

    @Test
    void aFailedFileStaysAndIsLoggedOnce() throws Exception {
        setUp();
        service.add(new WatchFolder(in.toString(), compress.getId(), out.toString()));
        service.start();
        Files.writeString(in.resolve("Broken.pdf"), "this is not really a PDF");

        waitFor(() -> !heard.isEmpty(), "the failure in the log");
        Thread.sleep(1200);     // several more scans
        assertEquals(1, heard.size(), "not tried again until the file changes");
        assertFalse(heard.get(0).ok());
        assertTrue(heard.get(0).result().contains("left in the folder"), heard.get(0).result());
        assertTrue(Files.exists(in.resolve("Broken.pdf")));
        waitFor(() -> !bursts.isEmpty(), "the summary");
        assertEquals(1, bursts.get(0)[1]);
    }

    @Test
    void leftInPlaceFilesAreNotDoneTwiceEvenAfterARestart() throws Exception {
        setUp();
        WatchFolder folder = new WatchFolder(in.toString(), compress.getId(), out.toString());
        folder.setAfterwards(WatchFolder.Afterwards.LEAVE);
        service.add(folder);
        service.start();
        Files.move(TestFiles.pdf(dir, "Keep.pdf", "Keep", 1), in.resolve("Keep.pdf"));
        waitFor(() -> Files.exists(out.resolve("Keep_shrink.pdf")), "the result");
        assertTrue(Files.exists(in.resolve("Keep.pdf")));
        service.stop(Duration.ofSeconds(5));

        // A new service reading the saved folders, as after restarting the app.
        WatchFolderService again = new WatchFolderService(dir.resolve("app"), () -> pipelines,
                () -> new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER), Duration.ofMillis(200), Duration.ofMillis(300));
        again.load();
        again.start();
        Thread.sleep(1500);
        again.stop(Duration.ofSeconds(5));
        try (var outputs = Files.list(out)) {
            assertEquals(1, outputs.count(), "Keep.pdf wasn't done again");
        }
        assertEquals(1, again.activity().size(), "the log was read back");
    }

    @Test
    void aPausedFolderIsLeftAlone() throws Exception {
        setUp();
        WatchFolder folder = new WatchFolder(in.toString(), compress.getId(), out.toString());
        service.add(folder);
        service.setEnabled(folder, false);
        service.start();
        Files.move(TestFiles.pdf(dir, "Wait.pdf", "Wait", 1), in.resolve("Wait.pdf"));
        Thread.sleep(1200);
        assertFalse(Files.exists(out.resolve("Wait_shrink.pdf")));
        service.setEnabled(folder, true);
        waitFor(() -> Files.exists(out.resolve("Wait_shrink.pdf")), "the result after resuming");
    }

    @Test
    void foldersThatCantWorkAreExplained() throws Exception {
        setUp();
        assertNull(WatchFolderService.check(new WatchFolder(in.toString(), compress.getId(), out.toString()), pipelines, List.of()));
        assertTrue(WatchFolderService.check(new WatchFolder(in.toString(), compress.getId(), in.toString()), pipelines, List.of())
                .startsWith("Results can't go into the watched folder"));
        assertNull(WatchFolderService.check(new WatchFolder(in.toString(), compress.getId(), in.resolve("done").toString()), pipelines, List.of()),
                "a folder inside it is fine");
        Pipeline secure = new Pipeline("Secure", List.of(new Pipeline.Step("protect", Map.of())));
        pipelines.add(secure);
        assertTrue(WatchFolderService.check(new WatchFolder(in.toString(), secure.getId(), out.toString()), pipelines, List.of())
                .contains("asks for a password"));
        WatchFolder existing = new WatchFolder(in.toString(), compress.getId(), out.toString());
        assertTrue(WatchFolderService.check(new WatchFolder(in.toString(), compress.getId(), dir.resolve("x").toString()), pipelines,
                List.of(existing)).startsWith("That folder is already being watched"));
        assertEquals("Choose the folder to watch.",
                WatchFolderService.check(new WatchFolder(dir.resolve("missing").toString(), compress.getId(), out.toString()), pipelines, List.of()));
    }

    @Test
    void theLogCanBeExported() throws Exception {
        setUp();
        service.add(new WatchFolder(in.toString(), compress.getId(), out.toString()));
        service.start();
        Files.move(TestFiles.pdf(dir, "Report, final.pdf", "Report", 1), in.resolve("Report, final.pdf"));
        waitFor(() -> !heard.isEmpty(), "a log line");
        Path csv = dir.resolve("log.csv");
        service.exportLog(csv);
        List<String> lines = Files.readAllLines(csv);
        assertEquals("Time,File,Pipeline,Result,Saved as", lines.get(0));
        assertTrue(lines.get(1).contains("\"Report, final.pdf\",\"Shrink\",\"Done"), lines.get(1));
    }
}
