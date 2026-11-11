package com.ndalamo.tools;

import com.ndalamo.models.FileResult;
import com.ndalamo.models.OptionField;
import com.ndalamo.models.Progress;
import com.ndalamo.models.SaveTo;
import com.ndalamo.utils.Format;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Protect PDF: saves a copy that needs a password to open, encrypted with AES-256
 * (SPEC §4). Printing and copying text can be allowed or not.
 *
 * The password is only held in memory for this run; it is never saved or logged.
 * Behind the scenes a separate random "owner" password guards the print/copy
 * settings, so someone who knows the open password can't simply switch them off.
 */
public class ProtectTool implements PdfTool {

    static final String PASSWORD = "password";
    static final String CONFIRM = "confirm";
    static final String ALLOW_PRINTING = "allowPrinting";
    static final String ALLOW_COPYING = "allowCopying";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String id() { return "protect"; }
    @Override public String name() { return "Protect PDF"; }
    @Override public String description() { return "Add a password"; }
    @Override public String category() { return "Security & compare"; }
    @Override public String outputSuffix() { return "protected"; }

    @Override
    public List<OptionField> optionFields() {
        return List.of(
                new OptionField(PASSWORD, "Password", OptionField.Type.PASSWORD, null),
                new OptionField(CONFIRM, "Type it again", OptionField.Type.PASSWORD, null),
                new OptionField(ALLOW_PRINTING, "Allow printing", OptionField.Type.CHECKBOX, true),
                new OptionField(ALLOW_COPYING, "Allow copying text", OptionField.Type.CHECKBOX, false));
    }

    @Override
    public String checkOptions(Map<String, Object> options) {
        String password = (String) options.get(PASSWORD);
        if (password == null || password.isEmpty()) return "Choose a password.";
        if (password.length() < 4) return "Use at least 4 characters, so the password is hard to guess.";
        if (!password.equals(options.get(CONFIRM))) return "The two passwords don't match. Type them again.";
        return null;
    }

    @Override
    public String saveToNote() {
        return "Keep the password somewhere safe: without it the file can't be opened, and NdalamoPDF can't recover it.";
    }

    @Override
    public String buttonLabel(int files) {
        return "Protect " + Format.count(files, "file", "files");
    }

    @Override
    public String workingLabel(int files) {
        return "Protecting " + Format.count(files, "file", "files") + "…";
    }

    @Override
    public String doneLabel(int files) {
        return Format.count(files, "file", "files") + " protected";
    }

    @Override
    public List<FileResult> run(List<Path> inputs, Map<String, Object> options, SaveTo saveTo, Progress progress)
            throws IOException {
        String password = (String) options.get(PASSWORD);
        AccessPermission permissions = new AccessPermission();
        permissions.setCanPrint(Boolean.TRUE.equals(options.get(ALLOW_PRINTING)));
        permissions.setCanPrintFaithful(Boolean.TRUE.equals(options.get(ALLOW_PRINTING)));
        permissions.setCanExtractContent(Boolean.TRUE.equals(options.get(ALLOW_COPYING)));

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (progress.isCancelled()) break;
            Path input = inputs.get(i);
            progress.update((double) i / inputs.size(), "Protecting " + input.getFileName());
            progress.file(i, 0.2, "Encrypting");
            try (PDDocument doc = PdfFiles.open(input)) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(randomOwnerPassword(), password, permissions);
                policy.setEncryptionKeyLength(256);    // AES-256
                doc.protect(policy);
                Path output = saveTo.outputFor(input, outputSuffix(), "pdf", doc.getNumberOfPages());
                SafeSave.write(output, temp -> doc.save(temp.toFile()));
                results.add(new FileResult(input, output, Files.size(input), Files.size(output), FileResult.Status.SUCCESS,
                        "Needs the password to open"));
                progress.file(i, 1, "Done");
            } catch (IOException e) {
                results.add(PdfFiles.failed(input, PdfFiles.problem(e).equals("needs a password")
                        ? "It already has a password. Unlock it first, then protect it again"
                        : "Couldn't open it — it " + PdfFiles.problem(e)));
                progress.file(i, 1, "Couldn't finish");
            }
        }
        progress.update(1, "Done");
        return results;
    }

    private static String randomOwnerPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
