package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The "Page workspace" card. It isn't a run-once tool: clicking it opens the
 * workspace screen, where pages are edited by hand and saved from there
 * (WorkspaceScreen and WorkspaceService). It's listed here so it shows on Home
 * and in search like every other tool.
 */
public class WorkspaceTool implements PdfTool {

    @Override public String id() { return "workspace"; }
    @Override public String name() { return "Page workspace"; }
    @Override public String description() { return "Reorder, rotate, remove, crop"; }
    @Override public String category() { return "Organize"; }
    @Override public List<OptionField> optionFields() { return List.of(); }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress) {
        throw new UnsupportedOperationException("The page workspace is opened as a screen, not run as a job");
    }
}
