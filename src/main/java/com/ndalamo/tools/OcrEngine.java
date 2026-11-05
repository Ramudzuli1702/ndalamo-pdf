package com.ndalamo.tools;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Reads the words on a page picture with Tesseract (through Tess4J), fully offline.
 *
 * Tesseract needs its language files as real files on disk, but they're bundled
 * inside the app; the first time OCR runs they're copied to
 * %LOCALAPPDATA%\Ndalamo\tessdata and reused after that.
 *
 * One engine per job: Tesseract can't be shared between threads.
 */
final class OcrEngine {

    /** Bundled languages: Tesseract's code for each. */
    static final List<String> BUNDLED = List.of("eng", "afr");

    private final Tesseract tesseract = new Tesseract();

    /** @param languages Tesseract codes joined by "+", e.g. "eng" or "eng+afr" */
    OcrEngine(String languages) throws IOException {
        tesseract.setDatapath(dataFolder().toString());
        tesseract.setLanguage(languages);
        tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);     // find columns and blocks by itself
        tesseract.setVariable("user_defined_dpi", "300");                // pages are drawn at 300 dpi
    }

    /**
     * Tesseract is native Windows code and needs Microsoft's Visual C++ runtime. If that's
     * missing or too old, it fails as a low-level Error ("Invalid memory access",
     * UnsatisfiedLinkError) — this explains it instead of showing a stack trace.
     */
    static ToolProblem cantStart(Error e) {
        return new ToolProblem("OCR couldn't start on this PC. It needs a Windows component, the Microsoft "
                + "Visual C++ runtime, which seems to be missing or out of date. Your files were not changed. "
                + "Installing the latest “Microsoft Visual C++ Redistributable” usually fixes this.", e);
    }

    /** The words Tesseract found, with their boxes in the picture's pixels. */
    List<Word> words(BufferedImage page) {
        return tesseract.getWords(page, ITessAPI.TessPageIteratorLevel.RIL_WORD);
    }

    /** Where the language files are on disk, copying them out of the app the first time. */
    static Path dataFolder() throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path folder = (localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "Ndalamo") : Path.of(System.getProperty("user.home"), ".ndalamo"))
                .resolve("tessdata");
        Files.createDirectories(folder);
        for (String language : BUNDLED) {
            URL bundled = OcrEngine.class.getResource("/tessdata/" + language + ".traineddata");
            if (bundled == null) throw new IOException("Missing bundled OCR language " + language);
            Path file = folder.resolve(language + ".traineddata");
            // Copy again if missing or a different size (e.g. a newer app version bundles newer data).
            long bundledSize = bundled.openConnection().getContentLengthLong();
            if (Files.exists(file) && Files.size(file) == bundledSize) continue;
            Path temp = Files.createTempFile(folder, language, ".tmp");
            try (InputStream in = bundled.openStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return folder;
    }
}
