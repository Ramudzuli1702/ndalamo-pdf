package com.ndalamo.screens;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PagePreviewTest {

    @Test
    void clickedSplitPointsBecomeRangesCoveringTheWholeFile() {
        assertEquals("1-", PagePreview.cutsToText(new TreeSet<>()));
        assertEquals("1-3, 4-", PagePreview.cutsToText(new TreeSet<>(List.of(4))));
        assertEquals("1, 2-5, 6-", PagePreview.cutsToText(new TreeSet<>(List.of(2, 6))));
    }
}
