package com.ndalamo.services;

import com.ndalamo.models.WorkspacePage;
import com.ndalamo.utils.SafeSave;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PDF side of the Page workspace: reads a file's pages when it's opened, and
 * builds the new PDF when the user saves. The files the pages come from are only
 * read; the new file is written with SafeSave. Both are slow for big files, so the
 * screen calls them on a background thread.
 */
public class WorkspaceService {

    /** The pages of a PDF, ready for the workspace. */
    public List<WorkspacePage> open(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            List<WorkspacePage> pages = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                pages.add(WorkspacePage.of(file, i + 1, Math.floorMod(doc.getPage(i).getRotation(), 360)));
            }
            return pages;
        }
    }

    /**
     * Writes the pages, in order, to a new PDF. Turning and cropping are applied
     * here; a blank page takes the size of the page before it (A4 if it's first).
     */
    public void save(List<WorkspacePage> pages, Path target) throws IOException {
        Map<Path, PDDocument> sources = new HashMap<>();
        try (PDDocument out = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDRectangle lastSize = PDRectangle.A4;
            for (WorkspacePage page : pages) {
                PDPage made;
                if (page.isBlank()) {
                    made = new PDPage(lastSize);
                    out.addPage(made);
                    made.setRotation(page.extraRotation());
                } else {
                    PDDocument source = sources.get(page.source());
                    if (source == null) {
                        source = Loader.loadPDF(page.source().toFile(), IOUtils.createTempFileOnlyStreamCache());
                        source.setAllSecurityToBeRemoved(true);
                        sources.put(page.source(), source);
                    }
                    PDPage original = source.getPage(page.sourcePage() - 1);
                    // importPage shares the page's content with the open source file, which
                    // is why every source stays open until the new file is saved.
                    made = out.importPage(original);
                    made.setRotation(Math.floorMod(original.getRotation() + page.extraRotation(), 360));
                    if (page.crop() != null) made.setCropBox(cropBox(original.getCropBox(), page.crop()));
                }
                lastSize = made.getCropBox();
            }
            SafeSave.write(target, temp -> out.save(temp.toFile()));
        } finally {
            for (PDDocument source : sources.values()) source.close();
        }
    }

    /** Size of a file, for the "12 pages · 3.4 MB" line; 0 if it can't be read. */
    public long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static PDRectangle cropBox(PDRectangle box, WorkspacePage.Crop crop) {
        float x = box.getLowerLeftX(), y = box.getLowerLeftY(), w = box.getWidth(), h = box.getHeight();
        return new PDRectangle(
                (float) (x + crop.left() * w), (float) (y + crop.bottom() * h),
                (float) ((crop.right() - crop.left()) * w), (float) ((crop.top() - crop.bottom()) * h));
    }
}
