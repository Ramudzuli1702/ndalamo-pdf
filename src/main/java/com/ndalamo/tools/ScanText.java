package com.ndalamo.tools;

import com.ndalamo.tools.PageText.TextWord;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a scanned page with OCR and turns what Tesseract finds into the same kind of
 * words PageText gives for real text, so PDF to Word can lay them out the same way
 * (SPEC §4.1 step 7). Tesseract only gives each word's box, so the rest is estimated:
 * - text size: from each word's height and which letters it has (a word with g or y
 *   reaches lower than "Invoice", which reaches higher than "use"), the typical value
 *   on each line; lines close to the page's usual
 *   size are all given that size, so body text doesn't wobble between sizes, while
 *   clearly bigger lines stay bigger and can become headings;
 * - baseline: the bottom most words on the line share (letters like g and y dip lower);
 * - font: plain Arial, not bold or italic; a scan doesn't say.
 */
final class ScanText {

    private static final int DPI = 300;

    private ScanText() {}

    static List<TextWord> words(OcrEngine engine, PDFRenderer renderer, int pageIndex) throws IOException {
        float toPoints = 72f / DPI;
        List<float[]> boxes = new ArrayList<>();             // left, top, right, bottom in points
        List<String> texts = new ArrayList<>();
        for (net.sourceforge.tess4j.Word word : engine.words(renderer.renderImageWithDPI(pageIndex, DPI, ImageType.GRAY))) {
            String text = word.getText().strip();
            if (text.isEmpty()) continue;
            Rectangle b = word.getBoundingBox();
            boxes.add(new float[]{b.x * toPoints, b.y * toPoints, (b.x + b.width) * toPoints, (b.y + b.height) * toPoints});
            texts.add(text);
        }
        if (boxes.isEmpty()) return List.of();

        // Group into lines: words whose middles are within each other's height.
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> (boxes.get(i)[1] + boxes.get(i)[3]) / 2));
        List<List<Integer>> lines = new ArrayList<>();
        float lineBottom = -1;
        for (int i : order) {
            float middle = (boxes.get(i)[1] + boxes.get(i)[3]) / 2;
            if (lines.isEmpty() || middle > lineBottom) {
                lines.add(new ArrayList<>());
                lineBottom = boxes.get(i)[3];
            } else {
                lineBottom = Math.max(lineBottom, boxes.get(i)[3]);
            }
            lines.get(lines.size() - 1).add(i);
        }

        // Each line's size: the middle of its words' estimates. The page's usual size: the middle line size.
        float[] lineSizes = new float[lines.size()];
        for (int l = 0; l < lines.size(); l++) {
            float[] estimates = new float[lines.get(l).size()];
            for (int k = 0; k < estimates.length; k++) {
                int i = lines.get(l).get(k);
                estimates[k] = (boxes.get(i)[3] - boxes.get(i)[1]) / shareOfSize(texts.get(i));
            }
            Arrays.sort(estimates);
            lineSizes[l] = estimates[estimates.length / 2];
        }
        float[] sorted = lineSizes.clone();
        Arrays.sort(sorted);
        float usual = sorted[sorted.length / 2];

        List<TextWord> words = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            float size = lineSizes[l] > usual * 0.75f && lineSizes[l] < usual * 1.3f ? usual : lineSizes[l];
            size = Math.max(6, Math.round(size * 2) / 2f);                   // to the nearest half point, like Word
            float[] bottoms = new float[lines.get(l).size()];
            for (int k = 0; k < bottoms.length; k++) bottoms[k] = boxes.get(lines.get(l).get(k))[3];
            Arrays.sort(bottoms);
            float baseline = bottoms[bottoms.length / 2];
            for (int i : lines.get(l)) {
                float[] b = boxes.get(i);
                words.add(new TextWord(texts.get(i), b[0], b[2], baseline, size, "Arial", false, false, 0, false));
            }
        }
        return words;
    }

    /**
     * How much of the font size a word's box covers, judging by its letters: from the
     * top of tall letters to the bottom of letters like g (about 0.93), to the baseline
     * only (capitals, digits, b d f h k l t: about 0.72), or just small letters (0.52).
     */
    private static float shareOfSize(String text) {
        boolean dips = text.matches(".*[gjpqyQ,;()\\[\\]].*");
        boolean tall = text.matches(".*[A-Z0-9bdfhklt'\"()\\[\\]/?!].*");
        if (dips && tall) return 0.93f;
        if (tall) return 0.72f;
        if (dips) return 0.73f;
        return 0.52f;
    }
}
