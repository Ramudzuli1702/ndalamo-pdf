package com.ndalamo.models;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A saved chain of tools, e.g. "Client documents": OCR → Compress → Protect (SPEC §5).
 * Each step is a tool id and its options; the output of one step is the input of the
 * next. Saved as JSON by PipelineService. Passwords are never saved: steps that need one
 * ask for it each time the pipeline runs.
 */
public class Pipeline {

    private String id = UUID.randomUUID().toString();
    private String name = "New pipeline";
    private String suffix;                 // the word for {tool} in result names; null = made from the name
    private List<Step> steps = new ArrayList<>();
    private boolean pinned;
    private long lastRun;                  // milliseconds since 1970; 0 = never run
    private int runs;

    /** One tool in the chain and the options it runs with. */
    public static class Step {
        private String toolId;
        private Map<String, Object> options = new LinkedHashMap<>();

        public Step() {
        }

        public Step(String toolId, Map<String, Object> options) {
            this.toolId = toolId;
            this.options = new LinkedHashMap<>(options);
        }

        public String getToolId() { return toolId; }
        public Map<String, Object> getOptions() { return options == null ? Map.of() : options; }

        Step copy() {
            Map<String, Object> copied = new LinkedHashMap<>();
            getOptions().forEach((k, v) -> copied.put(k, v instanceof double[] d ? d.clone() : v));
            return new Step(toolId, copied);
        }
    }

    public Pipeline() {
    }

    public Pipeline(String name, List<Step> steps) {
        this.name = name;
        this.steps = new ArrayList<>(steps);
    }

    /** A copy with a new id, for Duplicate: same steps, "(copy)" after the name, not pinned, never run. */
    public Pipeline duplicate() {
        Pipeline copy = new Pipeline(name + " (copy)", steps.stream().map(Step::copy).toList());
        copy.suffix = suffix;
        return copy;
    }

    /** A copy to edit in the builder: the same pipeline (same id), so leaving without saving changes nothing. */
    public Pipeline editableCopy() {
        Pipeline copy = new Pipeline(name, steps.stream().map(Step::copy).toList());
        copy.id = id;
        copy.suffix = suffix;
        copy.pinned = pinned;
        copy.lastRun = lastRun;
        copy.runs = runs;
        return copy;
    }

    /**
     * The word for {tool} in result names: the one set, or the name's first word in lower case
     * ("Client documents" → "client"), keeping only letters and digits so it's safe in a file name.
     */
    public String getSuffix() {
        if (suffix != null && !suffix.isBlank()) return suffix;
        String first = name == null ? "" : name.strip().split("\\s+")[0];
        String safe = first.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return safe.isEmpty() ? "pipeline" : safe;
    }

    /** "OCR → Compress → Protect" style list of the step tool ids; screens use tool names instead. */
    public List<String> toolIds() {
        return steps.stream().map(Step::getToolId).toList();
    }

    public void ranOnce(long now) {
        lastRun = now;
        runs++;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public void setSuffix(String suffix) { this.suffix = suffix; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = new ArrayList<>(steps); }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public long getLastRun() { return lastRun; }
    public int getRuns() { return runs; }

    /** For tests and screens that show where a pipeline's results go. */
    public String resultName(Path original, SaveTo saveTo, String extension) {
        return saveTo.withToolWord(getSuffix()).plainOutputFor(original, getSuffix(), extension).getFileName().toString();
    }
}
