package com.ndalamo.tools;

import com.ndalamo.models.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds LibreOffice on this PC and uses it to turn Word, Excel and PowerPoint files
 * into PDFs. LibreOffice runs as a separate program ("soffice --headless --convert-to
 * pdf"), so none of its code is part of Ndalamo (decision 008). It is not bundled:
 * the user installs it once, and only Office to PDF needs it.
 */
public final class LibreOffice {

    private static final Logger LOG = LoggerFactory.getLogger(LibreOffice.class);

    /** A large spreadsheet can take a while; anything slower than this has hung. */
    private static final long TIME_LIMIT_SECONDS = 10 * 60;

    /** Set from Settings when the user picks soffice.exe with "Locate it"; null to search the usual places. */
    private static volatile String chosenLocation;

    /** One conversion at a time: two LibreOffices sharing one profile folder get in each other's way. */
    private static final Object ONE_AT_A_TIME = new Object();

    private LibreOffice() {}

    public static void setChosenLocation(String location) {
        chosenLocation = location;
    }

    /** soffice.exe, or null if LibreOffice can't be found. */
    public static Path find() {
        if (chosenLocation != null && !chosenLocation.isBlank()) {
            Path chosen = programIn(Path.of(chosenLocation));
            if (chosen != null) return chosen;
        }
        List<Path> usualPlaces = new ArrayList<>();
        for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
            String folder = System.getenv(variable);
            if (folder != null && !folder.isBlank()) usualPlaces.add(Path.of(folder, "LibreOffice"));
        }
        String registered = registeredInstallFolder();
        if (registered != null) usualPlaces.add(Path.of(registered));
        for (Path place : usualPlaces) {
            Path program = programIn(place);
            if (program != null) return program;
        }
        return null;
    }

    /**
     * soffice.exe from whatever the user picked: the file itself, LibreOffice's
     * "program" folder, or the folder LibreOffice is installed in. Null if it isn't there.
     */
    public static Path programIn(Path picked) {
        for (Path candidate : List.of(picked, picked.resolve("soffice.exe"), picked.resolve("program").resolve("soffice.exe"))) {
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().equalsIgnoreCase("soffice.exe")) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    /** The installer writes its program folder to the registry; read it with Windows' own "reg" command. */
    private static String registeredInstallFolder() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            Process reg = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\LibreOffice\\UNO\\InstallPath", "/ve")
                    .redirectErrorStream(true).start();
            String output = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!reg.waitFor(5, TimeUnit.SECONDS) || reg.exitValue() != 0) return null;
            // e.g. "    (Default)    REG_SZ    C:\Program Files\LibreOffice\program"
            for (String line : output.split("\\R")) {
                int type = line.indexOf("REG_SZ");
                if (type >= 0) return line.substring(type + "REG_SZ".length()).strip();
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** LibreOffice couldn't convert a file; the message is a plain sentence for the result screen. */
    public static class ConversionFailed extends IOException {
        ConversionFailed(String plainMessage) {
            super(plainMessage);
        }
    }

    /**
     * Converts one file into a PDF inside {@code outFolder} (an empty temp folder) and
     * returns it. The input is only read. Returns null if the user cancelled, after
     * stopping LibreOffice.
     *
     * LibreOffice uses its own settings folder for Ndalamo, so it works even while the
     * user has LibreOffice open, and never shows its first-start screens.
     */
    static Path convert(Path program, Path input, Path outFolder, Path profileFolder, Progress progress)
            throws IOException, InterruptedException {
        List<String> command = List.of(program.toString(),
                "-env:UserInstallation=" + profileFolder.toUri(),
                "--headless", "--norestore", "--nolockcheck", "--nodefault", "--nofirststartwizard",
                "--convert-to", "pdf", "--outdir", outFolder.toString(), input.toString());
        Path log = outFolder.resolve("libreoffice-output.txt");     // kept out of the pipe so it can't fill up and stall

        synchronized (ONE_AT_A_TIME) {
            Process office = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS);
            while (!office.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (progress.isCancelled()) {
                    stop(office);
                    return null;
                }
                if (System.nanoTime() > deadline) {
                    stop(office);
                    throw new ConversionFailed("LibreOffice stopped responding while converting it.");
                }
            }
        }

        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path pdf = outFolder.resolve((dot > 0 ? name.substring(0, dot) : name) + ".pdf");
        if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
            if (Files.exists(log)) LOG.warn("LibreOffice couldn't convert {}: {}", name, Files.readString(log).strip());
            throw new ConversionFailed("LibreOffice couldn't convert it. Is it damaged, or locked with a password?");
        }
        return pdf;
    }

    /** soffice.exe starts soffice.bin, so stop that too. */
    private static void stop(Process office) throws InterruptedException {
        office.descendants().forEach(ProcessHandle::destroyForcibly);
        office.destroyForcibly();
        office.waitFor(10, TimeUnit.SECONDS);
    }
}
