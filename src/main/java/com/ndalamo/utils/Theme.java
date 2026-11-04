package com.ndalamo.utils;

/**
 * The app's colours and shapes, taken from the design tokens in SPEC §6.1.
 * Screens use these instead of typing hex codes, so the look stays consistent
 * and a dark theme can be added in one place later.
 */
public final class Theme {

    private Theme() {}

    // ── Neutrals ─────────────────────────────────────────────────────────
    public static final String BG            = "#F4F5F7"; // window background
    public static final String SURFACE       = "#FFFFFF"; // cards, panels, header
    public static final String BORDER        = "#E2E4E9"; // card borders, dividers
    public static final String BORDER_STRONG = "#D5D8DF"; // inputs, secondary buttons
    public static final String TEXT          = "#16181D";
    public static final String TEXT_MUTED    = "#5B616E";

    // ── Brand and links ──────────────────────────────────────────────────
    public static final String ACCENT        = "#2F5BEA"; // primary buttons, selection, progress
    public static final String ACCENT_SOFT   = "#E8EDFB"; // active nav, selected backgrounds
    public static final String LINK          = "#2448C9";
    public static final String LOGO_GREEN    = "#1E8A4C"; // logo bars and leaf only

    // ── Status (one colour per meaning) ──────────────────────────────────
    public static final String SUCCESS       = "#1E6B3A";
    public static final String SUCCESS_SOFT  = "#E6F4EC";
    public static final String WARNING       = "#9A3412";
    public static final String WARNING_SOFT  = "#FFF4E5";
    public static final String DANGER        = "#B42318";
    public static final String DANGER_SOFT   = "#FDECEA";

    // ── Shape ────────────────────────────────────────────────────────────
    public static final int RADIUS_SM = 8;   // buttons, inputs
    public static final int RADIUS_MD = 12;  // cards
    public static final int RADIUS_LG = 14;  // dialogs

    public static final String FONT = "Segoe UI"; // IBM Plex Sans is bundled in chunk 2
}
