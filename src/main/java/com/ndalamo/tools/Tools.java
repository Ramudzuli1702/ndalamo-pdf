package com.ndalamo.tools;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The list of every tool in the app, in home-screen order. The home screen,
 * search, pipeline builder and drop suggestions all read from here, so a new
 * tool only has to be added in one place.
 *
 * Tools that aren't built yet are {@link ComingSoonTool} placeholders; each build
 * chunk replaces its placeholders with the real classes.
 */
public final class Tools {

    /** Home-screen sections, in display order. */
    public static final List<String> CATEGORIES =
            List.of("Organize", "Optimize", "Convert", "Edit & sign", "Security & compare");

    // Names, descriptions and order follow docs/ui/screens/Main.dc.html.
    private static final List<PdfTool> ALL = List.of(
            soon("merge", "Merge PDF", "Combine files in any order", "Organize"),
            soon("split", "Split PDF", "By range, every page or size", "Organize"),
            soon("workspace", "Page workspace", "Reorder, rotate, remove, crop", "Organize"),

            soon("compress", "Compress PDF", "Smaller files, same pages", "Optimize"),
            soon("compress-to-size", "Compress to size", "e.g. under 2 MB for uploads", "Optimize"),
            soon("repair", "Repair PDF", "Fix damaged files", "Optimize"),
            soon("pdfa", "PDF to PDF/A", "Long-term archiving", "Optimize"),

            soon("pdf-to-word", "PDF to Word", "Editable .docx", "Convert"),
            soon("pdf-to-excel", "PDF to Excel", "Tables to .xlsx", "Convert"),
            soon("office-to-pdf", "Office to PDF", "Word, Excel, PowerPoint", "Convert"),
            soon("image-to-pdf", "Image to PDF", "JPG, PNG, scans", "Convert"),
            soon("pdf-to-jpg", "PDF to JPG", "Pages as images", "Convert"),
            soon("ocr", "OCR PDF", "Make scans searchable", "Convert"),

            soon("page-numbers", "Page numbers", "Position, style, start number", "Edit & sign"),
            soon("watermark", "Watermark", "Text or image", "Edit & sign"),
            soon("sign", "Sign PDF", "Draw, type or upload", "Edit & sign"),
            soon("redact", "Redact PDF", "Black out sensitive info", "Edit & sign"),
            soon("fill-forms", "Fill forms", "Complete PDF forms", "Edit & sign"),

            soon("protect", "Protect PDF", "Add a password", "Security & compare"),
            soon("unlock", "Unlock PDF", "Remove a known password", "Security & compare"),
            soon("compare", "Compare PDF", "Spot differences", "Security & compare"));

    private Tools() {}

    public static List<PdfTool> all() {
        return ALL;
    }

    public static Optional<PdfTool> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * Tools whose name or description contains the search text, ignoring case —
     * so "to word" finds "PDF to Word". An empty search returns every tool.
     */
    public static List<PdfTool> search(String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return ALL;
        return ALL.stream()
                .filter(t -> (t.name() + " " + t.description()).toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    private static PdfTool soon(String id, String name, String description, String category) {
        return new ComingSoonTool(id, name, description, category);
    }
}
