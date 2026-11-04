package com.ndalamo.screens;

import com.ndalamo.tools.LibreOffice;
import com.ndalamo.utils.FileActions;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * "Office to PDF needs LibreOffice" (mockup: Dialogs.dc.html). Shown when the user
 * opens Office to PDF and LibreOffice can't be found. Buttons:
 * - I've installed it — check again
 * - Locate it (pick soffice.exe; remembered in Settings)
 * - How to install (a help page bundled with the app, opened in the browser; nothing is downloaded)
 * Every other tool works without LibreOffice.
 */
public final class LibreOfficeDialog {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeDialog.class);

    private static final String TITLE = "Office to PDF needs LibreOffice";
    private static final String MESSAGE = "LibreOffice is free and does the Word, Excel and PowerPoint conversion "
            + "on this PC. Install it once, then come back — every other tool works without it.";

    private LibreOfficeDialog() {}

    /**
     * Looks for LibreOffice in the background, then runs {@code onFound} if it's there,
     * or shows the dialog if not. {@code onPicked} saves a soffice.exe chosen with "Locate it".
     */
    public static void whenFound(MainWindow window, Consumer<Path> onPicked, Runnable onFound) {
        check(window, MESSAGE, onPicked, onFound);
    }

    private static void check(MainWindow window, String messageIfMissing, Consumer<Path> onPicked, Runnable onFound) {
        // Searching may ask the Windows registry, so keep it off the JavaFX thread.
        CompletableFuture.supplyAsync(LibreOffice::find).thenAccept(found -> Platform.runLater(() -> {
            if (found != null) onFound.run();
            else show(window, messageIfMissing, onPicked, onFound);
        }));
    }

    private static void show(MainWindow window, String message, Consumer<Path> onPicked, Runnable onFound) {
        new ChoiceDialog(window, TITLE, message, null)
                .width(660)                                         // four buttons, one fairly long
                .button("Cancel", ChoiceDialog.Style.PLAIN, dontAsk -> {})
                .button("I’ve installed it — check again", ChoiceDialog.Style.PLAIN, dontAsk ->
                        check(window, "LibreOffice still couldn’t be found. If the installer is still running, wait "
                                + "for it to finish. If you installed it somewhere unusual, use Locate it.",
                                onPicked, onFound))
                .button("Locate it", ChoiceDialog.Style.PLAIN, dontAsk -> locate(window, onPicked, onFound))
                .button("How to install", ChoiceDialog.Style.PRIMARY, dontAsk -> {
                    openHelpPage();
                    show(window, message, onPicked, onFound);      // stays open so they can check again afterwards
                })
                .show();
    }

    private static void locate(MainWindow window, Consumer<Path> onPicked, Runnable onFound) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Find LibreOffice (soffice.exe)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("LibreOffice (soffice.exe)", "soffice.exe"));
        File usual = new File(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"));
        if (usual.isDirectory()) chooser.setInitialDirectory(usual);
        File picked = chooser.showOpenDialog(window.getScene().getWindow());
        if (picked == null) {
            show(window, MESSAGE, onPicked, onFound);
            return;
        }
        Path program = LibreOffice.programIn(picked.toPath());
        if (program == null) {
            show(window, "That isn’t LibreOffice. Pick soffice.exe, in the “program” folder inside the folder "
                    + "LibreOffice is installed in (usually C:\\Program Files\\LibreOffice\\program).", onPicked, onFound);
            return;
        }
        onPicked.accept(program);
        onFound.run();
    }

    /** Copies the bundled help page next to the settings and opens it in the default browser. */
    private static void openHelpPage() {
        try (InputStream page = LibreOfficeDialog.class.getResourceAsStream("/help/install-libreoffice.html")) {
            if (page == null) throw new IOException("The help page is missing from the app");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path folder = (localAppData != null && !localAppData.isBlank()
                    ? Path.of(localAppData, "Ndalamo") : Path.of(System.getProperty("user.home"), ".ndalamo"))
                    .resolve("help");
            Path file = Files.createDirectories(folder).resolve("install-libreoffice.html");
            Files.copy(page, file, StandardCopyOption.REPLACE_EXISTING);
            FileActions.open(file);
        } catch (IOException e) {
            log.warn("Could not open the LibreOffice help page", e);
        }
    }
}
