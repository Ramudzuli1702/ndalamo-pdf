package com.ndalamo.services;

import com.ndalamo.models.OptionField;
import com.ndalamo.models.Pipeline;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks a pipeline before it's saved or run (SPEC §5) and says how to fix each problem.
 * Warnings can be fixed with one click in the builder (move a step); errors stop the
 * pipeline from running until the step is changed or removed.
 */
public final class PipelineValidator {

    public enum Severity { WARNING, ERROR }

    /** The one-click fix offered under a problem. */
    public enum Fix { MOVE_TO_END, MOVE_TO_START, REMOVE, NONE }

    /** A problem with step {@code step} (0-based; -1 for the pipeline as a whole). */
    public record Problem(int step, Severity severity, String message, Fix fix) {}

    private PipelineValidator() {}

    public static List<Problem> check(Pipeline pipeline) {
        List<Problem> problems = new ArrayList<>();
        List<Pipeline.Step> steps = pipeline.getSteps();
        if (steps.isEmpty()) {
            problems.add(new Problem(-1, Severity.ERROR, "Add at least one step.", Fix.NONE));
            return problems;
        }
        String previousOutput = null;
        String previousName = null;
        for (int i = 0; i < steps.size(); i++) {
            Optional<PdfTool> found = Tools.find(steps.get(i).getToolId());
            if (found.isEmpty() || !found.get().isReady()) {
                problems.add(new Problem(i, Severity.ERROR, "This tool isn't available in this version of NdalamoPDF.", Fix.REMOVE));
                previousOutput = null;
                continue;
            }
            PdfTool tool = found.get();
            String whyNot = whyNotInPipeline(tool);
            if (whyNot != null) problems.add(new Problem(i, Severity.ERROR, whyNot, Fix.REMOVE));
            if (tool.id().equals("merge") && i > 0) {
                problems.add(new Problem(i, Severity.WARNING,
                        "Merge combines the files you start with, so it has to be the first step.", Fix.MOVE_TO_START));
            }
            if (tool.id().equals("protect") && i < steps.size() - 1) {
                problems.add(new Problem(i, Severity.WARNING,
                        "Protect locks the file, so the steps after it couldn't change it. It has to be the last step.", Fix.MOVE_TO_END));
            }
            if (previousOutput != null && !tool.fileTypes().contains(previousOutput)) {
                problems.add(new Problem(i, Severity.ERROR, tool.name() + " can't use the " + previousOutput.toUpperCase()
                        + " files that " + previousName + " makes. Remove it, or put a step before it that makes "
                        + kinds(tool) + ".", Fix.REMOVE));
            }
            previousOutput = tool.outputExtension();
            previousName = tool.name();
        }
        return problems;
    }

    /** True if the pipeline can run: warnings are allowed, errors aren't. */
    public static boolean canRun(Pipeline pipeline) {
        return check(pipeline).stream().noneMatch(p -> p.severity() == Severity.ERROR);
    }

    /**
     * Why a tool can't be a pipeline step, or null if it can. Steps run without anyone
     * watching, so tools that need someone to draw, sign or fill in each file can't.
     */
    public static String whyNotInPipeline(PdfTool tool) {
        if (!tool.isReady()) return tool.name() + " isn't built yet.";
        if (tool.id().equals("workspace")) return "The page workspace is for working by hand, so it can't be a step.";
        if (tool.id().equals("compare")) return "Compare needs two versions to look at, so it can't be a step.";
        for (OptionField field : tool.optionFields()) {
            switch (field.getType()) {
                case SIGNATURE -> {
                    return tool.name() + " needs you to place your signature on each file, so it can't be a step.";
                }
                case FORM -> {
                    return tool.name() + " needs you to fill in each form, so it can't be a step.";
                }
                case EDITS -> {
                    return tool.name() + " needs you to make the changes on each file, so it can't be a step.";
                }
                default -> { }
            }
        }
        return null;
    }

    /** Applies the fix offered for a problem: moves or removes that step. */
    public static void fix(Pipeline pipeline, Problem problem) {
        List<Pipeline.Step> steps = new ArrayList<>(pipeline.getSteps());
        if (problem.step() < 0 || problem.step() >= steps.size()) return;
        Pipeline.Step step = steps.remove(problem.step());
        switch (problem.fix()) {
            case MOVE_TO_END -> steps.add(step);
            case MOVE_TO_START -> steps.add(0, step);
            case REMOVE -> { }
            case NONE -> steps.add(problem.step(), step);
        }
        pipeline.setSteps(steps);
    }

    /** "PDFs", "pictures" or a list of file types, for messages. */
    private static String kinds(PdfTool tool) {
        if (tool.fileTypes().equals(java.util.Set.of("pdf"))) return "PDFs";
        if (tool.fileTypes().contains("jpg")) return "pictures";
        return String.join(", ", tool.fileTypes().stream().sorted().map(t -> t.toUpperCase()).toList()) + " files";
    }
}
