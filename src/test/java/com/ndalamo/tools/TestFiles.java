package com.ndalamo.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Makes small PDFs and images for tool tests, so no test files need to be checked in. */
final class TestFiles {

    private TestFiles() {}

    /** A PDF whose pages read "<label> page 1", "<label> page 2", ... so tests can check page order. */
    static Path pdf(Path folder, String name, String label, int pages) throws IOException {
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream text = new PDPageContentStream(doc, page)) {
                    text.beginText();
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    text.newLineAtOffset(72, 700);
                    text.showText(label + " page " + i);
                    text.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A one-page PDF that can only be opened with a password. */
    static Path lockedPdf(Path folder, String name) throws IOException {
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            doc.save(file.toFile());
        }
        return file;
    }

    /** A PDF that opens without a password but doesn't allow copying text (owner password "owner-secret"). */
    static Path restrictedPdf(Path folder, String name) throws IOException {
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            AccessPermission limits = new AccessPermission();
            limits.setCanExtractContent(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "", limits);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            doc.save(file.toFile());
        }
        return file;
    }

    /** A small PNG picture, wider than tall. */
    static Path png(Path folder, String name) throws IOException {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 300, 200);
        g.dispose();
        Path file = folder.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    /**
     * A PDF with a big photo-like picture on each page, stored without lossy compression,
     * like a scan from a phone app — the kind of file Compress is for. A few MB per page.
     */
    static Path photoPdf(Path folder, String name, int pages, String title) throws IOException {
        Path file = folder.resolve(name);
        java.util.Random random = new java.util.Random(42);
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                BufferedImage photo = new BufferedImage(1100, 1500, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < photo.getHeight(); y++) {
                    for (int x = 0; x < photo.getWidth(); x++) {
                        int r = (x * 255 / photo.getWidth() + random.nextInt(24)) & 0xFF;
                        int g = (y * 255 / photo.getHeight() + random.nextInt(24)) & 0xFF;
                        int b = ((x + y + p * 80) % 256 + random.nextInt(24)) & 0xFF;
                        photo.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
                PDPage page = new PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                doc.addPage(page);
                var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, photo);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            if (title != null) doc.getDocumentInformation().setTitle(title);
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * A 300×200 JPEG (left half orange, right half blue) with an EXIF note saying
     * "turn me {@code orientation}" — how phones save photos taken upright.
     */
    static Path jpegWithOrientation(Path folder, String name, int orientation) throws IOException {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 150, 200);
        g.setColor(Color.BLUE);
        g.fillRect(150, 0, 150, 200);
        g.dispose();
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", plain);
        byte[] jpeg = plain.toByteArray();

        // APP1 "Exif" block: big-endian TIFF header, one IFD entry, tag 0x0112 (orientation), type SHORT.
        byte[] exif = {
                (byte) 0xFF, (byte) 0xE1, 0, 34,                       // APP1, length 34
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,                          // "MM", 42, first IFD at 8
                0, 1,                                                 // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};                                          // no next IFD
        byte[] withExif = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, withExif, 0, 2);                    // SOI
        System.arraycopy(exif, 0, withExif, 2, exif.length);
        System.arraycopy(jpeg, 2, withExif, 2 + exif.length, jpeg.length - 2);
        return Files.write(folder.resolve(name), withExif);
    }

    /** A TIFF scan with several pages. */
    static Path multiPageTiff(Path folder, String name, int pages) throws IOException {
        Path file = folder.resolve(name);
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int p = 0; p < pages; p++) {
                BufferedImage page = new BufferedImage(170, 240, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = page.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 170, 240);
                g.setColor(Color.BLACK);
                g.drawString("Scan page " + (p + 1), 20, 40);
                g.dispose();
                writer.writeToSequence(new javax.imageio.IIOImage(page, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return file;
    }

    /**
     * A scanned page: the lines are drawn into a 300 dpi picture and the picture is the
     * whole page, so there's no selectable text until OCR adds it.
     */
    static Path scannedPdf(Path folder, String name, String... lines) throws IOException {
        BufferedImage scan = new BufferedImage(2480, 3508, BufferedImage.TYPE_BYTE_GRAY);   // A4 at 300 dpi
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, scan.getWidth(), scan.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 60));                 // about 14 pt
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], 250, 400 + i * 110);
        g.dispose();
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, scan);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * The smallest Word file that opens: a zip with the three parts a .docx needs.
     * Each string becomes one page (page breaks between them).
     */
    static Path docx(Path folder, String name, String... pages) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(pages[i]).append("</w:t></w:r></w:p>");
        }
        Path file = folder.resolve(name);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zipEntry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            zipEntry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            zipEntry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>");
        }
        return file;
    }

    private static void zipEntry(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * A page with a title, then a table with a border around every cell (drawn as
     * separate lines, the way many PDF makers do), then a closing sentence.
     */
    static Path ruledTablePdf(Path folder, String name, String[][] rows) throws IOException {
        Path file = folder.resolve(name);
        float left = 72, top = 640, rowHeight = 22, columnWidth = 130;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                writeLine(content, font, 16, left, 700, "Stock report for November");
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < rows[r].length; c++) {
                        writeLine(content, font, 11, left + c * columnWidth + 5, top - r * rowHeight - 15, rows[r][c]);
                    }
                }
                content.setLineWidth(0.8f);
                int columns = rows[0].length;
                for (int r = 0; r <= rows.length; r++) {             // one short line per cell edge
                    for (int c = 0; c < columns; c++) {
                        content.moveTo(left + c * columnWidth, top - r * rowHeight);
                        content.lineTo(left + (c + 1) * columnWidth, top - r * rowHeight);
                    }
                }
                for (int c = 0; c <= columns; c++) {
                    content.moveTo(left + c * columnWidth, top);
                    content.lineTo(left + c * columnWidth, top - rows.length * rowHeight);
                }
                content.stroke();
                writeLine(content, font, 11, left, top - rows.length * rowHeight - 40, "Counted by the night shift.");
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A page with a paragraph, then a table laid out with spaces only (no lines), like a bank statement. */
    static Path spacedTablePdf(Path folder, String name, String[][] rows, float[] columnX) throws IOException {
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                writeLine(content, font, 11, 72, 720, "Dear customer, here is your statement for the month of October.");
                writeLine(content, font, 11, 72, 706, "Please check every line and tell us about anything you don't recognise.");
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < rows[r].length; c++) {
                        if (!rows[r][c].isEmpty()) writeLine(content, font, 10, columnX[c], 660 - r * 16, rows[r][c]);
                    }
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A scanned page (a 300 dpi picture, no text) showing a ruled table. */
    static Path scannedTablePdf(Path folder, String name, String[][] rows) throws IOException {
        BufferedImage scan = new BufferedImage(2480, 3508, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, scan.getWidth(), scan.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 50));
        int left = 250, top = 500, rowHeight = 100, columnWidth = 600;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) g.drawString(rows[r][c], left + c * columnWidth + 25, top + r * rowHeight + 68);
        }
        g.setStroke(new java.awt.BasicStroke(3));
        for (int r = 0; r <= rows.length; r++) g.drawLine(left, top + r * rowHeight, left + rows[0].length * columnWidth, top + r * rowHeight);
        for (int c = 0; c <= rows[0].length; c++) g.drawLine(left + c * columnWidth, top, left + c * columnWidth, top + rows.length * rowHeight);
        g.dispose();
        Path file = folder.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, scan);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static void writeLine(PDPageContentStream content, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    static int pageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    static String text(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    static byte[] bytes(Path file) throws IOException {
        return Files.readAllBytes(file);
    }
}
