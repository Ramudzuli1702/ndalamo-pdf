package com.ndalamo.services;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Pipeline;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import com.ndalamo.utils.Format;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A saved pipeline dressed as a tool, so running it uses the same screens as any tool:
 * choose files, progress with Cancel, results. Its only options are the passwords its
 * steps need, asked for on each run (named "s2.password" for step 2's "password").
 */
public class PipelineTool implements PdfTool {

    private final Pipeline pipeline;
    private final Runnable afterRun;

    public PipelineTool(Pipeline pipeline) {
        this(pipeline, () -> {});
    }

    /** @param afterRun called (on the worker thread) after each run, e.g. to count it */
    public PipelineTool(Pipeline pipeline, Runnable afterRun) {
        this.pipeline = pipeline;
        this.afterRun = afterRun;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    @Override public String id() { return "pipeline"; }
    @Override public String name() { return pipeline.getName(); }
    @Override public String category() { return "Pipelines"; }
    @Override public String outputSuffix() { return pipeline.getSuffix(); }

    @Override
    public String description() {
        return String.join(" → ", tools().stream().map(t -> t.map(PdfTool::name).orElse("?")).toList());
    }

    @Override
    public List<OptionField> optionFields() {
        List<OptionField> fields = new ArrayList<>();
        List<Optional<PdfTool>> tools = tools();
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).isEmpty()) continue;
            PdfTool tool = tools.get(i).get();
            for (OptionField field : PipelineService.passwordFields(tool)) {
                fields.add(new OptionField(key(i, field.getId()), field.getLabel() + " · step " + (i + 1) + ", " + tool.name(),
                        OptionField.Type.PASSWORD, null));
            }
        }
        return fields;
    }

    @Override
    public Set<String> fileTypes() {
        return first().map(PdfTool::fileTypes).orElse(Set.of("pdf"));
    }

    @Override
    public int minFiles() {
        return first().map(PdfTool::minFiles).orElse(1);
    }

    /** One result from everything when a step combines files (Merge). */
    @Override
    public boolean oneOutput() {
        return tools().stream().anyMatch(t -> t.map(PdfTool::oneOutput).orElse(false));
    }

    @Override
    public String outputExtension() {
        List<Optional<PdfTool>> tools = tools();
        return tools.isEmpty() ? "pdf" : tools.get(tools.size() - 1).map(PdfTool::outputExtension).orElse("pdf");
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        List<PipelineValidator.Problem> errors = PipelineValidator.check(pipeline).stream()
                .filter(p -> p.severity() == PipelineValidator.Severity.ERROR).toList();
        if (!errors.isEmpty()) return "This pipeline can't run yet: " + errors.get(0).message() + " Edit it to fix this.";
        Map<Integer, Map<String, Object>> asked = asked(options);
        List<Optional<PdfTool>> tools = tools();
        for (int i = 0; i < tools.size(); i++) {
            PdfTool tool = tools.get(i).orElseThrow();
            Map<String, Object> answers = asked.getOrDefault(i, Map.of());
            if (tool.needsPasswords() && !(answers.get(PipelineRunner.FILE_PASSWORD) instanceof String s && !s.isEmpty())) {
                return "Type the password for step " + (i + 1) + " (" + tool.name() + ").";
            }
            // Every step's own checks (say, Crop set to a drawn area with nothing drawn), with the answers typed now.
            Map<String, Object> stepOptions = new HashMap<>(PipelineService.toRun(tool, pipeline.getSteps().get(i).getOptions()));
            stepOptions.putAll(answers);
            String problem = tool.checkOptions(stepOptions);
            if (problem != null) return "Step " + (i + 1) + " (" + tool.name() + "): " + problem;
        }
        return null;
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        try {
            return PipelineRunner.run(pipeline, inputs, asked(options), saveTo, progress);
        } finally {
            if (!progress.isCancelled()) afterRun.run();
        }
    }

    @Override
    public String buttonLabel(int files) {
        return "Run · " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Running " + pipeline.getName() + " on " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return pipeline.getName() + " finished";
    }

    /** The answers typed on the run screen, sorted by step: "s2.password" → step 1 (0-based), "password". */
    static Map<Integer, Map<String, Object>> asked(Map<String, Object> options) {
        Map<Integer, Map<String, Object>> asked = new HashMap<>();
        options.forEach((key, value) -> {
            int dot = key.indexOf('.');
            if (!key.startsWith("s") || dot < 2) return;
            try {
                int step = Integer.parseInt(key.substring(1, dot)) - 1;
                asked.computeIfAbsent(step, k -> new HashMap<>()).put(key.substring(dot + 1), value);
            } catch (NumberFormatException e) {
                // not one of ours
            }
        });
        return asked;
    }

    private static String key(int step, String fieldId) {
        return "s" + (step + 1) + "." + fieldId;
    }

    private List<Optional<PdfTool>> tools() {
        return pipeline.getSteps().stream().map(s -> Tools.find(s.getToolId())).toList();
    }

    private Optional<PdfTool> first() {
        List<Optional<PdfTool>> tools = tools();
        return tools.isEmpty() ? Optional.empty() : tools.get(0);
    }
}
