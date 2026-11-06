package com.ndalamo.models;

import java.util.List;

/**
 * One field of a PDF form, as Fill forms shows it: what it's called, what kind of
 * answer it takes, the choices for a choice field, and the answer already in it.
 *
 * @param name     the field's full name in the PDF, used to write the answer back
 * @param label    what to show the user: the field's tooltip if it has one, else its name
 * @param kind     what kind of answer it takes
 * @param choices  for RADIO and LIST: the possible answers; empty otherwise
 * @param value    the answer in the PDF now ("" if none; "true"/"false" for a tick box)
 * @param page     the page it's on, from 1, so fields can be listed in page order
 * @param readOnly the form doesn't allow changing it
 */
public record FormField(String name, String label, Kind kind, List<String> choices, String value, int page, boolean readOnly) {

    public enum Kind { TEXT, LONG_TEXT, TICK_BOX, RADIO, LIST }
}
