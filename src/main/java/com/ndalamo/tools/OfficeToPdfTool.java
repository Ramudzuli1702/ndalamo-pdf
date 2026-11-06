package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.services.FileInspector;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Office to PDF: turns Word, Excel and PowerPoint files (and their OpenDocument
 * cousins) into PDFs, using LibreOffice installed on this PC (SPEC §4). Each file
 * is copied into a temp folder and converted there, so the original is only read;
 * the PDF then goes to the usual output name. Excel files get every sheet.
 */
public class OfficeToPdfTool implements PdfTool {

    static final Set<String> TYPES = Set.of(
            "doc", "docx", "odt", "rtf",          // documents
            "xls", "xlsx", "ods", "csv",          // spreadsheets
            "ppt", "pptx", "odp");                // presentations

    private final Supplier<Path> libreOffice;

    public OfficeToPdfTool() {
        this(LibreOffice::find);
    }

    /** For tests: use this soffice.exe (or none, if it supplies null). */
    OfficeToPdfTool(Supplier<Path> libreOffice) {
        this.libreOffice = libreOffice;
    }

    @Override public String id() { return "office-to-pdf"; }
    @Override public String name() { return "Office to PDF"; }
    @Override public String description() { return "Word, Excel, PowerPoint"; }
    @Override public String category() { return "Convert"; }
    @Override public String outputSuffix() { return "converted"; }
    @Override public Set<String> fileTypes() { return TYPES; }

    @Override
    public List<OptionField> optionFields() {
        return List.of();
    }

    @Override
    public String buttonLabel(int files) {
        return "Convert " + Format.count(files, "file", "files") + " to PDF";
    }

    @Override
    public String workingLabel(int files) {
        return "Converting " + Format.count(files, "file", "files") + " to PDF…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " converted to PDF";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException, InterruptedException {
        Path program = libreOffice.get();
        if (program == null) {
            throw new ToolProblem("Office to PDF needs LibreOffice, and it couldn't be found on this PC. "
                    + "Your files were not changed. Install LibreOffice (it's free), then try again.", null);
        }
        Path profile = profileFolder();

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.1, "Converting");
            progress.update((double) i / inputs.size(), "Converting " + input.getFileName());
            Path work = Files.createTempDirectory("ndalamo-office");
            try {
                FileResult result = convertOne(program, input, work, profile, saveTo, progress);
                if (result == null) break;                    // cancelled: LibreOffice was stopped, nothing saved
                results.add(result);
                progress.file(i, 1, "Done");
            } catch (LibreOffice.ConversionFailed e) {
                results.add(PdfFiles.failed(input, e.getMessage()));
                progress.file(i, 1, "Couldn't convert");
            } catch (IOException e) {
                if (!Files.exists(input)) {
                    results.add(PdfFiles.failed(input, "It was moved or deleted before it could be converted."));
                    progress.file(i, 1, "Couldn't convert");
                } else {
                    // Most likely soffice.exe itself couldn't start; every file would fail the same way.
                    throw new ToolProblem("LibreOffice couldn't be started. Your files were not changed. "
                            + "If LibreOffice was updated or moved, open Office to PDF again to find it.", e);
                }
            } finally {
                deleteQuietly(work);
            }
        }
        progress.update(1, "Done");
        return results;
    }

    /** Converts one file inside {@code work}; null if cancelled. */
    private FileResult convertOne(Path program, Path input, Path work, Path profile, SaveTo saveTo, Progress progress)
            throws IOException, InterruptedException {
        // A copy under the same name with unusual characters taken out: LibreOffice can trip over them, and it
        // never sees the original. The name matters: spreadsheets print it as the sheet name in the page header.
        Path copy = work.resolve(plainName(input) + "." + FileInspector.extension(input));
        Files.copy(input, copy);
        Path outFolder = Files.createDirectory(work.resolve("out"));

        Path pdf = LibreOffice.convert(program, copy, outFolder, profile, progress);
        if (pdf == null) return null;

        int pages;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            pages = doc.getNumberOfPages();
        }
        Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pages);
        SafeSave.write(output, temp -> Files.copy(pdf, temp, StandardCopyOption.REPLACE_EXISTING));
        return new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                Format.count(pages, "page", "pages"));
    }

    /** The file's name without its extension, keeping letters, digits, spaces and - _ ( ) only. */
    static String plainName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^\\p{L}\\p{N} _()\\-]", "").strip();
        return base.isEmpty() || base.startsWith("-") ? "document" : base;
    }

    /** LibreOffice's own settings folder for Ndalamo, so it never touches the user's LibreOffice settings. */
    private static Path profileFolder() throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path folder = (localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "Ndalamo") : Path.of(System.getProperty("user.home"), ".ndalamo"))
                .resolve("libreoffice-profile");
        return Files.createDirectories(folder);
    }

    private static void deleteQuietly(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A leftover temp folder is harmless; Windows cleans its temp folder.
        }
    }
}
