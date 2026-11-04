package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.PageStamp;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;

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

    /** How the tool screen previews pages (see PagePreview). */
    enum Preview {
        NONE,     // no page preview
        MERGE,    // all pages of the result, in order; drag to reorder
        ROTATE,   // each file's pages; click to choose which to turn
        REMOVE,   // each file's pages; click to mark for removal
        SPLIT,    // pages coloured by the new file they go into; click to start a new file
        STAMP     // each file's pages with the page number or watermark drawn where it will go
    }

    /** Option key for the page order dragged into place in the preview (a List of PageRef). */
    String PAGE_ORDER = "pageOrder";

    /** Option key for passwords typed in the password dialog (a Map of file to password). Never saved. */
    String PASSWORDS = "passwords";

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

    /**
     * Checks the options before anything runs, e.g. a page list typed as "1-x".
     * Returns a plain sentence to show under the run button, or null if all is well.
     */
    default String checkOptions(Map<String, Object> options) {
        return null;
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

    // ── Page preview, with defaults for tools that don't have one ────────

    default Preview preview() {
        return Preview.NONE;
    }

    /** The text option that clicking pages writes into, e.g. "pages" for "2, 5-7". */
    default String pagesOption() {
        return null;
    }

    /** ROTATE / REMOVE: the pages the current options apply to, for a file with this many pages. */
    default List<Integer> previewPages(Map<String, Object> options, int pageCount) {
        return List.of();
    }

    /** ROTATE: how far chosen pages turn, in degrees clockwise. */
    default int previewTurn(Map<String, Object> options) {
        return 0;
    }

    /** SPLIT: which pages go into each new file, or null if that's only known when it runs. */
    default List<PageRanges.Range> previewParts(Map<String, Object> options, int pageCount) {
        return null;
    }

    /** STAMP: what gets drawn on this page and where, or null for nothing on this page. */
    default PageStamp previewStamp(Map<String, Object> options, int page, int pageCount) {
        return null;
    }

    /** True if the tool screen should ask for the password of each locked file before running (Unlock). */
    default boolean needsPasswords() {
        return false;
    }

    /** Extra line under "Save to" on the tool screen, e.g. where Split puts its parts. Null for none. */
    default String saveToNote() {
        return null;
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
