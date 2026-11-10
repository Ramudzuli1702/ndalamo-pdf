package com.ndalamo;

import com.ndalamo.services.WordAddIn;

/**
 * The installed app's starting point. JavaFX only starts from the class path when the main
 * class isn't itself a JavaFX Application, so the packaged app starts here and hands over
 * to {@link NdalamoApp} straight away.
 *
 * Two switches are for the installer and open no window: --install-word-addin (run after
 * Ndalamo's files are copied, when "Add Delete Page to Microsoft Word" is ticked) and
 * --remove-word-addin (run when Ndalamo is uninstalled). They do what Settings › Word add-in
 * does, then exit.
 */
public final class Launcher {

    static final String INSTALL_WORD_ADDIN = "--install-word-addin";
    static final String REMOVE_WORD_ADDIN = "--remove-word-addin";

    private Launcher() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals(INSTALL_WORD_ADDIN) || args[0].equals(REMOVE_WORD_ADDIN))) {
            System.exit(wordAddIn(args[0].equals(INSTALL_WORD_ADDIN)));
        }
        NdalamoApp.main(args);
    }

    /**
     * Adds or removes the Word add-in for the installer. Without Word on the PC there's nothing to
     * add, which counts as done. 0 when done, 1 when it couldn't be (the installer carries on either
     * way; Settings › Word add-in shows the state and can try again).
     */
    static int wordAddIn(boolean install) {
        try {
            WordAddIn.Status now = WordAddIn.status();
            if (install && now != WordAddIn.Status.UNAVAILABLE && now != WordAddIn.Status.INSTALLED) WordAddIn.install();
            if (!install && (now == WordAddIn.Status.INSTALLED || now == WordAddIn.Status.TURNED_OFF_IN_WORD)) WordAddIn.remove();
            return 0;
        } catch (Exception e) {
            System.err.println("Word add-in: " + e.getMessage());
            return 1;
        }
    }
}
