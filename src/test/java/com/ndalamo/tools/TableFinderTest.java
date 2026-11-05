package com.ndalamo.tools;

import com.ndalamo.tools.PageContent.Box;
import com.ndalamo.tools.PageContent.Rule;
import com.ndalamo.tools.TableFinder.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Table finding on hand-placed words, so each awkward layout can be tested on its own. */
class TableFinderTest {

    /** A word at (x, y) in 10-point text: about 5 points per letter, 7 points tall. */
    private static Box word(String text, float x, float y) {
        return new Box(text, x, y - 7, x + text.length() * 5, y);
    }

    /** A right-aligned word ending at x. */
    private static Box rightAligned(String text, float right, float y) {
        return new Box(text, right - text.length() * 5, y - 7, right, y);
    }

    /** Words of a sentence, one word-space apart. */
    private static List<Box> sentence(String text, float x, float y) {
        List<Box> words = new ArrayList<>();
        for (String w : text.split(" ")) {
            words.add(word(w, x, y));
            x += w.length() * 5 + 2.5f;
        }
        return words;
    }

    @Test
    void rightAlignedAmountsOfDifferentWidthsStayInOneColumn() {
        List<Box> words = new ArrayList<>();
        String[] amounts = {"5.00", "1 250.00", "18 500.00", "75.20"};
        for (int i = 0; i < amounts.length; i++) {
            words.addAll(sentence("Item " + (i + 1), 72, 100 + i * 14));
            words.addAll(sentence(amounts[i], 300 - amounts[i].length() * 5, 100 + i * 14));
        }

        List<Table> tables = TableFinder.find(words, List.of());

        assertEquals(1, tables.size());
        assertEquals(2, tables.get(0).columns());
        assertEquals("18 500.00", tables.get(0).rows().get(2).get(1));
    }

    @Test
    void aLongDescriptionInOneRowDoesntJoinTwoColumns() {
        List<Box> words = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            float y = 100 + i * 14;
            words.add(word("0" + (i + 1) + "/10", 72, y));
            String description = i == 3 ? "Monthly service fee and cash deposit charges" : "Groceries";
            words.addAll(sentence(description, 130, y));
            words.add(rightAligned("-" + (i + 1) + "0.00", 380, y));
        }

        List<Table> tables = TableFinder.find(words, List.of());

        assertEquals(1, tables.size());
        assertEquals(3, tables.get(0).columns(), "date, description, amount");
        assertEquals("-10.00", tables.get(0).rows().get(0).get(2));
    }

    @Test
    void textSpillingOverTheNextColumnStaysInItsCell() {
        // Spreadsheets let a long description run on over the next column; its last word sits above the amounts.
        List<Box> words = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            float y = 100 + i * 14;
            words.add(word("0" + (i + 1) + "/10", 72, y));
            words.addAll(sentence(i == 2 ? "Debit order insurance premium" : "Groceries", 130, y));
            words.add(rightAligned("-" + (i + 1) + "0.00", 320, y));     // "premium" ends near x 265, over this column
        }

        List<Table> tables = TableFinder.find(words, List.of());

        assertEquals("Debit order insurance premium", tables.get(0).rows().get(2).get(1));
        assertEquals("-30.00", tables.get(0).rows().get(2).get(2));
    }

    @Test
    void ordinaryParagraphsAreNotTables() {
        List<Box> words = new ArrayList<>();
        words.addAll(sentence("Thank you for banking with us. Your statement is attached below", 72, 100));
        words.addAll(sentence("and shows every payment made during the month of October.", 72, 114));
        words.addAll(sentence("Call us if anything looks wrong.", 72, 128));

        assertTrue(TableFinder.find(words, List.of()).isEmpty());
    }

    @Test
    void aBoxAroundOneParagraphIsNotATable() {
        List<Box> words = sentence("Important: pay before the 7th", 80, 110);
        List<Rule> box = List.of(
                new Rule(true, 95, 72, 400), new Rule(true, 120, 72, 400),
                new Rule(false, 72, 95, 120), new Rule(false, 400, 95, 120));

        assertTrue(TableFinder.ruledTables(words, box).isEmpty());
    }

    @Test
    void doubleBordersDontMakeEmptyRows() {
        // A ruled 2×2 table whose top border is drawn twice, 1 point apart.
        List<Rule> rules = List.of(
                new Rule(true, 99, 72, 272), new Rule(true, 100, 72, 272), new Rule(true, 120, 72, 272),
                new Rule(true, 140, 72, 272),
                new Rule(false, 72, 99, 140), new Rule(false, 172, 99, 140), new Rule(false, 272, 99, 140));
        List<Box> words = List.of(word("Name", 80, 115), word("Total", 180, 115), word("Thabo", 80, 135), word("42", 180, 135));

        List<Table> tables = TableFinder.ruledTables(words, rules);

        assertEquals(1, tables.size());
        assertEquals(List.of(List.of("Name", "Total"), List.of("Thabo", "42")), tables.get(0).rows());
    }

    @Test
    void twoTablesOnAPageComeOutTopToBottom() {
        List<Rule> rules = new ArrayList<>();
        List<Box> words = new ArrayList<>();
        for (float top : new float[]{300, 100}) {                     // the lower table is drawn first
            for (int r = 0; r <= 2; r++) rules.add(new Rule(true, top + r * 20, 72, 272));
            for (int c = 0; c <= 2; c++) rules.add(new Rule(false, 72 + c * 100, top, top + 40));
            words.add(word(top == 100 ? "First" : "Second", 80, top + 15));
            words.add(word("a", 180, top + 15));
            words.add(word("b", 80, top + 35));
            words.add(word("c", 180, top + 35));
        }

        List<Table> tables = TableFinder.ruledTables(words, rules);

        assertEquals(2, tables.size());
        assertEquals("First", tables.get(0).rows().get(0).get(0));
        assertEquals("Second", tables.get(1).rows().get(0).get(0));
    }
}
