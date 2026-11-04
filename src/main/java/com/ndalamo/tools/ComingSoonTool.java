package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A placeholder for a tool that isn't built yet, so the home screen can show
 * every tool from the start. Its card says "Coming soon" and can't be opened.
 * Each build chunk swaps its placeholders in {@link Tools} for the real tools.
 */
public class ComingSoonTool implements PdfTool {

    private final String id;
    private final String name;
    private final String description;
    private final String category;

    public ComingSoonTool(String id, String name, String description, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public String category() { return category; }
    @Override public List<OptionField> optionFields() { return List.of(); }
    @Override public boolean isReady() { return false; }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress) {
        throw new UnsupportedOperationException(name + " isn't built yet");
    }
}
