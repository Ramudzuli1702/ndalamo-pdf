package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repair PDF (SPEC §4): rescues damaged PDFs, such as a download that was cut off, a
 * file from a crashed program, or an attachment that won't open. First it checks the
 * file for the usual kinds of damage, so it can say what was wrong. PDFBox's lenient
 * reader then rebuilds the file's table of contents (the "xref", which says where
 * each part of the file is) by scanning for every part it can find, and a clean copy
 * is saved. Every page is drawn once to find any still-damaged page. The input file
 * is never changed.
 */
public class RepairTool implements PdfTool {

    private static final Pattern STARTXREF = Pattern.compile("startxref\\s+(\\d+)\\s+%%EOF");

    @Override public String id() { return "repair"; }
    @Override public String name() { return "Repair PDF"; }
    @Override public String description() { return "Fix damaged files"; }
    @Override public String category() { return "Optimize"; }
    @Override public String outputSuffix() { return "repaired"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of();
    }

    @Override
    public String buttonLabel(int files) {
        return "Repair " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Repairing " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " repaired";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.file(i, 0.05, "Checking");
            progress.update((double) i / inputs.size(), "Repairing " + input.getFileName());
            List<String> problems = check(input);

            PDDocument doc;
            try {
                doc = Loader.loadPDF(input.toFile());
            } catch (InvalidPasswordException e) {
                results.add(PdfFiles.failed(input, "It's locked with a password. Unlock it first, then repair the copy."));
                progress.file(i, 1, "Couldn't finish");
                continue;
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, problems.contains(NOT_A_PDF)
                        ? "It isn't a PDF, or is too damaged to recognise as one. Nothing could be recovered."
                        : "It's too damaged to recover: no pages could be found in it."));
                progress.file(i, 1, "Couldn't finish");
                continue;
            }
            try (doc) {
                int pages = doc.getNumberOfPages();
                if (pages == 0) {
                    results.add(PdfFiles.failed(input, "It's too damaged to recover: no pages could be found in it."));
                    progress.file(i, 1, "Couldn't finish");
                    continue;
                }
                // Draw every page once: a page that can't be drawn is still damaged.
                PDFRenderer renderer = new PDFRenderer(doc);
                List<Integer> broken = new ArrayList<>();
                for (int p = 0; p < pages; p++) {
                    if (progress.isCancelled()) return results;      // nothing saved for this file
                    progress.file(i, 0.1 + 0.8 * p / pages, "Checking page " + (p + 1) + " of " + pages);
                    try {
                        renderer.renderImageWithDPI(p, 18);
                    } catch (Exception | StackOverflowError e) {
                        broken.add(p + 1);
                    }
                }

                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", pages);
                SafeSave.write(output, temp -> doc.save(temp.toFile()));

                String found = problems.isEmpty() ? "No damage found; a clean copy was saved"
                        : "Fixed: " + String.join("; ", problems);
                String note = found + " · " + Format.count(pages, "page", "pages") + " recovered"
                        + (broken.isEmpty() ? "" : " · " + (broken.size() == 1 ? "page " : "pages ")
                        + PageRanges.format(broken) + " may still show incompletely");
                results.add(new FileResult(input, output, Files.size(input), Files.size(output),
                        broken.isEmpty() ? FileResult.Status.SUCCESS : FileResult.Status.PARTIAL, note));
                progress.file(i, 1, "Done");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    private static final String NOT_A_PDF = "it didn't start like a PDF";

    /**
     * The kinds of damage found, in plain words. Reads only the start and end of the
     * file: the header, the "%%EOF" end marker, and where the table of contents is said to be.
     */
    static List<String> check(Path file) throws IOException {
        List<String> problems = new ArrayList<>();
        long size = Files.size(file);
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[(int) Math.min(1024, size)];
            in.readFully(head);
            if (!new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-")) problems.add(NOT_A_PDF);

            int tailLength = (int) Math.min(2048, size);
            byte[] tail = new byte[tailLength];
            in.seek(size - tailLength);
            in.readFully(tail);
            String end = new String(tail, StandardCharsets.ISO_8859_1);
            Matcher m = STARTXREF.matcher(end);
            if (!end.contains("%%EOF")) {
                problems.add("the file was cut off before its end (an interrupted download or copy)");
            } else if (!m.find()) {
                problems.add("the file's table of contents was missing");
            } else {
                long offset = Long.parseLong(m.group(1));
                // The offset should point at "xref" or at the object holding a compressed table.
                String there = "";
                if (offset >= 0 && offset < size - 4) {
                    byte[] look = new byte[(int) Math.min(32, size - offset)];
                    in.seek(offset);
                    in.readFully(look);
                    there = new String(look, StandardCharsets.ISO_8859_1);
                }
                if (!there.startsWith("xref") && !there.matches("(?s)\\s*\\d+\\s+\\d+\\s+obj.*")) {
                    problems.add("the file's table of contents pointed to the wrong place, so it was rebuilt");
                }
            }
        }
        return problems;
    }
}
