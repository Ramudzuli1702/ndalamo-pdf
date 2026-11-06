package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;
import com.ndalamo.tools.WordLayout.ListMarker;
import com.ndalamo.tools.WordLayout.Paragraph;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Headings and lists for PDF to Word (SPEC §4.1 step 3).
 *
 * Headings: the size most of the document's text has is body text. Short paragraphs
 * in bigger text become Heading 1, 2 and 3 (biggest first). A short line that is all
 * bold, in body size, becomes the next heading level down ("1. Contributions").
 * The headings keep the PDF's look; the style only tells Word they're headings, so
 * the navigation pane and a table of contents work.
 *
 * Lists: list items next to each other form one list. Items further right are a
 * level deeper. Each list becomes a real Word list that starts at the PDF's first
 * number, so "3. 4. 5." stays "3. 4. 5." and can be renumbered in Word.
 */
final class WordStyles {

    /** Headings have at most this many words; longer text in a big size is a pull quote or a title page. */
    private static final int HEADING_WORDS = 20;

    private WordStyles() {}

    /** The size of body text: the size most of the document's letters have. */
    static float bodySize(List<List<TextWord>> pages) {
        List<TextWord> all = new ArrayList<>();
        pages.forEach(all::addAll);
        return all.isEmpty() ? 11 : WordLayout.mostCommonSize(all);
    }

    /** Heading level (1–3) for each paragraph that is a heading. */
    static Map<Paragraph, Integer> headings(List<Paragraph> paragraphs, float bodySize) {
        TreeSet<Float> biggerSizes = new TreeSet<>();
        for (Paragraph p : paragraphs) {
            if (couldBeHeading(p) && p.size() >= bodySize + 1.5f) biggerSizes.add(Math.round(p.size() * 2) / 2f);
        }
        List<Float> largestFirst = new ArrayList<>(biggerSizes.descendingSet());
        int boldLevel = Math.min(3, largestFirst.size() + 1);

        Map<Paragraph, Integer> levels = new IdentityHashMap<>();
        for (Paragraph p : paragraphs) {
            if (!couldBeHeading(p)) continue;
            float size = Math.round(p.size() * 2) / 2f;
            if (size >= bodySize + 1.5f) {
                levels.put(p, Math.min(3, largestFirst.indexOf(size) + 1));
            } else if (Math.abs(size - bodySize) < 1 && p.allBold() && p.words().size() + (p.marker() == null ? 0 : 1) <= 12
                    && !p.endsSentence()) {
                levels.put(p, boldLevel);
            }
        }
        return levels;
    }

    private static boolean couldBeHeading(Paragraph p) {
        return !p.words().isEmpty() && p.words().size() <= HEADING_WORDS
                && p.alignment() != WordLayout.Alignment.JUSTIFIED && (p.marker() == null || !p.marker().bullet());
    }

    /** Adds the Heading 1–3 styles. Named like Word's own ("heading 1") so Word treats them as its headings. */
    static void addHeadingStyles(XWPFDocument word) {
        XWPFStyles styles = word.createStyles();
        for (int level = 1; level <= 3; level++) {
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId("Heading" + level);
            style.setType(STStyleType.PARAGRAPH);
            style.addNewName().setVal("heading " + level);
            style.addNewUiPriority().setVal(BigInteger.valueOf(9));
            style.addNewQFormat();
            style.addNewPPr().addNewKeepNext();                           // a heading stays with the text after it
            style.getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
            styles.addStyle(new XWPFStyle(style));
        }
    }

    /** Where each list item goes: which Word list, and how deep. */
    record ListPlace(BigInteger numId, int level) {}

    /**
     * Turns runs of list items into Word lists. Items in a row (headings excluded) are
     * one list; their markers' left edges give the levels. Each list gets its own Word
     * numbering, starting at its first number.
     */
    static Map<Paragraph, ListPlace> lists(XWPFDocument word, List<Paragraph> paragraphs, Map<Paragraph, Integer> headings) {
        Map<Paragraph, ListPlace> places = new IdentityHashMap<>();
        XWPFNumbering numbering = null;
        int i = 0;
        while (i < paragraphs.size()) {
            if (!isListItem(paragraphs.get(i), headings)) {
                i++;
                continue;
            }
            List<Paragraph> list = new ArrayList<>();
            while (i < paragraphs.size() && isListItem(paragraphs.get(i), headings)) list.add(paragraphs.get(i++));

            Map<Paragraph, Integer> levelOf = levelsOf(list);
            Map<Integer, ListMarker> firstAtLevel = new HashMap<>();
            for (Paragraph p : list) firstAtLevel.putIfAbsent(levelOf.get(p), p.marker());

            if (numbering == null) numbering = word.createNumbering();
            BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum(firstAtLevel, numbering)));
            BigInteger numId = numbering.addNum(abstractId);
            ListMarker top = firstAtLevel.get(levelOf.get(list.get(0)));
            if (!top.bullet() && top.number() > 1) {
                // "3. 4. 5." continues an earlier list in the PDF: start at 3, not 1.
                CTNumLvl override = numbering.getNum(numId).getCTNum().addNewLvlOverride();
                override.setIlvl(BigInteger.valueOf(levelOf.get(list.get(0))));
                override.addNewStartOverride().setVal(BigInteger.valueOf(top.number()));
            }
            for (Paragraph p : list) places.put(p, new ListPlace(numId, levelOf.get(p)));
        }
        return places;
    }

    /** How deep each list item is (0 = top level), for writers that do their own numbering, like Markdown. */
    static Map<Paragraph, Integer> listLevels(List<Paragraph> paragraphs, Map<Paragraph, Integer> headings) {
        Map<Paragraph, Integer> levels = new IdentityHashMap<>();
        int i = 0;
        while (i < paragraphs.size()) {
            if (!isListItem(paragraphs.get(i), headings)) {
                i++;
                continue;
            }
            List<Paragraph> list = new ArrayList<>();
            while (i < paragraphs.size() && isListItem(paragraphs.get(i), headings)) list.add(paragraphs.get(i++));
            levels.putAll(levelsOf(list));
        }
        return levels;
    }

    /** Levels within one list, from where the markers start, left to right; markers within 4 points share a level. */
    private static Map<Paragraph, Integer> levelsOf(List<Paragraph> list) {
        List<Float> edges = new ArrayList<>();
        for (Paragraph p : list) {
            float x = p.marker().markerLeft();
            if (edges.stream().noneMatch(e -> Math.abs(e - x) < 4)) edges.add(x);
        }
        edges.sort(null);
        Map<Paragraph, Integer> levelOf = new IdentityHashMap<>();
        for (Paragraph p : list) {
            int level = 0;
            for (int e = 0; e < edges.size(); e++) if (Math.abs(edges.get(e) - p.marker().markerLeft()) < 4) level = e;
            levelOf.put(p, Math.min(level, 8));
        }
        return levelOf;
    }

    private static boolean isListItem(Paragraph p, Map<Paragraph, Integer> headings) {
        return p.marker() != null && !headings.containsKey(p);
    }

    /** A Word list definition with nine levels; each level looks like the PDF's first marker at that level. */
    private static CTAbstractNum abstractNum(Map<Integer, ListMarker> firstAtLevel, XWPFNumbering numbering) {
        CTAbstractNum definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(BigInteger.valueOf(numbering.getAbstractNums().size() + 1L));
        String[] defaultBullets = {"•", "◦", "▪"};
        for (int level = 0; level < 9; level++) {
            ListMarker marker = firstAtLevel.get(level);
            CTLvl lvl = definition.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(level));
            lvl.addNewStart().setVal(BigInteger.ONE);
            boolean bullet = marker == null ? firstAtLevel.values().stream().allMatch(ListMarker::bullet) : marker.bullet();
            if (bullet) {
                lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
                // Word's private-use Symbol bullets don't show in other fonts, so write a plain bullet instead.
                String symbol = marker == null || marker.pattern().charAt(0) >= '' ? defaultBullets[level % 3] : marker.pattern();
                lvl.addNewLvlText().setVal(symbol);
            } else {
                String format = marker == null ? "decimal" : marker.format();
                lvl.addNewNumFmt().setVal(STNumberFormat.Enum.forString(format));
                String pattern = marker == null ? "%1." : marker.pattern();
                lvl.addNewLvlText().setVal(pattern.replace("%1", "%" + (level + 1)));
            }
            lvl.addNewPPr().addNewInd().setLeft(BigInteger.valueOf(720L * (level + 1)));
            lvl.getPPr().getInd().setHanging(BigInteger.valueOf(360));
        }
        return definition;
    }
}
