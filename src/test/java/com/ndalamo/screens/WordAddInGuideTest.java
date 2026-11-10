package com.ndalamo.screens;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Word add-in guide's pictures are all in the app, and are real pictures. */
class WordAddInGuideTest {

    @Test
    void everyStepHasItsPicture() throws Exception {
        List<String> pictures = WordAddInGuide.pictures();
        assertEquals(4, pictures.size());
        for (String name : pictures) {
            var file = WordAddInGuide.class.getResource("/help/word/" + name);
            assertNotNull(file, name + " is missing from resources/help/word");
            BufferedImage image = ImageIO.read(file);
            assertNotNull(image, name + " isn't a picture");
            assertTrue(image.getWidth() >= 300 && image.getHeight() >= 150, name + " is too small to read");
        }
    }
}
