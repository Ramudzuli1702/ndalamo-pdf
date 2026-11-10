package com.ndalamo.models;

import java.util.List;

/**
 * One finished run, for History and the Home sidebar's recent files and tools: when, which
 * tool (or pipeline), the files it was given and the files it made.
 *
 * @param time     milliseconds since 1970
 * @param toolId   the tool's id ("compress"), or "pipeline" for a pipeline run
 * @param toolName as shown ("Compress PDF", or the pipeline's name)
 * @param inputs   full paths of the files given
 * @param outputs  full paths of the files made (may be empty if every file failed)
 */
public record HistoryEntry(long time, String toolId, String toolName, List<String> inputs, List<String> outputs) {

    public HistoryEntry {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }
}
