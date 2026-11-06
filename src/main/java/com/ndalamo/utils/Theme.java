package com.ndalamo.utils;

import com.ndalamo.models.Settings;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * The app's colours and shapes (SPEC §6.1), and switching between light and dark.
 *
 * The colours are the role names defined in app.css (e.g. "-nd-text"), not hex
 * codes, so inline styles and icons follow the theme: dark.css redefines the roles,
 * and everything using them changes with it. Use them in styles, e.g.
 * {@code setStyle("-fx-background-color: " + Theme.ACCENT)}, or pass them to Icons.
 */
public final class Theme {

    private static final Logger log = LoggerFactory.getLogger(Theme.class);

    private static boolean dark;

    private Theme() {}

    // ── Neutrals ─────────────────────────────────────────────────────────
    public static final String BG            = "-nd-bg";             // window background
    public static final String SURFACE       = "-nd-surface";        // cards, panels, header
    public static final String BORDER        = "-nd-border";         // card borders, dividers
    public static final String BORDER_STRONG = "-nd-border-strong";  // inputs, secondary buttons
    public static final String TEXT          = "-nd-text";
    public static final String TEXT_MUTED    = "-nd-text-muted";
    public static final String TEXT_FAINT    = "-nd-text-faint";

    // ── Brand and links ──────────────────────────────────────────────────
    public static final String ACCENT        = "-nd-accent";         // primary buttons, selection, progress
    public static final String ACCENT_SOFT   = "-nd-accent-soft";    // active nav, selected backgrounds
    public static final String LINK          = "-nd-link";
    /** The logo's green; a brand colour, the same in both themes. */
    public static final String LOGO_GREEN    = "#1E8A4C";
    /** The brand blue as a fixed colour, for drawings that can't use a role (the logo tile). */
    public static final String BRAND_BLUE    = "#2F5BEA";

    // ── Status (one colour per meaning) ──────────────────────────────────
    public static final String SUCCESS       = "-nd-success";
    public static final String SUCCESS_SOFT  = "-nd-success-soft";
    public static final String WARNING       = "-nd-warning-deep";
    public static final String WARNING_SOFT  = "-nd-warning-soft";
    public static final String DANGER        = "-nd-danger";
    public static final String DANGER_SOFT   = "-nd-danger-soft";

    // ── Shape ────────────────────────────────────────────────────────────
    public static final int RADIUS_SM = 8;   // buttons, inputs
    public static final int RADIUS_MD = 12;  // cards
    public static final int RADIUS_LG = 14;  // dialogs

    // ── Tool categories: icon background / icon colour (SPEC §6.1) ──────
    public static String categoryTint(String category) {
        return "-nd-cat-" + categoryKey(category) + "-tint";
    }

    public static String categoryInk(String category) {
        return "-nd-cat-" + categoryKey(category) + "-ink";
    }

    private static String categoryKey(String category) {
        return switch (category) {
            case "Organize" -> "organize";
            case "Optimize" -> "optimize";
            case "Convert" -> "convert";
            case "Edit & sign" -> "edit";
            default -> "security";             // Security & compare
        };
    }

    // ── Light and dark ───────────────────────────────────────────────────

    /** True while the dark theme is on, for the few drawings that pick fixed colours (the logo). */
    public static boolean isDark() {
        return dark;
    }

    /**
     * Turns the dark theme on or off for the window: adds or removes dark.css, which
     * redefines the colour roles. "Follow system" reads Windows' light/dark setting.
     */
    public static void apply(Scene scene, Settings.Appearance appearance) {
        dark = appearance == Settings.Appearance.DARK
                || appearance == Settings.Appearance.FOLLOW_SYSTEM && Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        URL sheet = Theme.class.getResource("/css/dark.css");
        if (sheet == null) return;
        String darkSheet = sheet.toExternalForm();
        scene.getStylesheets().remove(darkSheet);
        if (dark) scene.getStylesheets().add(darkSheet);
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
