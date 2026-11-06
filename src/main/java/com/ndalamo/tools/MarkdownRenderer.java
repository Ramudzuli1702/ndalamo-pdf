package com.ndalamo.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.util.Matrix;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Markdown document (already read by commonmark) onto PDF pages with PDFBox,
 * for Markdown to PDF. A small layout engine: text is broken into lines that fit,
 * and a new page starts when a page is full.
 *
 * Fonts: IBM Plex Sans (Noto Sans for letters Plex lacks, decision 006); italics are
 * Plex slanted, as no italic font is bundled; code uses Courier, which every PDF
 * reader has built in. Pictures are drawn only from files on this PC; a picture on
 * the web shows its description instead, because Ndalamo never goes online.
 */
final class MarkdownRenderer {

    private static final float BODY = 10.5f, LEADING = 1.45f;
    private static final float[] HEADING_SIZES = {22, 17, 14, 12, 11, 10.5f};
    private static final Color TEXT = new Color(0x16181D), MUTED = new Color(0x5B616E), LINK = new Color(0x2F5BEA);
    private static final Color RULE = new Color(0xD5D8DF), SHADE = new Color(0xF4F5F7);
    /** How far italics lean: a slanted upright font, since no italic font is bundled. */
    private static final float SLANT = 0.2f;

    /** How a piece of text looks. */
    private record Look(boolean bold, boolean italic, boolean code, boolean strike, String link, Color colour) {
        Look with(boolean b, boolean i, boolean c, boolean s, String l) {
            return new Look(bold || b, italic || i, code || c, strike || s, l != null ? l : link, colour);
        }
    }

    /** A word (or a space-free piece of one) ready to place on a line. */
    private record Piece(String text, Look look, boolean spaceBefore) {}

    private final PDDocument doc;
    private final Path folder;                 // the Markdown file's folder, for pictures given by relative path
    private final PDRectangle pageSize;
    private final float margin = 64;
    private final PDFont plex, plexBold, noto, notoBold;
    private final PDFont courier = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private final List<Float> quoteBars = new ArrayList<>();   // x of each quote bar currently open

    private PDPage page;
    private PDPageContentStream out;
    private float y;                           // from the top of the page
    private int missingPictures;

    MarkdownRenderer(PDDocument doc, Path folder, PDRectangle pageSize) throws IOException {
        this.doc = doc;
        this.folder = folder;
        this.pageSize = pageSize;
        plex = PageCanvas.load(doc, "/fonts/IBMPlexSans-Regular.ttf");
        plexBold = PageCanvas.load(doc, "/fonts/IBMPlexSans-Bold.ttf");
        noto = PageCanvas.load(doc, "/fonts/NotoSans-Regular.ttf");
        notoBold = PageCanvas.load(doc, "/fonts/NotoSans-Bold.ttf");
    }

    /** Pictures that couldn't be drawn: on the web, missing, or in a format Java can't read. */
    int missingPictures() {
        return missingPictures;
    }

    void render(Node document) throws IOException {
        newPage();
        blocks(document, margin, contentWidth(), TEXT);
        out.close();
    }

    /** Adds the page number at the bottom of every page, once all pages exist. */
    void numberPages() throws IOException {
        int n = 0;
        for (PDPage p : doc.getPages()) {
            n++;
            try (PDPageContentStream c = new PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true)) {
                String number = String.valueOf(n);
                float width = PageCanvas.textWidth(plex, number, 9);
                c.beginText();
                c.setFont(plex, 9);
                c.setNonStrokingColor(MUTED);
                c.newLineAtOffset((pageSize.getWidth() - width) / 2, margin / 2);
                c.showText(number);
                c.endText();
            }
        }
    }

    // ── Blocks ───────────────────────────────────────────────────────────

    private void blocks(Node parent, float x, float width, Color colour) throws IOException {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) block(node, x, width, colour);
    }

    private void block(Node node, float x, float width, Color colour) throws IOException {
        if (node instanceof Heading h) {
            float size = HEADING_SIZES[Math.min(h.getLevel(), 6) - 1];
            space(h.getLevel() <= 2 ? size * 0.9f : size * 0.7f);
            needRoom(size * LEADING * 3);                     // keep a heading with the text after it
            text(inline(h, new Look(true, false, false, false, null, colour)), x, width, size);
            space(size * 0.35f);
        } else if (node instanceof Paragraph p) {
            paragraph(p, x, width, colour);
            if (!(p.getParent() instanceof ListItem li && isTight(li))) space(BODY * 0.7f);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            list(node, x, width, colour);
            if (!(node.getParent() instanceof ListItem)) space(BODY * 0.5f);
        } else if (node instanceof BlockQuote) {
            space(BODY * 0.2f);
            quoteBars.add(x + 2);
            blocks(node, x + 16, width - 16, MUTED);
            quoteBars.remove(quoteBars.size() - 1);
            space(BODY * 0.3f);
        } else if (node instanceof FencedCodeBlock code) {
            codeBlock(code.getLiteral(), x, width);
        } else if (node instanceof IndentedCodeBlock code) {
            codeBlock(code.getLiteral(), x, width);
        } else if (node instanceof ThematicBreak) {
            space(BODY * 0.6f);
            needRoom(4);
            line(x, y, x + width, y, RULE, 0.8f);
            space(BODY * 0.9f);
        } else if (node instanceof TableBlock table) {
            table(table, x, width);
            space(BODY * 0.8f);
        } else if (node instanceof HtmlBlock html) {
            // Raw HTML can't be drawn; keep its words so nothing is lost.
            String words = html.getLiteral().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
            if (!words.isEmpty()) {
                text(pieces(words, new Look(false, false, false, false, null, colour), false), x, width, BODY);
                space(BODY * 0.7f);
            }
        } else {
            blocks(node, x, width, colour);                   // anything else: draw what's inside it
        }
    }

    private void paragraph(Paragraph p, float x, float width, Color colour) throws IOException {
        // Pictures go on lines of their own; the words around them stay a paragraph.
        List<Piece> words = new ArrayList<>();
        for (Node child = p.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Image image) {
                if (!words.isEmpty()) {
                    text(words, x, width, BODY);
                    words = new ArrayList<>();
                }
                picture(image, x, width);
            } else {
                inline(child, new Look(false, false, false, false, null, colour), words);
            }
        }
        if (!words.isEmpty()) text(words, x, width, BODY);
    }

    private static boolean isTight(ListItem item) {
        return item.getParent() instanceof BulletList b ? b.isTight() : item.getParent() instanceof OrderedList o && o.isTight();
    }

    private void list(Node list, float x, float width, Color colour) throws IOException {
        int number = list instanceof OrderedList o && o.getMarkerStartNumber() != null ? o.getMarkerStartNumber() : 1;
        String delimiter = list instanceof OrderedList o && o.getMarkerDelimiter() != null ? o.getMarkerDelimiter() : ".";
        float indent = list instanceof OrderedList ? 22 : 16;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            needRoom(BODY * LEADING);
            float markerBaseline = y + BODY * 1.0f;
            TaskListItemMarker task = taskMarker(item);
            if (task != null) {
                checkbox(x + 2, markerBaseline, task.isChecked());
            } else {
                String marker = list instanceof OrderedList ? number + delimiter : "•";
                float markerWidth = PageCanvas.textWidth(plex, marker, BODY);
                drawText(marker, plex, BODY, x + indent - markerWidth - 5, markerBaseline, false, colour);
            }
            number++;
            blocks(item, x + indent, width - indent, colour);
        }
    }

    private static TaskListItemMarker taskMarker(Node item) {
        Node first = item.getFirstChild();
        if (first instanceof TaskListItemMarker m) return m;
        if (first != null && first.getFirstChild() instanceof TaskListItemMarker m) return m;
        return null;
    }

    /** A tick box drawn with lines, since the bundled fonts have no ☐ or ☑. */
    private void checkbox(float x, float baseline, boolean checked) throws IOException {
        float size = BODY * 0.8f, bottom = pageSize.getHeight() - baseline;
        out.setStrokingColor(MUTED);
        out.setLineWidth(0.8f);
        out.addRect(x, bottom, size, size);
        out.stroke();
        if (checked) {
            out.setStrokingColor(LINK);
            out.setLineWidth(1.4f);
            out.moveTo(x + size * 0.2f, bottom + size * 0.5f);
            out.lineTo(x + size * 0.42f, bottom + size * 0.22f);
            out.lineTo(x + size * 0.85f, bottom + size * 0.8f);
            out.stroke();
        }
    }

    private void codeBlock(String literal, float x, float width) throws IOException {
        float size = BODY * 0.88f, leading = size * 1.45f, pad = 7;
        float charWidth = courier.getStringWidth("m") / 1000 * size;
        int perLine = Math.max(10, (int) ((width - 2 * pad) / charWidth));
        List<String> lines = new ArrayList<>();
        for (String line : literal.replace("\t", "    ").stripTrailing().split("\n", -1)) {
            if (line.isEmpty()) lines.add("");
            for (int i = 0; i < line.length(); i += perLine) lines.add(line.substring(i, Math.min(line.length(), i + perLine)));
        }
        space(BODY * 0.2f);
        shade(x, y, width, pad);
        y += pad;
        for (String line : lines) {
            if (needRoom(leading)) shade(x, y - pad, width, pad);
            shade(x, y, width, leading);
            drawText(line, codeFont(line), size, x + pad, y + leading * 0.78f, false, TEXT);
            y += leading;
        }
        shade(x, y, width, pad);
        y += pad;
        space(BODY * 0.8f);
    }

    private void shade(float x, float top, float width, float height) throws IOException {
        out.setNonStrokingColor(SHADE);
        out.addRect(x, pageSize.getHeight() - top - height, width, height);
        out.fill();
    }

    private void picture(Image image, float x, float width) throws IOException {
        String destination = image.getDestination();
        String alt = textOf(image);
        BufferedImage picture = null;
        if (destination != null && !destination.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || (destination != null && destination.startsWith("file:"))) {
            try {
                String local = destination.startsWith("file:") ? Path.of(java.net.URI.create(destination)).toString()
                        : java.net.URLDecoder.decode(destination, java.nio.charset.StandardCharsets.UTF_8);
                Path file = folder.resolve(local).normalize();
                if (Files.isRegularFile(file)) picture = ImageIO.read(file.toFile());
            } catch (IllegalArgumentException | IOException e) {
                picture = null;
            }
        }
        if (picture == null) {
            missingPictures++;
            String note = "[Picture" + (alt.isBlank() ? "" : ": " + alt) + " — "
                    + (destination != null && destination.matches("(?i)^https?://.*") ? "on the web, not downloaded" : "not found") + "]";
            text(pieces(note, new Look(false, true, false, false, null, MUTED), false), x, width, BODY);
            return;
        }
        // At 96 pixels per inch, like a browser shows it, but never wider than the column or taller than a page.
        float w = picture.getWidth() * 0.75f, h = picture.getHeight() * 0.75f;
        float maxHeight = pageSize.getHeight() - 2 * margin - 20;
        float scale = Math.min(1, Math.min(width / w, maxHeight / h));
        w *= scale;
        h *= scale;
        space(BODY * 0.3f);
        needRoom(h);
        PDImageXObject xobject = LosslessFactory.createFromImage(doc, picture);
        out.drawImage(xobject, x, pageSize.getHeight() - y - h, w, h);
        y += h;
        space(BODY * 0.5f);
    }

    // ── Tables ───────────────────────────────────────────────────────────

    private void table(TableBlock table, float x, float width) throws IOException {
        List<List<List<Piece>>> rows = new ArrayList<>();
        List<TableCell.Alignment> alignments = new ArrayList<>();
        int headerRows = 0;
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                List<List<Piece>> cells = new ArrayList<>();
                int c = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext(), c++) {
                    boolean header = section instanceof TableHead;
                    cells.add(inline(cell, new Look(header, false, false, false, null, TEXT)));
                    if (alignments.size() <= c) alignments.add(((TableCell) cell).getAlignment());
                }
                rows.add(cells);
                if (section instanceof TableHead) headerRows++;
            }
        }
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        float pad = 5, size = BODY * 0.95f;

        // Column widths: as wide as their text wants, shrunk to fit if the table is too wide.
        float[] natural = new float[columns], longestWord = new float[columns];
        for (List<List<Piece>> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                float lineWidth = 0;
                for (Piece p : row.get(c)) {
                    float w = width(p, size);
                    lineWidth += w + (p.spaceBefore() ? spaceWidth(size) : 0);
                    longestWord[c] = Math.max(longestWord[c], w);
                }
                natural[c] = Math.max(natural[c], lineWidth);
            }
        }
        float[] widths = new float[columns];
        float total = 0;
        for (int c = 0; c < columns; c++) total += natural[c] + 2 * pad;
        for (int c = 0; c < columns; c++) {
            widths[c] = total <= width ? natural[c] + 2 * pad
                    : Math.max(longestWord[c] + 2 * pad, (natural[c] + 2 * pad) * width / total);
        }
        float sum = 0;
        for (float w : widths) sum += w;
        if (sum > width) for (int c = 0; c < columns; c++) widths[c] *= width / sum;

        space(BODY * 0.2f);
        for (int r = 0; r < rows.size(); r++) {
            float height = rowHeight(rows.get(r), widths, pad, size);
            if (y + height > pageSize.getHeight() - margin && r > headerRows) {
                newPage();
                for (int h = 0; h < headerRows; h++) drawRow(rows.get(h), widths, alignments, x, pad, size, true);  // repeat the header
            }
            drawRow(rows.get(r), widths, alignments, x, pad, size, r < headerRows);
        }
    }

    private float rowHeight(List<List<Piece>> row, float[] widths, float pad, float size) throws IOException {
        int lines = 1;
        for (int c = 0; c < row.size(); c++) lines = Math.max(lines, breakLines(row.get(c), widths[c] - 2 * pad, size).size());
        return lines * size * LEADING + 2 * pad;
    }

    private void drawRow(List<List<Piece>> row, float[] widths, List<TableCell.Alignment> alignments, float x, float pad,
                         float size, boolean header) throws IOException {
        float height = rowHeight(row, widths, pad, size), top = y;
        float total = 0;
        for (float w : widths) total += w;
        if (header) {
            out.setNonStrokingColor(SHADE);
            out.addRect(x, pageSize.getHeight() - top - height, total, height);
            out.fill();
        }
        float cellX = x;
        for (int c = 0; c < widths.length; c++) {
            if (c < row.size()) {
                List<List<Piece>> lines = breakLines(row.get(c), widths[c] - 2 * pad, size);
                float lineTop = top + pad;
                TableCell.Alignment alignment = c < alignments.size() ? alignments.get(c) : null;
                for (List<Piece> line : lines) {
                    float lineWidth = lineWidth(line, size);
                    float start = alignment == TableCell.Alignment.RIGHT ? cellX + widths[c] - pad - lineWidth
                            : alignment == TableCell.Alignment.CENTER ? cellX + (widths[c] - lineWidth) / 2 : cellX + pad;
                    drawLine(line, start, lineTop + size * LEADING * 0.75f, size);
                    lineTop += size * LEADING;
                }
            }
            cellX += widths[c];
        }
        // Borders: around the row and between its cells.
        float bottom = pageSize.getHeight() - top - height;
        out.setStrokingColor(RULE);
        out.setLineWidth(0.6f);
        out.addRect(x, bottom, total, height);
        float edge = x;
        for (int c = 0; c < widths.length - 1; c++) {
            edge += widths[c];
            out.moveTo(edge, bottom);
            out.lineTo(edge, bottom + height);
        }
        out.stroke();
        y = top + height;
    }

    // ── Inline text ──────────────────────────────────────────────────────

    private List<Piece> inline(Node parent, Look look) {
        List<Piece> pieces = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) inline(child, look, pieces);
        return pieces;
    }

    private void inline(Node node, Look look, List<Piece> pieces) {
        if (node instanceof Text t) {
            pieces.addAll(pieces(t.getLiteral(), look, startsWithSpace(t.getLiteral()) || !pieces.isEmpty() && endsWithSpace(pieces)));
        } else if (node instanceof Code c) {
            pieces.addAll(pieces(c.getLiteral(), look.with(false, false, true, false, null), !pieces.isEmpty()));
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            pieces.add(new Piece(node instanceof HardLineBreak ? "\n" : " ", look, false));
        } else if (node instanceof StrongEmphasis) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) inline(c, look.with(true, false, false, false, null), pieces);
        } else if (node instanceof Emphasis) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) inline(c, look.with(false, true, false, false, null), pieces);
        } else if (node instanceof Strikethrough) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) inline(c, look.with(false, false, false, true, null), pieces);
        } else if (node instanceof Link link) {
            Look linked = new Look(look.bold(), look.italic(), look.code(), look.strike(), link.getDestination(), LINK);
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) inline(c, linked, pieces);
        } else if (node instanceof Image image) {
            String alt = textOf(image);
            if (!alt.isBlank()) pieces.addAll(pieces("[" + alt + "]", look.with(false, true, false, false, null), true));
        } else if (node instanceof HtmlInline || node instanceof TaskListItemMarker) {
            // HTML tags can't be drawn; a task box is drawn as the list marker.
        } else {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) inline(c, look, pieces);
        }
    }

    private static boolean startsWithSpace(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(0));
    }

    private static boolean endsWithSpace(List<Piece> pieces) {
        return pieces.get(pieces.size() - 1).text().equals(" ");
    }

    /** Splits text into words; each word remembers whether a space came before it. */
    private static List<Piece> pieces(String text, Look look, boolean spaceFirst) {
        List<Piece> pieces = new ArrayList<>();
        boolean space = spaceFirst;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length();
            char ch = end ? ' ' : text.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!word.isEmpty()) {
                    pieces.add(new Piece(word.toString(), look, space));
                    word.setLength(0);
                }
                if (!end) space = true;
            } else {
                if (word.isEmpty() && !pieces.isEmpty() && !Character.isWhitespace(text.charAt(Math.max(0, i - 1))) && i > 0) space = false;
                word.append(ch);
            }
        }
        if (text.isEmpty() || (!pieces.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1)))) {
            pieces.add(new Piece(" ", look, false));          // remembered so the next piece starts after a space
        }
        return pieces;
    }

    /** Breaks pieces into lines that fit, draws them, and moves down the page. */
    private void text(List<Piece> pieces, float x, float width, float size) throws IOException {
        for (List<Piece> line : breakLines(pieces, width, size)) {
            needRoom(size * LEADING);
            drawLine(line, x, y + size * LEADING * 0.75f, size);
            y += size * LEADING;
        }
    }

    private List<List<Piece>> breakLines(List<Piece> pieces, float width, float size) throws IOException {
        List<List<Piece>> lines = new ArrayList<>();
        List<Piece> line = new ArrayList<>();
        float used = 0;
        for (Piece p : pieces) {
            if (p.text().equals(" ")) continue;
            if (p.text().equals("\n")) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
                continue;
            }
            float w = width(p, size), space = line.isEmpty() || !p.spaceBefore() ? 0 : spaceWidth(size);
            if (!line.isEmpty() && used + space + w > width) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
                space = 0;
            }
            // A word wider than the whole line (a long web address) is broken where it must be.
            Piece piece = p;
            while (width(piece, size) > width && piece.text().length() > 1) {
                int fit = piece.text().length() - 1;
                while (fit > 1 && width(new Piece(piece.text().substring(0, fit), piece.look(), false), size) > width - used) fit--;
                line.add(new Piece(piece.text().substring(0, fit), piece.look(), !line.isEmpty() && piece.spaceBefore()));
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
                piece = new Piece(piece.text().substring(fit), piece.look(), false);
            }
            line.add(line.isEmpty() ? new Piece(piece.text(), piece.look(), false) : piece);
            used += space + width(piece, size);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line);
        return lines;
    }

    private float lineWidth(List<Piece> line, float size) throws IOException {
        float w = 0;
        for (Piece p : line) w += width(p, size) + (p.spaceBefore() ? spaceWidth(size) : 0);
        return w;
    }

    private void drawLine(List<Piece> line, float x, float baseline, float size) throws IOException {
        for (float bar : quoteBars) line(bar, baseline - size * LEADING * 0.75f, bar, baseline + size * LEADING * 0.25f, RULE, 2.5f);
        float cursor = x;
        String previousLink = null;
        for (Piece p : line) {
            if (p.spaceBefore()) {
                // Within one link the underline carries on under the space, so the link reads as one.
                if (p.look().link() != null && p.look().link().equals(previousLink)) {
                    line(cursor, baseline + 1.5f, cursor + spaceWidth(size), baseline + 1.5f, LINK, 0.6f);
                }
                cursor += spaceWidth(size);
            }
            previousLink = p.look().link();
            float w = width(p, size);
            float s = p.look().code() ? size * 0.9f : size;
            if (p.look().code()) {
                out.setNonStrokingColor(SHADE);
                out.addRect(cursor - 1.5f, pageSize.getHeight() - baseline - s * 0.28f, w + 3, s * 1.18f);
                out.fill();
            }
            drawText(p.text(), font(p), s, cursor, baseline, p.look().italic() && !p.look().code(), p.look().colour());
            if (p.look().link() != null) {
                line(cursor, baseline + 1.5f, cursor + w, baseline + 1.5f, LINK, 0.6f);
                linkArea(p.look().link(), cursor, baseline, w, size);
            }
            if (p.look().strike()) line(cursor, baseline - size * 0.3f, cursor + w, baseline - size * 0.3f, p.look().colour(), 0.7f);
            cursor += w;
        }
    }

    /** A clickable area over linked text. Clicking opens the link in the reader's browser; Ndalamo itself never goes online. */
    private void linkArea(String destination, float x, float baseline, float width, float size) throws IOException {
        if (!destination.matches("(?i)^(https?|mailto):.*")) return;     // #anchors and local files: just coloured text
        PDAnnotationLink link = new PDAnnotationLink();
        PDActionURI action = new PDActionURI();
        action.setURI(destination);
        link.setAction(action);
        PDBorderStyleDictionary noBorder = new PDBorderStyleDictionary();
        noBorder.setWidth(0);
        link.setBorderStyle(noBorder);
        float bottom = pageSize.getHeight() - baseline - size * 0.25f;
        link.setRectangle(new PDRectangle(x, bottom, width, size * 1.2f));
        page.getAnnotations().add(link);
    }

    private void drawText(String text, PDFont font, float size, float x, float baseline, boolean italic, Color colour)
            throws IOException {
        if (text.isEmpty()) return;
        out.beginText();
        out.setFont(font, size);
        out.setNonStrokingColor(colour);
        float bottom = pageSize.getHeight() - baseline;
        out.setTextMatrix(italic ? new Matrix(1, 0, SLANT, 1, x, bottom) : Matrix.getTranslateInstance(x, bottom));
        out.showText(writable(font, text));
        out.endText();
    }

    private void line(float x1, float top1, float x2, float top2, Color colour, float thickness) throws IOException {
        out.setStrokingColor(colour);
        out.setLineWidth(thickness);
        out.moveTo(x1, pageSize.getHeight() - top1);
        out.lineTo(x2, pageSize.getHeight() - top2);
        out.stroke();
    }

    // ── Fonts and measuring ──────────────────────────────────────────────

    private PDFont font(Piece p) {
        if (p.look().code()) return codeFont(p.text());
        PDFont first = p.look().bold() ? plexBold : plex;
        return canWrite(first, p.text()) ? first : p.look().bold() ? notoBold : noto;
    }

    /** Courier for code, unless the text has letters only a full font has (e.g. Tshivenda ṱ). */
    private PDFont codeFont(String text) {
        return canWrite(courier, text) ? courier : canWrite(plex, text) ? plex : noto;
    }

    private static boolean canWrite(PDFont font, String text) {
        try {
            font.encode(text);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The text with any letter the font can't draw replaced by "?", so one odd symbol can't stop the file. */
    private static String writable(PDFont font, String text) {
        if (canWrite(font, text)) return text;
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            out.append(canWrite(font, ch) ? ch : "?");
        });
        return out.toString();
    }

    private float width(Piece p, float size) throws IOException {
        float s = p.look().code() ? size * 0.9f : size;
        PDFont font = font(p);
        return font.getStringWidth(writable(font, p.text())) / 1000 * s;
    }

    private float spaceWidth(float size) throws IOException {
        return plex.getStringWidth(" ") / 1000 * size;
    }

    private static String textOf(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) out.append(t.getLiteral());
            else out.append(textOf(c));
        }
        return out.toString();
    }

    // ── Pages ────────────────────────────────────────────────────────────

    private float contentWidth() {
        return pageSize.getWidth() - 2 * margin;
    }

    private void space(float points) {
        if (y > margin + 0.5f) y += points;        // no extra space at the top of a page
    }

    /** Starts a new page if there isn't this much room left. True if it did. */
    private boolean needRoom(float height) throws IOException {
        if (y + height <= pageSize.getHeight() - margin) return false;
        newPage();
        return true;
    }

    private void newPage() throws IOException {
        if (out != null) out.close();
        page = new PDPage(pageSize);
        doc.addPage(page);
        out = new PDPageContentStream(doc, page);
        y = margin;
    }
}
