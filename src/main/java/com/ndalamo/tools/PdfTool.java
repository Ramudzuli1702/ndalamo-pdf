package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The shape every tool follows (Merge, Compress, Split, ...). Because they all look
 * the same, one tool screen, the pipeline builder and watch folders can run any tool.
 *
 * Rules for every tool (SPEC §3.3):
 * - no JavaFX code;
 * - never write to an input file: write a temp file, then move it to the final name;
 * - check progress.isCancelled() between pages, and delete half-written output on cancel;
 * - one bad file must not stop the others: return FAILED/SKIPPED for it and carry on.
 */
public interface PdfTool {

    /** Stable id used in saved pipelines, e.g. "compress". Never change it once released. */
    String id();

    /** Name on the tool card, e.g. "Compress PDF". */
    String name();

    /** One line under the name, e.g. "Smaller files, same pages". */
    String description();

    /** Home-screen group: "Organize", "Optimize", "Convert", "Edit & sign" or "Security & compare". */
    String category();

    /** The options panel is built from these. Empty if the tool has no options. */
    List<OptionField> optionFields();

    /**
     * Does the work, on a background thread. Returns one result per input file.
     * Throws only if the whole run fails; per-file problems go in the results.
     */
    List<FileResult> run(List<Path> inputs, Map<String, Object> options, Progress progress) throws Exception;
}
