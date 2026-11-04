package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.PageRef;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.models.Settings;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.PageRanges.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What each tool tells the page preview, and Merge following a dragged page order. */
class PreviewTest {

    @TempDir
    Path dir;

    @Test
    void pageListsAreWrittenBackAsText() {
        assertEquals("1-3, 5, 8-9", PageRanges.format(List.of(9, 1, 2, 3, 5, 8, 2)));
        assertEquals("", PageRanges.format(List.of()));
    }

    @Test
    void rotateHighlightsAllPagesOrOnlyTheOnesTyped() {
        RotateTool rotate = new RotateTool();
        assertEquals(List.of(1, 2, 3), rotate.previewPages(Map.of(RotateTool.WHICH, RotateTool.ALL), 3));
        assertEquals(List.of(2, 3), rotate.previewPages(
                Map.of(RotateTool.WHICH, RotateTool.SOME, RotateTool.PAGES, "2-3"), 5));
        assertEquals(270, rotate.previewTurn(Map.of(RotateTool.TURN, RotateTool.LEFT)));
    }

    @Test
    void halfTypedPagesHighlightNothingInsteadOfFailing() {
        assertEquals(List.of(), new RemovePagesTool().previewPages(Map.of(RemovePagesTool.PAGES, "3-x"), 5));
        assertEquals(List.of(), new RemovePagesTool().previewPages(Map.of(RemovePagesTool.PAGES, "9"), 5));
    }

    @Test
    void splitPreviewsItsParts() {
        SplitTool split = new SplitTool();
        assertEquals(List.of(new Range(1, 2), new Range(3, 4), new Range(5, 5)),
                split.previewParts(Map.of(SplitTool.MODE, SplitTool.EVERY_N, SplitTool.PAGES_PER_FILE, 2.0), 5));
        assertEquals(List.of(new Range(1, 3), new Range(4, 6)),
                split.previewParts(Map.of(SplitTool.MODE, SplitTool.BY_RANGES, SplitTool.RANGES, "1-3, 4-"), 6));
        assertNull(split.previewParts(Map.of(SplitTool.MODE, SplitTool.BY_SIZE, SplitTool.MAX_MB, 2.0), 6));
    }

    @Test
    void mergeFollowsADraggedPageOrder() throws IOException {
        Path a = TestFiles.pdf(dir, "a.pdf", "A", 2);
        Path b = TestFiles.pdf(dir, "b.pdf", "B", 2);
        List<PageRef> order = List.of(new PageRef(b, 2), new PageRef(a, 1), new PageRef(b, 1), new PageRef(a, 2));

        FileResult result = new MergeTool().run(List.of(a, b), Map.of(MergeTool.PAGE_ORDER, order),
                new SaveTo(null, "{name}_{tool}", Settings.IfExists.ADD_NUMBER), new Progress()).get(0);

        String text = TestFiles.text(result.getOutput());
        int b2 = text.indexOf("B page 2"), a1 = text.indexOf("A page 1"), b1 = text.indexOf("B page 1"), a2 = text.indexOf("A page 2");
        assertTrue(b2 < a1 && a1 < b1 && b1 < a2, "pages should follow the dragged order");
        assertEquals(4, TestFiles.pageCount(result.getOutput()));
    }
}
