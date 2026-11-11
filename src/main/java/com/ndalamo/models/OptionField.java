package com.ndalamo.models;

import java.util.List;

/**
 * One option a tool offers, e.g. "Target size (MB)". The tool screen builds its
 * options panel from these, so tools never have to create any UI themselves.
 */
public class OptionField {

    /** AREA: a box the user drags on the first page; the value is {left, top, right, bottom} as fractions of the page as shown. */
    /** FORM: a PDF form's fields, filled in on FormDialog; the value is a Map of field name to answer. */
    /** SIGNATURE: made on SignatureDialog and placed on PlaceSignatureDialog; the value is a Signature. */
    public enum Type { TEXT, NUMBER, CHOICE, CHECKBOX, FILE, PASSWORD, AREA, FORM, SIGNATURE, EDITS }

    private final String id;            // key in the options map passed to the tool
    private final String label;
    private final Type type;
    private final List<String> choices; // only for CHOICE
    private final Object defaultValue;
    private boolean advanced;           // shown under "Advanced options"
    private String hint;                // example text shown in an empty box, e.g. "e.g. 1-3, 5"
    private List<String> choiceDescriptions = List.of();   // one line under each choice, optional
    private String shownWhenId;         // only show this field when another (choice) field...
    private String shownWhenValue;      // ...has this value

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

    /** Example text shown in the empty box, e.g. "e.g. 2, 5-7". Returns itself so it can be chained. */
    public OptionField hint(String text) {
        this.hint = text;
        return this;
    }

    /** A short line under each choice, in the same order, e.g. "Good quality, good size". */
    public OptionField describe(String... descriptions) {
        this.choiceDescriptions = List.of(descriptions);
        return this;
    }

    /** Only shows this field while the choice field {@code choiceId} is set to {@code value}. */
    public OptionField shownWhen(String choiceId, String value) {
        this.shownWhenId = choiceId;
        this.shownWhenValue = value;
        return this;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public Type getType() { return type; }
    public List<String> getChoices() { return choices; }
    public Object getDefaultValue() { return defaultValue; }
    public boolean isAdvanced() { return advanced; }
    public String getHint() { return hint; }
    public List<String> getChoiceDescriptions() { return choiceDescriptions; }
    public String getShownWhenId() { return shownWhenId; }
    public String getShownWhenValue() { return shownWhenValue; }
}
