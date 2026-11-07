package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs a pipeline on some files (SPEC §5): each step's results are the next step's
 * inputs. Steps in between write to a temp folder; only the last step writes where
 * results go, named after the original file and the pipeline ("{name}_client.pdf").
 * The originals are never changed, and the temp folder is deleted afterwards.
 *
 * A file that a step can't handle stops there with a note saying which step; the other
 * files carry on. A step that skips a file (nothing to do) passes it on unchanged.
 */
public final class PipelineRunner {

    /** Extra option for a step whose tool asks for each file's password (Unlock): one password for the run. */
    public static final String FILE_PASSWORD = "pipeline.filePassword";

    /** A file on its way through, and the original it came from. */
    private record Item(Path file, Path original, long originalBytes) {}

    private PipelineRunner() {}

    /**
     * @param asked   per step (0-based), the answers asked for when the run started: passwords,
     *                which are never saved with the pipeline
     * @param saveTo  where results go, as for any tool
     */
    public static List<FileResult> run(Pipeline pipeline, List<Path> inputs, Map<Integer, Map<String, Object>> asked,
                                       SaveTo saveTo, Progress progress) throws IOException {
        List<FileResult> results = new ArrayList<>();
        List<PipelineValidator.Problem> errors = PipelineValidator.check(pipeline).stream()
                .filter(p -> p.severity() == PipelineValidator.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            String why = "The pipeline can't run yet: " + lowerFirst(errors.get(0).message()) + " Open it in the builder to fix it.";
            for (Path input : inputs) results.add(failed(input, why));
            return results;
        }

        List<Pipeline.Step> steps = pipeline.getSteps();
        SaveTo finalSaveTo = saveTo.withToolWord(pipeline.getSuffix());
        Path temp = Files.createTempDirectory("ndalamo-pipeline-");
        try {
            List<Item> items = new ArrayList<>();
            for (Path input : inputs) items.add(new Item(input, input, Files.size(input)));
            for (int i = 0; i < steps.size() && !items.isEmpty(); i++) {
                PdfTool tool = Tools.find(steps.get(i).getToolId()).orElseThrow();
                boolean last = i == steps.size() - 1;
                String stepName = "Step " + (i + 1) + " (" + tool.name() + ")";
                Progress stepProgress = progress.part((double) i / steps.size(), 1.0 / steps.size(),
                        "Step " + (i + 1) + " of " + steps.size() + " · " + tool.name());
                Map<String, Object> options = PipelineService.toRun(tool, steps.get(i).getOptions());
                Map<String, Object> answers = asked.getOrDefault(i, Map.of());

                // Tools that make one file from all (Merge) get everything at once; the rest one file at a time,
                // so each result is known to come from one original.
                List<List<Item>> batches = tool.oneOutput() ? List.of(items) : items.stream().map(List::of).toList();
                List<Item> next = new ArrayList<>();
                for (int b = 0; b < batches.size(); b++) {
                    if (progress.isCancelled()) return results;
                    List<Item> batch = batches.get(b);
                    Item first = batch.get(0);
                    SaveTo target = last
                            ? (finalSaveTo.getFolder() != null ? finalSaveTo : finalSaveTo.inFolder(first.original().toAbsolutePath().getParent()))
                            : new SaveTo(Files.createDirectories(temp.resolve("step" + (i + 1)).resolve("file" + next.size() + "-" + b)),
                                         "{name}", Settings.IfExists.REPLACE);
                    Map<String, Object> runOptions = new LinkedHashMap<>(options);
                    answers.forEach((key, value) -> {
                        if (!key.equals(FILE_PASSWORD)) runOptions.put(key, value);
                    });
                    if (answers.get(FILE_PASSWORD) instanceof String password) {
                        Map<Path, String> passwords = new HashMap<>();
                        for (Item item : batch) passwords.put(item.file(), password);
                        runOptions.put(PdfTool.PASSWORDS, passwords);
                    }
                    List<Path> files = batch.stream().map(Item::file).toList();
                    List<FileResult> stepResults;
                    try {
                        stepResults = tool.run(files, runOptions, target, stepProgress.part((double) b / batches.size(), 1.0 / batches.size(), null));
                    } catch (Exception e) {
                        String reason = e.getMessage() == null ? "it stopped unexpectedly" : e.getMessage();
                        for (Item item : batch) results.add(failed(item.original(), stepName + " couldn't finish: " + reason));
                        continue;
                    }
                    long bytes = batch.stream().mapToLong(Item::originalBytes).sum();
                    for (FileResult r : stepResults) {
                        Item from = batch.stream().filter(it -> it.file().equals(r.getInput())).findFirst().orElse(first);
                        switch (r.getStatus()) {
                            case FAILED -> results.add(failed(from.original(), stepName + ": " + r.getNote()));
                            case SKIPPED -> {
                                // Nothing for this step to do (say, OCR on a file that already has text): pass it on.
                                if (last) results.add(new FileResult(from.original(), null, from.originalBytes(), 0,
                                        FileResult.Status.SKIPPED, stepName + ": " + r.getNote()));
                                else next.add(from);
                            }
                            default -> {
                                if (last) {
                                    results.add(new FileResult(from.original(), r.getOutput(), tool.oneOutput() ? bytes : from.originalBytes(),
                                            r.getSizeAfter(), r.getStatus(), r.getNote()));
                                } else if (r.getOutput() != null) {
                                    next.add(new Item(r.getOutput(), from.original(), tool.oneOutput() ? bytes : from.originalBytes()));
                                }
                            }
                        }
                    }
                }
                items = next;
            }
            progress.update(1, "Done");
            return results;
        } finally {
            deleteFolder(temp);
        }
    }

    private static FileResult failed(Path original, String note) {
        long size;
        try {
            size = Files.size(original);
        } catch (IOException e) {
            size = 0;
        }
        return FileResult.failed(original, size, note);
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static void deleteFolder(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    p.toFile().deleteOnExit();
                }
            });
        } catch (IOException e) {
            folder.toFile().deleteOnExit();
        }
    }
}
