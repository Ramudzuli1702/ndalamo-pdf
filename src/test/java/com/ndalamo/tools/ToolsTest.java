package com.ndalamo.tools;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {

    private static List<String> names(List<PdfTool> tools) {
        return tools.stream().map(PdfTool::name).toList();
    }

    @Test
    void emptySearchShowsEveryTool() {
        assertEquals(Tools.all(), Tools.search(""));
        assertEquals(Tools.all(), Tools.search("   "));
    }

    @Test
    void searchMatchesNameOrDescriptionIgnoringCase() {
        assertEquals(List.of("PDF to Word"), names(Tools.search("to word")));
        assertTrue(names(Tools.search("COMPRESS")).containsAll(List.of("Compress PDF", "Compress to size")));
        assertEquals(List.of("Protect PDF"), names(Tools.search("password")).subList(0, 1));
    }

    @Test
    void searchWithNoMatchesIsEmpty() {
        assertTrue(Tools.search("spreadsheet wizard").isEmpty());
    }

    @Test
    void everyToolHasAUniqueIdAndAKnownCategory() {
        Set<String> ids = new HashSet<>();
        for (PdfTool tool : Tools.all()) {
            assertTrue(ids.add(tool.id()), "duplicate id " + tool.id());
            assertTrue(Tools.CATEGORIES.contains(tool.category()), tool.id() + " has unknown category");
        }
    }
}
