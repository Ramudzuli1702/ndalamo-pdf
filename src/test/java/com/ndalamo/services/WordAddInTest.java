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
    void findsOnlyThisAddInInWordsDisabledItems() {
        // As printed by "reg query" on a PC where Word had switched the add-in off (second value),
        // next to an entry of Word's own (first value) that must be left alone.
        String output = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Office\\16.0\\Word\\Resiliency\\DisabledItems
                    198EFFE    REG_BINARY    0400000010000000480000000061F6847CFFB44FB0C002CD7FB668FE5000720065007600690065007700650072003A0020004D006900630072006F0073006F0066007400200057006F007200640020007000720065007600690065007700650072000000
                    E51C90B    REG_BINARY    01000000880000002400000063003A005C00750073006500720073005C00720061006D00750064007A0075006C0069005C0061007000700064006100740061005C006C006F00630061006C005C006E00640061006C0061006D006F005C0077006F00720064002D0061006400640069006E005C006E00640061006C0061006D006F0077006F00720064002E0064006C006C0000006E00640061006C0061006D006F002E0077006F007200640061006400640069006E000000

                """;
        assertEquals(List.of("E51C90B"), WordAddIn.disabledNames(output));
        assertEquals(List.of(), WordAddIn.disabledNames(""));
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
