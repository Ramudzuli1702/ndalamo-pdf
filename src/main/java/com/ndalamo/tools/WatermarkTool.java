package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.PageStamp;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Watermark: puts see-through text (e.g. "CONFIDENTIAL") or a picture (e.g. a logo)
 * on pages — diagonally across the page, centred, at the top or bottom, or tiled
 * all over. Text is sized to the page so it looks the same on A4 and Letter.
 */
public class WatermarkTool implements PdfTool {

    static final String KIND = "kind";
    static final String TEXT = "Text";
    static final String IMAGE = "Picture";
    static final String WORDS = "words";
    static final String PICTURE = "picture";

    static final String PLACE = "place";
    static final String DIAGONAL = "Diagonal";
    static final String CENTRE = "Centre";
    static final String TOP = "Top";
    static final String BOTTOM = "Bottom";
    static final String TILED = "Tiled";

    static final String OPACITY = "opacity";
    static final String COLOUR = "colour";
    static final String GREY = "Grey";
    static final String RED = "Red";
    static final String BLUE = "Blue";
    static final String PAGES = "pages";

    @Override public String id() { return "watermark"; }
    @Override public String name() { return "Watermark"; }
    @Override public String description() { return "Text or image"; }
    @Override public String category() { return "Edit & sign"; }
    @Override public String outputSuffix() { return "watermarked"; }
    @Override public Preview preview() { return Preview.STAMP; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(KIND, "Watermark", OptionField.Type.CHOICE, List.of(TEXT, IMAGE), TEXT),
                new OptionField(WORDS, "Text", OptionField.Type.TEXT, "CONFIDENTIAL").shownWhen(KIND, TEXT),
                new OptionField(PICTURE, "Picture", OptionField.Type.FILE, null).shownWhen(KIND, IMAGE),
                new OptionField(PLACE, "Where", OptionField.Type.CHOICE,
                        List.of(DIAGONAL, CENTRE, TOP, BOTTOM, TILED), DIAGONAL),
                new OptionField(OPACITY, "How see-through (opacity %)", OptionField.Type.NUMBER, 20).advanced(),
                new OptionField(COLOUR, "Colour", OptionField.Type.CHOICE, List.of(GREY, RED, BLUE), GREY).advanced(),
                new OptionField(PAGES, "Only these pages (empty = all)", OptionField.Type.TEXT, "")
                        .hint("e.g. 1-3").advanced());
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        if (TEXT.equals(options.get(KIND))) {
            String words = (String) options.get(WORDS);
            if (words == null || words.isBlank()) return "Type the watermark text, for example CONFIDENTIAL.";
        } else if (options.get(PICTURE) == null) {
            return "Choose a picture for the watermark.";
        }
        Double opacity = (Double) options.get(OPACITY);
        if (opacity == null || opacity < 1 || opacity > 100) return "Opacity should be between 1 and 100.";
        String pages = (String) options.get(PAGES);
        return pages == null || pages.isBlank() ? null : PageRanges.check(pages);
    }

    @Override
    public PageStamp previewStamp(Map<String, Object> options, int page, int pageCount) {
        if (checkOptions(options) != null || !onPage(options, page, pageCount)) return null;
        String place = (String) options.get(PLACE);
        String text = TEXT.equals(options.get(KIND)) ? ((String) options.get(WORDS)).strip() : "[picture]";
        return new PageStamp(text,
                TOP.equals(place) ? "top" : BOTTOM.equals(place) ? "bottom" : "middle", "centre",
                DIAGONAL.equals(place) ? 45 : TILED.equals(place) ? 30 : 0,
                (Double) options.get(OPACITY) / 100, textSize(place, text, 595, 842) / 595,   // as a share of an A4 page
                hex(colour(options)), TILED.equals(place));
    }

    @Override
    public String buttonLabel(int files) {
        return "Watermark " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Adding watermarks to " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return "Watermark added to " + Format.count(files, "file", "files");
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        boolean isText = TEXT.equals(options.get(KIND));
        String place = (String) options.get(PLACE);
        float opacity = ((Double) options.get(OPACITY)).floatValue() / 100;
        Color colour = colour(options);

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Adding a watermark to " + input.getFileName());
            progress.file(i, 0.1, "Working");
            try (PDDocument doc = PdfFiles.open(input)) {
                int pageCount = doc.getNumberOfPages();
                PDFont font = isText ? PageCanvas.font(doc, true, ((String) options.get(WORDS)).strip()) : null;
                PDImageXObject picture = isText ? null
                        : PDImageXObject.createFromFileByContent(((Path) options.get(PICTURE)).toFile(), doc);
                PDExtendedGraphicsState seeThrough = new PDExtendedGraphicsState();
                seeThrough.setNonStrokingAlphaConstant(opacity);
                seeThrough.setStrokingAlphaConstant(opacity);

                int marked = 0;
                for (int p = 1; p <= pageCount; p++) {
                    if (progress.isCancelled()) return results;
                    if (!onPage(options, p, pageCount)) continue;
                    marked++;
                    PageCanvas.drawOn(doc, doc.getPage(p - 1), (content, w, h) -> {
                        content.setGraphicsStateParameters(seeThrough);
                        if (isText) drawText(content, font, ((String) options.get(WORDS)).strip(), place, colour, w, h);
                        else drawPicture(content, picture, place, w, h);
                    });
                    progress.file(i, (double) p / pageCount * 0.9, "Adding watermark · " + Math.round(90.0 * p / pageCount) + "%");
                }
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pageCount);
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                        "Watermark on " + Format.count(marked, "page", "pages")));
                progress.file(i, 1, "Done");
            } catch (IllegalArgumentException e) {
                results.add(PdfFiles.failed(input, e.getMessage().replaceAll("\\.$", "")));
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    private static void drawText(PDPageContentStream content, PDFont font, String text, String place, Color colour,
                                 float w, float h) throws IOException {
        float size = textSize(place, text, w, h);
        float textWidth = PageCanvas.textWidth(font, text, size);
        float capHeight = size * 0.7f;
        content.setNonStrokingColor(colour);
        if (TILED.equals(place)) {
            // A grid of small slanted copies; rows are offset like bricks so it covers evenly.
            float stepX = textWidth + size * 2, stepY = size * 5;
            for (float y = -h; y < h * 2; y += stepY) {
                float offset = ((int) (y / stepY) % 2) * stepX / 2;
                for (float x = -w + offset; x < w * 2; x += stepX) {
                    writeAt(content, font, text, size, Math.toRadians(30), x, y);
                }
            }
            return;
        }
        double angle = DIAGONAL.equals(place) ? Math.atan2(h, w) : 0;     // corner to corner
        float cy = TOP.equals(place) ? h - 40 - capHeight / 2 : BOTTOM.equals(place) ? 40 + capHeight / 2 : h / 2;
        // Rotate around the text's own middle, so it stays centred whatever the angle.
        float startX = (float) (w / 2 - Math.cos(angle) * textWidth / 2 + Math.sin(angle) * capHeight / 2);
        float startY = (float) (cy - Math.sin(angle) * textWidth / 2 - Math.cos(angle) * capHeight / 2);
        writeAt(content, font, text, size, angle, startX, startY);
    }

    private static void writeAt(PDPageContentStream content, PDFont font, String text, float size, double angle,
                                float x, float y) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setTextMatrix(Matrix.getRotateInstance(angle, x, y));
        content.showText(text);
        content.endText();
    }

    private static void drawPicture(PDPageContentStream content, PDImageXObject picture, String place, float w, float h)
            throws IOException {
        float width = w * (TILED.equals(place) ? 0.2f : 0.5f);
        float height = width * picture.getHeight() / picture.getWidth();
        if (TILED.equals(place)) {
            for (float y = 20; y < h; y += height * 1.8f) {
                for (float x = 20; x < w; x += width * 1.5f) content.drawImage(picture, x, y, width, height);
            }
            return;
        }
        float y = TOP.equals(place) ? h - 40 - height : BOTTOM.equals(place) ? 40 : (h - height) / 2;
        content.drawImage(picture, (w - width) / 2, y, width, height);
    }

    /**
     * Font size that makes the text fill a sensible share of the page, whatever its length:
     * three-quarters of the diagonal for Diagonal, 60% of the width for Centre/Top/Bottom.
     */
    private static float textSize(String place, String text, float pageWidth, float pageHeight) {
        float room = DIAGONAL.equals(place)
                ? (float) Math.hypot(pageWidth, pageHeight) * 0.75f
                : pageWidth * (TILED.equals(place) ? 0.25f : 0.6f);
        // Bold capitals average about 0.65 of the font size in width.
        float size = room / Math.max(1, text.length() * 0.65f);
        return Math.max(10, Math.min(size, TILED.equals(place) ? 28 : 140));
    }

    private static boolean onPage(Map<String, Object> options, int page, int pageCount) {
        String pages = (String) options.get(PAGES);
        if (pages == null || pages.isBlank()) return true;
        // Read the ranges without the page count, so "1-3" on a 2-page file still marks pages 1-2
        // instead of failing; checkOptions has already caught real typing mistakes.
        return PageRanges.parse(pages, Integer.MAX_VALUE).stream()
                .anyMatch(r -> page >= r.from() && page <= r.to());
    }

    private static Color colour(Map<String, Object> options) {
        Object colour = options.get(COLOUR);
        if (RED.equals(colour)) return new Color(0xC2, 0x36, 0x1F);
        if (BLUE.equals(colour)) return new Color(0x2F, 0x5B, 0xEA);
        return new Color(0x80, 0x80, 0x80);
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
