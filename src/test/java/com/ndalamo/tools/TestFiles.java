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
