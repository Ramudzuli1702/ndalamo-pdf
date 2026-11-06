package com.ndalamo.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SaveToTest {

    @TempDir
    Path dir;

    @Test
    void askEveryTimeIsPassedToTheToolScreen() {
        Settings settings = new Settings();
        settings.setSaveLocation(Settings.SaveLocation.ASK);
        SaveTo saveTo = SaveTo.from(settings);

        assertTrue(saveTo.asksForFolder());
        assertEquals("A folder you choose when you start", saveTo.describeFolder(false));
        assertFalse(saveTo.inFolder(dir).asksForFolder(), "once chosen, it doesn't ask again");
        assertEquals(dir, saveTo.inFolder(dir).getFolder());
    }

    @Test
    void theNameToCheckIsTheRealNameNeverTheOriginal() throws Exception {
        Path input = Files.writeString(dir.resolve("Report.pdf"), "x");
        SaveTo plain = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ASK);
        assertEquals(dir.resolve("Report_compressed.pdf"), plain.plainOutputFor(input, "compressed", "pdf"));

        SaveTo sameName = new SaveTo(null, "{name}", Settings.IfExists.ASK);
        assertNotEquals(input, sameName.plainOutputFor(input, "compressed", "pdf"), "never the original file");
    }

    @Test
    void theAnswerForOneRunIsKept() {
        SaveTo ask = new SaveTo(null, "{name}_{tool}", Settings.IfExists.ASK);
        assertTrue(ask.asksIfExists());
        assertFalse(ask.withIfExists(Settings.IfExists.REPLACE).asksIfExists());
    }
}
