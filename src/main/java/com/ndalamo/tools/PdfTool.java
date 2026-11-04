package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shape every tool follows (Merge, Compress, Split, ...). Because they all look
 * the same, one tool screen, the pipeline builder and watch folders can run any tool.
 *
 * Rules for every tool (SPEC §3.3):
 * - no JavaFX code;
 * - never write to an input file: get the output path from saveTo, write with SafeSave;
 * - check progress.isCancelled() between pages, and leave no output behind on cancel;
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
     * Does the work, on a background thread. Returns one result per output (or per
     * failed input). Throws only if the whole run fails; per-file problems go in the results.
     */
    List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws Exception;

    // ── Optional details, with sensible defaults ─────────────────────────

    /** False only for {@link ComingSoonTool} placeholders, which Home shows greyed out. */
    default boolean isReady() {
        return true;
    }

    /** File extensions (lower case, no dot) the tool accepts. */
    default Set<String> fileTypes() {
        return Set.of("pdf");
    }

    /** Fewest files the tool can work with, e.g. 2 for Merge. */
    default int minFiles() {
        return 1;
    }

    /** True if the tool makes one output from all inputs (Merge), false if one per input. */
    default boolean oneOutput() {
        return false;
    }

    /** The word for {tool} in output names, e.g. "compressed". */
    default String outputSuffix() {
        return id();
    }

    /** Run button, e.g. "Compress 3 files". */
    default String buttonLabel(int files) {
        return name() + " · " + Format.count(files, "file", "files");
    }

    /** Progress heading, e.g. "Compressing 3 files…". */
    default String workingLabel(int files) {
        return "Working on " + Format.count(files, "file", "files") + "…";
    }

    /** Result heading, e.g. "3 files compressed". */
    default String doneLabel(int files) {
        return "Done";
    }
}
