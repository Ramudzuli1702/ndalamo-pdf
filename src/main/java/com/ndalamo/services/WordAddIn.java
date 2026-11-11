package com.ndalamo.services;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Installs and removes the Word add-in ("Delete Page", the C# project in word-addin/,
 * decision 015). Installing copies the add-in's DLL to %LOCALAPPDATA%\Ndalamo\word-addin
 * and registers it for this Windows user only (HKEY_CURRENT_USER), so no administrator
 * rights are needed. Registry changes go through Windows' own "reg" command. Word picks
 * the change up the next time it starts.
 * Methods here run processes and copy files: call them off the JavaFX thread.
 */
public final class WordAddIn {

    /** Must match Connect.ClassId and Connect.ProgramId in the C# project. */
    static final String CLASS_ID = "{6E1F3C52-8D0B-4C7A-9E43-2B5D7A1C9F08}";
    static final String PROG_ID = "Ndalamo.WordAddIn";
    static final String CLASS_NAME = "Ndalamo.WordAddIn.Connect";
    static final String ASSEMBLY = "NdalamoWord, Version=1.0.0.0, Culture=neutral, PublicKeyToken=null";
    static final String DLL_NAME = "NdalamoWord.dll";

    static final String CLSID_KEY = "HKCU\\Software\\Classes\\CLSID\\" + CLASS_ID;
    static final String PROG_ID_KEY = "HKCU\\Software\\Classes\\" + PROG_ID;
    static final String WORD_KEY = "HKCU\\Software\\Microsoft\\Office\\Word\\Addins\\" + PROG_ID;
    private static final String WORD_PATH_KEY = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\Winword.exe";
    /** Where Word lists add-ins it has switched off after a problem (Word 2016 and later, then 2013). */
    private static final List<String> DISABLED_ITEMS_KEYS = List.of(
            "HKCU\\Software\\Microsoft\\Office\\16.0\\Word\\Resiliency\\DisabledItems",
            "HKCU\\Software\\Microsoft\\Office\\15.0\\Word\\Resiliency\\DisabledItems");
    /** Windows' category for .NET classes, written the same way .NET's own regasm does. */
    private static final String DOTNET_CATEGORY = "{62C8FE65-4EBB-45e7-B440-6E39B2CDBF29}";

    public enum Status {
        /** Not Windows, Word isn't installed, or this copy of Ndalamo came without the add-in. */
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLED,
        /** Installed, but Word has turned it off (it does that after a problem while starting). */
        TURNED_OFF_IN_WORD
    }

    /** Why Status is UNAVAILABLE, in a sentence for the Settings card; null otherwise. */
    public static String unavailableReason() {
        if (!isWindows()) return "The Word add-in works with Microsoft Word for Windows only.";
        if (!wordInstalled()) return "Microsoft Word isn't installed on this PC.";
        if (bundledDll().isEmpty()) return "The add-in's files are missing from this copy of NdalamoPDF. Reinstall NdalamoPDF to get them back.";
        return null;
    }

    public static Status status() {
        boolean registered = isWindows() && query(WORD_KEY, "LoadBehavior").isPresent();
        if (registered) {
            String loadBehavior = query(WORD_KEY, "LoadBehavior").orElse("");
            // 3 = load at start-up. Word lowers it to 2, or lists it under Disabled Items, when it turns it off.
            boolean on = loadBehavior.endsWith("0x3") && disabledEntries().isEmpty();
            return on ? Status.INSTALLED : Status.TURNED_OFF_IN_WORD;
        }
        return unavailableReason() == null ? Status.NOT_INSTALLED : Status.UNAVAILABLE;
    }

    /**
     * Copies the add-in to this user's app-data folder and registers it with Word.
     * Throws with a plain message when that can't be done (for example Word has the old copy open).
     */
    public static void install() throws IOException {
        Path source = bundledDll().orElseThrow(() -> new IOException(unavailableReason()));
        Path target = installedDll();
        Files.createDirectories(target.getParent());
        try {
            if (!Files.exists(target) || Files.mismatch(source, target) != -1) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (AccessDeniedException e) {
            throw new IOException("Word is using the add-in right now. Close Word, then click Install again.", e);
        } catch (FileSystemException e) {
            throw new IOException("The add-in couldn't be copied (" + e.getReason() + "). Close Word, then try again.", e);
        }
        for (List<String> command : installCommands(target)) run(command);
        // If Word switched the add-in off after a problem, it would stay off; installing again turns it back on.
        for (String[] entry : disabledEntries()) run(List.of("reg", "delete", entry[0], "/v", entry[1], "/f"));
    }

    /** Word's Disabled Items entries for this add-in, as {key, value name}. */
    private static List<String[]> disabledEntries() {
        List<String[]> entries = new ArrayList<>();
        if (!isWindows()) return entries;
        for (String key : DISABLED_ITEMS_KEYS) {
            for (String name : disabledNames(regOutput(List.of("reg", "query", key)))) entries.add(new String[] {key, name});
        }
        return entries;
    }

    /**
     * The values in "reg query" output of a Disabled Items key that are about this add-in. Each value is
     * binary, holding the DLL's path and the add-in's name as UTF-16 text.
     */
    static List<String> disabledNames(String regQueryOutput) {
        List<String> names = new ArrayList<>();
        for (String line : regQueryOutput.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 3 || !parts[1].equals("REG_BINARY") || parts[2].length() % 2 != 0) continue;
            byte[] bytes = new byte[parts[2].length() / 2];
            try {
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(parts[2].substring(2 * i, 2 * i + 2), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String text = new String(bytes, StandardCharsets.UTF_16LE).toLowerCase(java.util.Locale.ROOT);
            if (text.contains(DLL_NAME.toLowerCase(java.util.Locale.ROOT)) || text.contains(PROG_ID.toLowerCase(java.util.Locale.ROOT))) {
                names.add(parts[0]);
            }
        }
        return names;
    }

    /** What a "reg" command prints, or "" if it fails (for example, the key doesn't exist). */
    private static String regOutput(List<String> command) {
        try {
            Process reg = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return reg.waitFor(5, TimeUnit.SECONDS) && reg.exitValue() == 0 ? output : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Unregisters the add-in and deletes its copy (unless Word still has it open; it's harmless then). */
    public static void remove() throws IOException {
        for (List<String> command : removeCommands()) run(command);
        try {
            Files.deleteIfExists(installedDll());
        } catch (IOException e) {
            // Word has the file open until it closes; the next install simply replaces it.
        }
    }

    /** Where the add-in is copied to: %LOCALAPPDATA%\Ndalamo\word-addin\NdalamoWord.dll. */
    static Path installedDll() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null ? Path.of(localAppData) : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("Ndalamo").resolve("word-addin").resolve(DLL_NAME);
    }

    /**
     * The add-in that came with this copy of Ndalamo: set with -Dndalamo.wordaddin, next to the
     * app's files (word-addin\NdalamoWord.dll), or the C# project's build output while developing.
     */
    static Optional<Path> bundledDll() {
        List<Path> candidates = new ArrayList<>();
        String chosen = System.getProperty("ndalamo.wordaddin");
        if (chosen != null) candidates.add(Path.of(chosen));
        try {
            Path code = Path.of(WordAddIn.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path folder = Files.isDirectory(code) ? code : code.getParent();
            if (folder != null) {
                candidates.add(folder.resolve("word-addin").resolve(DLL_NAME));
                if (folder.getParent() != null) candidates.add(folder.getParent().resolve("word-addin").resolve(DLL_NAME));
            }
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // No code location (unusual); fall through to the development path.
        }
        candidates.add(Path.of("word-addin", "bin", "Release", "net48", DLL_NAME).toAbsolutePath());
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /**
     * The registry entries .NET's regasm would write for the add-in class, but under
     * HKEY_CURRENT_USER, plus Word's entry that loads it at start-up (LoadBehavior 3).
     */
    static List<List<String>> installCommands(Path dll) {
        String codeBase = dll.toAbsolutePath().toUri().toString();
        List<List<String>> commands = new ArrayList<>();
        commands.add(add(CLSID_KEY, null, CLASS_NAME));
        for (String server : List.of(CLSID_KEY + "\\InprocServer32", CLSID_KEY + "\\InprocServer32\\1.0.0.0")) {
            if (server.endsWith("InprocServer32")) {
                commands.add(add(server, null, "mscoree.dll"));
                commands.add(add(server, "ThreadingModel", "Both"));
            }
            commands.add(add(server, "Class", CLASS_NAME));
            commands.add(add(server, "Assembly", ASSEMBLY));
            commands.add(add(server, "RuntimeVersion", "v4.0.30319"));
            commands.add(add(server, "CodeBase", codeBase));
        }
        commands.add(add(CLSID_KEY + "\\ProgId", null, PROG_ID));
        commands.add(List.of("reg", "add", CLSID_KEY + "\\Implemented Categories\\" + DOTNET_CATEGORY, "/f"));
        commands.add(add(PROG_ID_KEY, null, CLASS_NAME));
        commands.add(add(PROG_ID_KEY + "\\CLSID", null, CLASS_ID));
        commands.add(add(WORD_KEY, "FriendlyName", "NdalamoPDF Delete Page"));
        commands.add(add(WORD_KEY, "Description", "Adds Delete Page to the Home tab and the right-click menu."));
        commands.add(List.of("reg", "add", WORD_KEY, "/v", "LoadBehavior", "/t", "REG_DWORD", "/d", "3", "/f"));
        return commands;
    }

    /** Word's entry first, so Word stops loading the add-in even if the rest fails. */
    static List<List<String>> removeCommands() {
        return List.of(
                List.of("reg", "delete", WORD_KEY, "/f"),
                List.of("reg", "delete", PROG_ID_KEY, "/f"),
                List.of("reg", "delete", CLSID_KEY, "/f"));
    }

    private static List<String> add(String key, String name, String value) {
        List<String> command = new ArrayList<>(List.of("reg", "add", key));
        if (name == null) command.add("/ve");
        else command.addAll(List.of("/v", name));
        command.addAll(List.of("/t", "REG_SZ", "/d", value, "/f"));
        return command;
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Windows didn't answer while setting up the add-in. Try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Setting up the add-in was interrupted. Try again.", e);
        }
        // Deleting a key that is already gone is fine.
        boolean alreadyGone = command.get(1).equals("delete") && output.contains("unable to find");
        if (process.exitValue() != 0 && !alreadyGone) {
            throw new IOException("Windows refused a change to the add-in's settings (" + output + "). Try again.");
        }
    }

    /** One value from the registry, e.g. "0x3" for a DWORD; empty when the key or value isn't there. */
    private static Optional<String> query(String key, String name) {
        try {
            List<String> command = new ArrayList<>(List.of("reg", "query", key));
            if (name == null) command.add("/ve");
            else command.addAll(List.of("/v", name));
            Process reg = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!reg.waitFor(5, TimeUnit.SECONDS) || reg.exitValue() != 0) return Optional.empty();
            // e.g. "    LoadBehavior    REG_DWORD    0x3"
            for (String line : output.split("\\R")) {
                int type = line.indexOf("REG_");
                if (type < 0) continue;
                int value = line.indexOf("    ", type);
                return Optional.of(value < 0 ? "" : line.substring(value).strip());
            }
            return Optional.of("");
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean wordInstalled() {
        return query(WORD_PATH_KEY, null).isPresent();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private WordAddIn() {
    }
}
