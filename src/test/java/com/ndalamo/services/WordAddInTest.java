package com.ndalamo.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry entries for the Word add-in. Nothing is written to the registry here; the
 * commands are checked as text, and the IDs against the C# project so the two can't drift apart.
 */
class WordAddInTest {

    private static final Path DLL = Path.of("C:\\Users\\Some One\\AppData\\Local\\Ndalamo\\word-addin\\NdalamoWord.dll");

    @Test
    void registersTheClassForThisUserOnly() {
        List<List<String>> commands = WordAddIn.installCommands(DLL);
        for (List<String> command : commands) {
            assertEquals("reg", command.get(0));
            assertEquals("add", command.get(1));
            assertTrue(command.get(2).startsWith("HKCU\\"), "only this user's settings: " + command);
            assertEquals("/f", command.get(command.size() - 1), "never prompts: " + command);
        }
        assertTrue(commands.contains(List.of("reg", "add", WordAddIn.CLSID_KEY + "\\InprocServer32",
                "/ve", "/t", "REG_SZ", "/d", "mscoree.dll", "/f")));
        assertTrue(commands.contains(List.of("reg", "add", WordAddIn.CLSID_KEY + "\\InprocServer32",
                "/v", "CodeBase", "/t", "REG_SZ", "/d", "file:///C:/Users/Some%20One/AppData/Local/Ndalamo/word-addin/NdalamoWord.dll", "/f")));
        assertTrue(commands.contains(List.of("reg", "add", WordAddIn.WORD_KEY,
                "/v", "LoadBehavior", "/t", "REG_DWORD", "/d", "3", "/f")));
    }

    @Test
    void removingStopsWordLoadingItFirst() {
        List<List<String>> commands = WordAddIn.removeCommands();
        assertEquals(List.of("reg", "delete", WordAddIn.WORD_KEY, "/f"), commands.get(0));
        assertTrue(commands.stream().allMatch(c -> c.get(2).startsWith("HKCU\\")));
    }

    @Test
    void idsMatchTheWordProject() throws IOException {
        Path project = Path.of("word-addin");
        String connect = Files.readString(project.resolve("Connect.cs"));
        String classId = WordAddIn.CLASS_ID.substring(1, WordAddIn.CLASS_ID.length() - 1);
        assertTrue(connect.contains("ClassId = \"" + classId + "\""));
        assertTrue(connect.contains("ProgramId = \"" + WordAddIn.PROG_ID + "\""));
        assertTrue(connect.contains("namespace Ndalamo.WordAddIn") && connect.contains("public class Connect"));
        assertEquals("Ndalamo.WordAddIn.Connect", WordAddIn.CLASS_NAME);
        String csproj = Files.readString(project.resolve("NdalamoWord.csproj"));
        assertTrue(csproj.contains("<AssemblyName>NdalamoWord</AssemblyName>"));
        assertTrue(csproj.contains("<AssemblyVersion>1.0.0.0</AssemblyVersion>"));
        assertTrue(WordAddIn.ASSEMBLY.startsWith("NdalamoWord, Version=1.0.0.0,"));
    }
}
