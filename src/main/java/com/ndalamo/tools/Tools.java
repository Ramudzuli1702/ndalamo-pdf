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
            new MergeTool(),
            new SplitTool(),
            new RotateTool(),          // own cards for Rotate and Remove pages: decision 005
            new RemovePagesTool(),
            new WorkspaceTool(),

            new CompressTool(),
            new CompressTool(true),    // same tool, starting with "Target size" chosen
            soon("repair", "Repair PDF", "Fix damaged files", "Optimize"),
            soon("pdfa", "PDF to PDF/A", "Long-term archiving", "Optimize"),

            new PdfToWordTool(),
            new PdfToMarkdownTool(),
            new MarkdownToPdfTool(),
            new PdfToExcelTool(),
            new OfficeToPdfTool(),
            new ImageToPdfTool(),
            new PdfToJpgTool(),
            new OcrTool(),

            new PageNumbersTool(),
            new WatermarkTool(),
            new CropTool(),
            soon("sign", "Sign PDF", "Draw, type or upload", "Edit & sign"),
            soon("redact", "Redact PDF", "Black out sensitive info", "Edit & sign"),
            soon("fill-forms", "Fill forms", "Complete PDF forms", "Edit & sign"),

            new ProtectTool(),
            new UnlockTool(),
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
