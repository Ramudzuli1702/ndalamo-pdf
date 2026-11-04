package com.ndalamo.utils;

import com.ndalamo.utils.PageRanges.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageRangesTest {

    @Test
    void readsPagesAndRangesInTheOrderTyped() {
        assertEquals(List.of(new Range(8, 10), new Range(1, 3), new Range(5, 5)),
                PageRanges.parse("8-10, 1-3, 5", 12));
    }

    @Test
    void openEndedRangeRunsToTheLastPage() {
        assertEquals(List.of(new Range(8, 12)), PageRanges.parse("8-", 12));
    }

    @Test
    void toleratesSpacesAndSemicolons() {
        assertEquals(List.of(new Range(1, 2), new Range(4, 4)), PageRanges.parse(" 1 - 2 ; 4 ", 5));
    }

    @Test
    void explainsTypingMistakesPlainly() {
        assertEquals("“1-x” isn't a page or range. Use numbers like 1-3, 5, 8-10.", PageRanges.check("1-x"));
        assertEquals("“5-2” goes backwards. Write the smaller page first, like 2-5.", PageRanges.check("5-2"));
        assertEquals("Pages start at 1, so “0” doesn't match a page.", PageRanges.check("0"));
        assertNotNull(PageRanges.check("   "));
        assertNull(PageRanges.check("1-3, 5"));
    }

    @Test
    void explainsPagesThatDontExist() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PageRanges.parse("4-8", 5));
        assertEquals("This file has only 5 pages, so “4-8” doesn't fit.", e.getMessage());
    }

    @Test
    void listsEachPageOnceInOrder() {
        assertEquals(List.of(1, 2, 3, 5), PageRanges.pages(PageRanges.parse("5, 1-3, 2", 6)));
    }

    @Test
    void labelsRangesForFileNames() {
        assertEquals("page 5", new Range(5, 5).label());
        assertEquals("pages 1-3", new Range(1, 3).label());
    }
}
