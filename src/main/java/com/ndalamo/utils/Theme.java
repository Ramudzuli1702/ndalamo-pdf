package com.ndalamo.utils;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * The app's colours and shapes, taken from the design tokens in SPEC §6.1.
 * Screens use these instead of typing hex codes, so the look stays consistent
 * and a dark theme can be added in one place later.
 */
public final class Theme {

    private static final Logger log = LoggerFactory.getLogger(Theme.class);

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

    // ── Tool categories: icon background / icon colour (SPEC §6.1) ──────
    public static String categoryTint(String category) {
        return switch (category) {
            case "Organize" -> "#E8EDFB";
            case "Optimize" -> "#E6F4EC";
            case "Convert" -> "#FDEEE3";
            case "Edit & sign" -> "#F1ECFB";
            default -> "#ECEEF2";            // Security & compare
        };
    }

    public static String categoryInk(String category) {
        return switch (category) {
            case "Organize" -> "#2448C9";
            case "Optimize" -> "#1E6B3A";
            case "Convert" -> "#B4440C";
            case "Edit & sign" -> "#5B35B5";
            default -> "#2E333D";
        };
    }

    // ── Fonts: IBM Plex Sans, bundled in src/main/resources/fonts ────────
    // JavaFX reads Plex's Medium and SemiBold files as separate font families,
    // so asking for "IBM Plex Sans" in semibold silently falls back to regular.
    // Use these names instead of a font weight. Bold works normally.
    public static final String FONT          = "IBM Plex Sans";
    public static final String FONT_MEDIUM   = "IBM Plex Sans Medm";
    public static final String FONT_SEMIBOLD = "IBM Plex Sans SmBld";

    /** Loads the bundled fonts. Call once at startup, before any screen is built. */
    public static void loadFonts() {
        for (String weight : new String[] {"Regular", "Medium", "SemiBold", "Bold"}) {
            String file = "/fonts/IBMPlexSans-" + weight + ".ttf";
            try (InputStream in = Theme.class.getResourceAsStream(file)) {
                if (in == null || Font.loadFont(in, 14) == null) {
                    log.warn("Could not load font {}; falling back to the system font", file);
                }
            } catch (IOException e) {
                log.warn("Could not load font {}", file, e);
            }
        }
    }
}
