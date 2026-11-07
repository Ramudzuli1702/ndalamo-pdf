package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.services.PipelineRunner;
import com.ndalamo.services.PipelineService;
import com.ndalamo.services.PipelineValidator;
import com.ndalamo.services.PipelineValidator.Fix;
import com.ndalamo.services.PipelineValidator.Problem;
import com.ndalamo.services.PipelineValidator.Severity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pipelines (SPEC §5): checking, running and saving them. */
class PipelineTest {

    @TempDir
    Path dir;

    private final SaveTo sameFolder = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER);

    private static Pipeline.Step step(String toolId, Map<String, Object> options) {
        return new Pipeline.Step(toolId, options);
    }

    private static Pipeline.Step step(String toolId) {
        return step(toolId, Map.of());
    }

    private static Pipeline pipeline(String name, Pipeline.Step... steps) {
        return new Pipeline(name, List.of(steps));
    }

    // ── Checking ──────────────────────────────────────────────────────────

    @Test
    void aSensiblePipelineHasNoProblems() {
        assertEquals(List.of(), PipelineValidator.check(pipeline("Client documents", step("ocr"), step("compress"), step("protect"))));
    }

    @Test
    void protectMustBeLastAndTheFixMovesIt() {
        Pipeline p = pipeline("p", step("protect"), step("compress"));
        List<Problem> problems = PipelineValidator.check(p);
        assertEquals(1, problems.size());
        assertEquals(new Problem(0, Severity.WARNING, problems.get(0).message(), Fix.MOVE_TO_END), problems.get(0));
        PipelineValidator.fix(p, problems.get(0));
        assertEquals(List.of("compress", "protect"), p.toolIds());
        assertTrue(PipelineValidator.check(p).isEmpty());
    }

    @Test
    void mergeMustBeFirst() {
        Pipeline p = pipeline("p", step("compress"), step("merge"));
        Problem problem = PipelineValidator.check(p).get(0);
        assertEquals(Fix.MOVE_TO_START, problem.fix());
        PipelineValidator.fix(p, problem);
        assertEquals(List.of("merge", "compress"), p.toolIds());
    }

    @Test
    void stepsMustFitWhatTheStepBeforeMakes() {
        List<Problem> problems = PipelineValidator.check(pipeline("p", step("pdf-to-excel"), step("compress")));
        assertEquals(1, problems.size());
        assertEquals(Severity.ERROR, problems.get(0).severity());
        assertEquals(Fix.REMOVE, problems.get(0).fix());
        assertTrue(problems.get(0).message().contains("XLSX"), problems.get(0).message());
        assertTrue(PipelineValidator.canRun(pipeline("p", step("image-to-pdf"), step("compress"))));
    }

    @Test
    void handWorkToolsCantBeSteps() {
        for (String id : List.of("sign", "fill-forms", "workspace", "compare")) {
            assertFalse(PipelineValidator.canRun(pipeline("p", step(id))), id);
        }
        assertFalse(PipelineValidator.canRun(pipeline("p")));
        assertFalse(PipelineValidator.canRun(pipeline("p", step("no-such-tool"))));
    }

    // ── Running ───────────────────────────────────────────────────────────

    @Test
    void eachStepWorksOnTheResultOfTheOneBefore() throws Exception {
        Path a = TestFiles.pdf(dir, "Invoice.pdf", "Invoice", 3), b = TestFiles.pdf(dir, "Quote.pdf", "Quote", 2);
        byte[] before = TestFiles.bytes(a);
        Pipeline p = pipeline("Client documents",
                step("rotate", Map.of(RotateTool.TURN, RotateTool.RIGHT)),
                step("page-numbers", Map.of(PageNumbersTool.STYLE, PageNumbersTool.PAGE_OF)),
                step("compress"));

        List<FileResult> results = PipelineRunner.run(p, List.of(a, b), Map.of(), sameFolder, new Progress());

        assertEquals(2, results.size());
        assertEquals(dir.resolve("Invoice_client.pdf"), results.get(0).getOutput());
        assertEquals(a, results.get(0).getInput(), "results point back at the original");
        assertEquals(dir.resolve("Quote_client.pdf"), results.get(1).getOutput());
        try (PDDocument out = Loader.loadPDF(results.get(0).getOutput().toFile())) {
            assertEquals(3, out.getNumberOfPages());
            assertEquals(90, out.getPage(0).getRotation(), "step 1 ran");
        }
        assertTrue(TestFiles.text(results.get(0).getOutput()).contains("Page 3 of 3"), "step 2 ran on step 1's result");
        assertArrayEquals(before, TestFiles.bytes(a));
        try (var files = Files.list(dir)) {
            assertEquals(4, files.count(), "only the two results were added next to the originals");
        }
    }

    @Test
    void mergeFirstMakesOneResult() throws Exception {
        Path a = TestFiles.pdf(dir, "Part1.pdf", "One", 2), b = TestFiles.pdf(dir, "Part2.pdf", "Two", 3);
        List<FileResult> results = PipelineRunner.run(pipeline("Bundle", step("merge"), step("compress")),
                List.of(a, b), Map.of(), sameFolder, new Progress());
        assertEquals(1, results.size());
        assertEquals(5, TestFiles.pageCount(results.get(0).getOutput()));
        assertEquals("Part1_bundle.pdf", results.get(0).getOutput().getFileName().toString());
        assertEquals(Files.size(a) + Files.size(b), results.get(0).getSizeBefore());
    }

    @Test
    void passwordsAreAskedForEachRunAndNeverSaved() throws Exception {
        Path a = TestFiles.pdf(dir, "Payslip.pdf", "Payslip", 1);
        Pipeline p = pipeline("Secure", step("compress"), step("protect", Map.of(ProtectTool.PASSWORD, "typed-in-builder")));
        // What gets saved for the step has no password in it.
        assertNull(PipelineService.toSaved(new ProtectTool(), Map.of(ProtectTool.PASSWORD, "x", ProtectTool.ALLOW_PRINTING, true))
                .get(ProtectTool.PASSWORD));
        PipelineService store = new PipelineService(dir.resolve("app"));
        store.save(List.of(p));
        String json = Files.readString(dir.resolve("app").resolve("pipelines.json"));
        assertFalse(json.contains("typed-in-builder"), "the store never writes a password, however the step was made");
        assertTrue(json.contains("\"protect\""));

        Map<Integer, Map<String, Object>> asked = Map.of(1, Map.of(ProtectTool.PASSWORD, "s3cret", ProtectTool.CONFIRM, "s3cret"));
        FileResult result = PipelineRunner.run(p, List.of(a), asked, sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(result.getOutput().toFile()).close());
        try (PDDocument opened = Loader.loadPDF(result.getOutput().toFile(), "s3cret")) {
            assertEquals(1, opened.getNumberOfPages());
        }
    }

    @Test
    void unlockUsesThePasswordAskedForAtTheStart() throws Exception {
        Path locked = TestFiles.lockedPdf(dir, "Locked.pdf");
        Pipeline p = pipeline("Open up", step("unlock"), step("compress"));
        assertEquals(1, PipelineService.passwordFields(new UnlockTool()).size());

        FileResult result = PipelineRunner.run(p, List.of(locked),
                Map.of(0, Map.of(PipelineRunner.FILE_PASSWORD, "user-secret")), sameFolder, new Progress()).get(0);

        assertEquals(FileResult.Status.SUCCESS, result.getStatus(), result.getNote());
        Loader.loadPDF(result.getOutput().toFile()).close();    // opens without a password now
    }

    @Test
    void aFileAStepCantHandleStopsThereAndTheOthersCarryOn() throws Exception {
        Path good = TestFiles.pdf(dir, "Good.pdf", "Good", 2);
        Path bad = dir.resolve("Broken.pdf");
        Files.writeString(bad, "this is not a PDF at all");
        List<FileResult> results = PipelineRunner.run(pipeline("p", step("rotate"), step("compress")),
                List.of(bad, good), Map.of(), sameFolder, new Progress());

        assertEquals(2, results.size());
        FileResult failed = results.stream().filter(r -> r.getStatus() == FileResult.Status.FAILED).findFirst().orElseThrow();
        assertEquals(bad, failed.getInput());
        assertTrue(failed.getNote().startsWith("Step 1 (Rotate pages)"), failed.getNote());
        assertTrue(results.stream().anyMatch(r -> r.getStatus() == FileResult.Status.SUCCESS && r.getInput().equals(good)));
    }

    @Test
    void aPipelineWithErrorsDoesntRun() throws Exception {
        Path a = TestFiles.pdf(dir, "A.pdf", "A", 1);
        FileResult result = PipelineRunner.run(pipeline("p", step("pdf-to-excel"), step("compress")),
                List.of(a), Map.of(), sameFolder, new Progress()).get(0);
        assertEquals(FileResult.Status.FAILED, result.getStatus());
        assertTrue(result.getNote().startsWith("The pipeline can't run yet"), result.getNote());
    }

    @Test
    void cancellingStopsBeforeAnythingIsWritten() throws Exception {
        Path a = TestFiles.pdf(dir, "A.pdf", "A", 1);
        Progress progress = new Progress();
        progress.cancel();
        assertTrue(PipelineRunner.run(pipeline("p", step("compress")), List.of(a), Map.of(), sameFolder, progress).isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    // ── Saving ────────────────────────────────────────────────────────────

    @Test
    void pipelinesAreSavedAndReadBackWithTheirOptions() throws Exception {
        PipelineService store = new PipelineService(dir);
        Pipeline p = pipeline("Shrink & number",
                step("compress", PipelineService.toSaved(new CompressTool(), Map.of(CompressTool.MODE, CompressTool.TARGET, CompressTool.TARGET_MB, 2.5))),
                step("crop", Map.of(CropTool.AREA, new double[] {0.1, 0.1, 0.9, 0.9})));
        p.setPinned(true);
        store.save(List.of(p, p.duplicate()));

        List<Pipeline> loaded = store.load();

        assertEquals(2, loaded.size());
        assertEquals("Shrink & number", loaded.get(0).getName());
        assertEquals("shrink", loaded.get(0).getSuffix());
        assertTrue(loaded.get(0).isPinned());
        assertEquals("Shrink & number (copy)", loaded.get(1).getName());
        assertFalse(loaded.get(1).isPinned());
        assertFalse(loaded.get(0).getId().equals(loaded.get(1).getId()));
        Map<String, Object> compress = PipelineService.toRun(new CompressTool(), loaded.get(0).getSteps().get(0).getOptions());
        assertEquals(2.5, compress.get(CompressTool.TARGET_MB));
        assertEquals(CompressTool.TARGET, compress.get(CompressTool.MODE));
        assertEquals(true, compress.get(CompressTool.DOWNSCALE), "options not saved take the tool's default");
        Object area = PipelineService.toRun(new CropTool(), loaded.get(0).getSteps().get(1).getOptions()).get(CropTool.AREA);
        assertArrayEquals(new double[] {0.1, 0.1, 0.9, 0.9}, (double[]) area);
        assertTrue(new PipelineService(dir.resolve("nothing-here")).load().isEmpty());
    }
}
