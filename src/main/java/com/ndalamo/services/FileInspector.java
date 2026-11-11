package com.ndalamo.services;

import com.ndalamo.models.FileInfo;
import com.ndalamo.utils.Format;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Looks at a file before any tool runs: size, page count, password, damage.
 * Only reads the file — never changes it. Slow for big PDFs, so screens call it
 * on a background thread.
 *
 * It also suggests tools for files dropped on Home (SPEC §4.2): {@link #look} reads a
 * file's traits (scanned, locked, large...) and {@link #suggest} turns them into tools,
 * best first.
 */
public class FileInspector {

    public static final Set<String> OFFICE_EXTENSIONS = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv");
    /** Above this a PDF is "large": many email services and upload portals stop at 5 to 10 MB. */
    public static final long LARGE = 5L * 1024 * 1024;

    /** What one dropped file is: its kind ("pdf", "image", "office", "markdown" or "other"), details, and whether it's a scan. */
    public record Look(Path file, String kind, FileInfo info, boolean scanned) {}

    /** A suggested tool and the one-line reason shown under it. */
    public record Suggestion(String toolId, String reason) {}

    /** Inspects a file and, for a PDF, checks whether it's a scan. Reads only. */
    public Look look(Path file) {
        FileInfo info = inspect(file);
        String ext = extension(file);
        String kind = ext.equals("pdf") ? "pdf"
                : IMAGE_EXTENSIONS.contains(ext) || ext.equals("tif") || ext.equals("tiff") ? "image"
                : OFFICE_EXTENSIONS.contains(ext) ? "office"
                : ext.equals("md") || ext.equals("markdown") ? "markdown" : "other";
        boolean scanned = kind.equals("pdf") && !info.isPasswordProtected() && !info.isDamaged() && isScanned(file);
        return new Look(file, kind, info, scanned);
    }

    /**
     * True if the PDF's first pages are pictures with (almost) no text: a scan or a photo of
     * paper. Pages with no text and no pictures are just empty, not scanned.
     */
    public static boolean isScanned(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int checked = Math.min(3, doc.getNumberOfPages());
            if (checked == 0) return false;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(checked);
            int letters = stripper.getText(doc).replaceAll("\\s", "").length();
            boolean pictures = false;
            for (int p = 0; p < checked && !pictures; p++) {
                PDResources resources = doc.getPage(p).getResources();
                if (resources == null) continue;
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        pictures = true;
                        break;
                    }
                }
            }
            return pictures && letters < 20 * checked;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Tools for these files, best first; the first is shown as "Recommended". Empty if none of
     * the files is something Ndalamo works with. At most five.
     */
    public static List<Suggestion> suggest(List<Look> looks) {
        List<Look> pdfs = looks.stream().filter(l -> l.kind().equals("pdf")).toList();
        long images = looks.stream().filter(l -> l.kind().equals("image")).count();
        long office = looks.stream().filter(l -> l.kind().equals("office")).count();
        long markdown = looks.stream().filter(l -> l.kind().equals("markdown")).count();
        List<Look> locked = pdfs.stream().filter(l -> l.info().isPasswordProtected()).toList();
        List<Look> damaged = pdfs.stream().filter(l -> l.info().isDamaged()).toList();
        List<Look> usable = pdfs.stream().filter(l -> !l.info().isPasswordProtected() && !l.info().isDamaged()).toList();
        boolean scanned = usable.stream().anyMatch(Look::scanned);
        boolean restricted = usable.stream().anyMatch(l -> l.info().isRestricted());
        long pdfBytes = usable.stream().mapToLong(l -> l.info().getSize()).sum();
        boolean large = usable.stream().anyMatch(l -> l.info().getSize() > LARGE);
        boolean one = pdfs.size() == 1;
        String it = one ? "it" : "them";

        Map<String, String> out = new LinkedHashMap<>();
        // The first one added is the recommendation, so these come in order of what matters most.
        if (!damaged.isEmpty()) out.put("repair", (one ? "It looks damaged" : damaged.size() + " of the PDFs look damaged")
                + ". Repair rescues what it can into a new copy.");
        if (!locked.isEmpty()) out.put("unlock", (one ? "It's" : locked.size() + " of them are")
                + " locked with a password. Unlock to use the other tools.");
        if (pdfs.size() >= 2) out.put("merge", "Combine the " + pdfs.size() + " PDFs into one file, in the order you choose.");
        if (scanned) out.put("ocr", (one ? "This looks like a scan" : "Some look like scans")
                + ". OCR lets you search, copy and convert the text.");
        if (pdfs.isEmpty() && office > 0) {
            out.put("office-to-pdf", "Turn " + (office == 1 ? "the document" : "the " + office + " documents") + " into PDF.");
        }
        if (pdfs.isEmpty() && markdown > 0) out.put("markdown-to-pdf", "Turn the Markdown into a formatted PDF.");
        if (pdfs.isEmpty() && images > 0) {
            out.put("image-to-pdf", "Put " + (images == 1 ? "the picture" : "the " + images + " pictures") + " into one PDF.");
        }
        if (large) out.put("compress", Format.size(pdfBytes) + " is large for email or upload portals.");
        if (one && !usable.isEmpty() && !scanned) out.put("pdf-to-word", "Edit the text in Word.");

        // Then other useful tools.
        if (pdfs.size() == 2 && usable.size() == 2) out.putIfAbsent("compare", "See what changed between the two versions.");
        if (scanned) {
            out.putIfAbsent("compress", "Scans are often large; make " + it + " easier to email.");
            out.putIfAbsent("pdf-to-word", "Get editable text from the scan.");
            out.putIfAbsent("workspace", "Rotate or remove crooked pages.");
        }
        if (restricted) out.putIfAbsent("unlock", "Remove " + (one ? "its" : "their") + " printing and copying limits.");
        if (!locked.isEmpty()) out.putIfAbsent("protect", "Change who can open, print or copy " + it + ".");
        if (images > 0) out.putIfAbsent("image-to-pdf", "Put the " + (images == 1 ? "picture" : images + " pictures") + " into a PDF.");
        if (office > 0) out.putIfAbsent("office-to-pdf", "Turn the " + (office == 1 ? "document" : office + " documents") + " into PDF.");
        if (markdown > 0) out.putIfAbsent("markdown-to-pdf", "Turn the Markdown into a formatted PDF.");
        if (!usable.isEmpty()) {
            out.putIfAbsent("workspace", "Rearrange, rotate or remove pages.");
            out.putIfAbsent("compress", "Make " + it + " smaller for email.");
            if (one && usable.get(0).info().getPages() > 1) out.putIfAbsent("split", "Split it into smaller files.");
            out.putIfAbsent("sign", "Add your signature.");
            out.putIfAbsent("protect", "Add a password.");
        }

        List<Suggestion> suggestions = new ArrayList<>();
        out.forEach((id, reason) -> suggestions.add(new Suggestion(id, reason)));
        return suggestions.size() > 5 ? List.copyOf(suggestions.subList(0, 5)) : suggestions;
    }

    /** What the summary card says about the files, e.g. "8 pages · 14.6 MB · scanned pages, no selectable text". */
    public static String describe(List<Look> looks) {
        List<String> parts = new ArrayList<>();
        int pages = looks.stream().mapToInt(l -> l.info().getPages()).sum();
        if (pages > 0) parts.add(Format.count(pages, "page", "pages"));
        parts.add(Format.size(looks.stream().mapToLong(l -> l.info().getSize()).sum()));
        boolean one = looks.size() == 1;
        if (looks.stream().anyMatch(Look::scanned)) parts.add(one ? "scanned pages, no selectable text" : "some are scans");
        if (looks.stream().anyMatch(l -> l.info().isPasswordProtected())) parts.add(one ? "locked with a password" : "some are locked");
        if (looks.stream().anyMatch(l -> l.info().isDamaged())) parts.add(one ? "looks damaged" : "some look damaged");
        if (looks.stream().anyMatch(l -> l.info().isRestricted())) parts.add(one ? "printing or copying limited" : "some have limits");
        if (looks.stream().anyMatch(l -> l.kind().equals("other"))) parts.add(one ? "not a file NdalamoPDF works with" : "some can't be used");
        return String.join(" · ", parts);
    }

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");

    public FileInfo inspect(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileInfo(0, 0, false, true);
        }

        String ext = extension(file);
        if (IMAGE_EXTENSIONS.contains(ext)) return new FileInfo(size, 1, false, false);
        if (ext.equals("tif") || ext.equals("tiff")) return new FileInfo(size, tiffPages(file), false, false);
        if (!ext.equals("pdf")) return new FileInfo(size, 0, false, false);

        // Temp-file cache: a 500 MB scan shouldn't need 500 MB of memory just to count pages.
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return new FileInfo(size, doc.getNumberOfPages(), false, false, doc.isEncrypted());
        } catch (InvalidPasswordException e) {
            return new FileInfo(size, 0, true, false);
        } catch (IOException e) {
            return new FileInfo(size, 0, false, true);
        }
    }

    /**
     * Checks a password typed in the password dialog, without changing anything.
     * For a file that opens without a password but has restrictions, only its full
     * ("owner") password counts, since that's what removing restrictions needs.
     */
    public boolean passwordWorks(Path file, String password) {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), password, IOUtils.createTempFileOnlyStreamCache())) {
            if (opensWithoutPassword(file)) return doc.getCurrentAccessPermission().isOwnerPermission();
            return true;     // it needed a password to open, and this one opened it
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean opensWithoutPassword(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** How many pages a TIFF scan holds; 0 if it can't be read. */
    private static int tiffPages(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) return 0;
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return 0;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** Lower-case extension without the dot, e.g. "pdf"; empty if there is none. */
    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
