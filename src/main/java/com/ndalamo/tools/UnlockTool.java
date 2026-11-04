package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unlock PDF: saves a copy without the password, for someone who knows it
 * (SPEC §4). The tool screen asks for each file's password in a dialog before
 * running and checks it; the passwords are only held in memory for this run.
 *
 * It never tries to guess or get around a password. A file that only has print/copy
 * restrictions needs its full ("owner") password too.
 */
public class UnlockTool implements PdfTool {

    @Override public String id() { return "unlock"; }
    @Override public String name() { return "Unlock PDF"; }
    @Override public String description() { return "Remove a known password"; }
    @Override public String category() { return "Security & compare"; }
    @Override public String outputSuffix() { return "unlocked"; }
    @Override public List<OptionField> optionFields() { return List.of(); }
    @Override public boolean needsPasswords() { return true; }

    @Override
    public String buttonLabel(int files) {
        return "Unlock " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Unlocking " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " unlocked";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        @SuppressWarnings("unchecked")
        Map<Path, String> passwords = (Map<Path, String>) options.getOrDefault(PASSWORDS, Map.of());

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Unlocking " + input.getFileName());
            progress.file(i, 0.2, "Unlocking");
            String password = passwords.getOrDefault(input, "");
            try (PDDocument doc = Loader.loadPDF(input.toFile(), password, IOUtils.createTempFileOnlyStreamCache())) {
                if (!doc.isEncrypted()) {
                    results.add(new FileResult(input, null, Files.size(input), 0, FileResult.Status.SKIPPED,
                            "This file doesn't have a password, so there was nothing to unlock"));
                    progress.file(i, 1, "Nothing to unlock");
                    continue;
                }
                if (!doc.getCurrentAccessPermission().isOwnerPermission() && password.isEmpty()) {
                    results.add(PdfFiles.failed(input, "It has print/copy restrictions; type its password to remove them"));
                    progress.file(i, 1, "Couldn't finish");
                    continue;
                }
                doc.setAllSecurityToBeRemoved(true);
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                        "Opens without a password"));
                progress.file(i, 1, "Done");
            } catch (InvalidPasswordException e) {
                results.add(PdfFiles.failed(input, "That password didn't work"));
                progress.file(i, 1, "Couldn't finish");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }
}
