package com.ndalamo;

/**
 * The installed app's starting point. JavaFX only starts from the class path when the main
 * class isn't itself a JavaFX Application, so the packaged app starts here and hands over
 * to {@link NdalamoApp} straight away.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        NdalamoApp.main(args);
    }
}
