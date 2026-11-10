package com.ndalamo.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A folder Ndalamo keeps an eye on (SPEC §5): every file put in it runs through a
 * pipeline, and the results go to the output folder. Saved as JSON with the settings.
 */
public class WatchFolder {

    /** What happens to an original after it has been processed. Deleting it is never offered. */
    public enum Afterwards { MOVE_TO_PROCESSED, LEAVE }

    private String id = UUID.randomUUID().toString();
    private String inputFolder;
    private String pipelineId;
    private String outputFolder;
    private boolean enabled = true;
    private Afterwards afterwards = Afterwards.MOVE_TO_PROCESSED;
    private int processed;
    /** For LEAVE: the files already done ("name|size|modified"), so they aren't done again. Newest last. */
    private List<String> done = new ArrayList<>();

    public WatchFolder() {
    }

    public WatchFolder(String inputFolder, String pipelineId, String outputFolder) {
        this.inputFolder = inputFolder;
        this.pipelineId = pipelineId;
        this.outputFolder = outputFolder;
    }

    /** Remembers a file as done (only needed when originals are left in place); keeps the last 5000. */
    public void remember(String key) {
        if (done == null) done = new ArrayList<>();
        done.add(key);
        if (done.size() > 5000) done.subList(0, done.size() - 5000).clear();
    }

    public boolean isDone(String key) {
        return done != null && done.contains(key);
    }

    public void countOne() {
        processed++;
    }

    public String getId() { return id; }
    public String getInputFolder() { return inputFolder; }
    public void setInputFolder(String inputFolder) { this.inputFolder = inputFolder; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getOutputFolder() { return outputFolder; }
    public void setOutputFolder(String outputFolder) { this.outputFolder = outputFolder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Afterwards getAfterwards() { return afterwards == null ? Afterwards.MOVE_TO_PROCESSED : afterwards; }
    public void setAfterwards(Afterwards afterwards) { this.afterwards = afterwards; }
    public int getProcessed() { return processed; }
}
