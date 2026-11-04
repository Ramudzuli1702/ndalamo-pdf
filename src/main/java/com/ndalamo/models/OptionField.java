package com.ndalamo.models;

import java.util.List;

/**
 * One option a tool offers, e.g. "Target size (MB)". The tool screen builds its
 * options panel from these, so tools never have to create any UI themselves.
 */
public class OptionField {

    public enum Type { TEXT, NUMBER, CHOICE, CHECKBOX, FILE, PASSWORD }

    private final String id;            // key in the options map passed to the tool
    private final String label;
    private final Type type;
    private final List<String> choices; // only for CHOICE
    private final Object defaultValue;
    private boolean advanced;           // shown under "Advanced options"

    public OptionField(String id, String label, Type type, Object defaultValue) {
        this(id, label, type, List.of(), defaultValue);
    }

    public OptionField(String id, String label, Type type, List<String> choices, Object defaultValue) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.choices = List.copyOf(choices);
        this.defaultValue = defaultValue;
    }

    /** Moves this option under "Advanced options". Returns itself so it can be chained. */
    public OptionField advanced() {
        this.advanced = true;
        return this;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public Type getType() { return type; }
    public List<String> getChoices() { return choices; }
    public Object getDefaultValue() { return defaultValue; }
    public boolean isAdvanced() { return advanced; }
}
