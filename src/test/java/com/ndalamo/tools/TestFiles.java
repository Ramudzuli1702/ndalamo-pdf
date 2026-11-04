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
