package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Pipeline;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the saved pipelines in pipelines.json in %APPDATA%\Ndalamo (SPEC §3.6), and turns
 * tool options into what can be saved and back. Passwords are never written to the file.
 */
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public PipelineService() {
        this(SettingsService.appDataFolder());
    }

    /** Uses pipelines.json in the given folder; tests point this at a temp folder. */
    public PipelineService(Path folder) {
        this.file = folder.resolve("pipelines.json");
    }

    public List<Pipeline> load() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<Pipeline> pipelines = GSON.fromJson(Files.readString(file), new TypeToken<List<Pipeline>>() {}.getType());
            return pipelines == null ? new ArrayList<>() : new ArrayList<>(pipelines);
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read {}; starting with no pipelines", file, e);
            return new ArrayList<>();
        }
    }

    /** Writes to a temp file and swaps it in, so a crash while saving can't leave a half-written file. */
    public void save(List<Pipeline> pipelines) throws IOException {
        // Passwords are asked for on each run; make sure none reaches the file, however a step was made.
        for (Pipeline pipeline : pipelines) {
            for (Pipeline.Step step : pipeline.getSteps()) {
                Tools.find(step.getToolId()).ifPresent(tool -> tool.optionFields().stream()
                        .filter(f -> f.getType() == OptionField.Type.PASSWORD)
                        .forEach(f -> step.getOptions().remove(f.getId())));
            }
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "pipelines-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(pipelines));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The options worth saving for a step: only the tool's own fields, never passwords, with
     * files kept as their path text. (Page orders dragged in a preview belong to one set of
     * files, so they aren't kept either.)
     */
    public static Map<String, Object> toSaved(PdfTool tool, Map<String, Object> options) {
        Map<String, Object> saved = new LinkedHashMap<>();
        for (OptionField field : tool.optionFields()) {
            Object value = options.get(field.getId());
            if (value == null || field.getType() == OptionField.Type.PASSWORD) continue;
            saved.put(field.getId(), value instanceof Path p ? p.toString() : value);
        }
        return saved;
    }

    /**
     * Saved options back in the shapes the tool expects: JSON gives numbers as decimals and
     * lists for arrays, so a drawn area becomes a double[] again and a file a Path. Options the
     * tool no longer has are dropped; missing ones take the tool's default.
     */
    public static Map<String, Object> toRun(PdfTool tool, Map<String, Object> saved) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (OptionField field : tool.optionFields()) {
            Object value = saved.containsKey(field.getId()) ? saved.get(field.getId()) : field.getDefaultValue();
            if (value == null) continue;
            options.put(field.getId(), switch (field.getType()) {
                case NUMBER -> value instanceof Number n ? n.doubleValue() : value;
                case FILE -> value instanceof String s ? Path.of(s) : value;
                case AREA -> value instanceof List<?> list ? list.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray() : value;
                default -> value;
            });
        }
        return options;
    }

    /** The password boxes a step's tool has (asked for on each run), plus Unlock's own password question. */
    public static List<OptionField> passwordFields(PdfTool tool) {
        List<OptionField> fields = new ArrayList<>(tool.optionFields().stream()
                .filter(f -> f.getType() == OptionField.Type.PASSWORD).toList());
        if (tool.needsPasswords()) fields.add(new OptionField(PipelineRunner.FILE_PASSWORD, "Password to open the files",
                OptionField.Type.PASSWORD, null));
        return fields;
    }
}
