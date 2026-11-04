package com.ndalamo.services;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.ndalamo.utils.ExifOrientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Small page pictures for the tool screen's preview and file rows.
 *
 * Pages are drawn on one background thread, a file at a time, smallest effort
 * first: low resolution, opened with a temp-file cache. Each picture is kept,
 * so scrolling or changing options never redraws a page. Files are only read.
 *
 * Usage: {@code thumbnails.start(file, pages); thumbnails.whenReady(file, 1, image -> view.setImage(image));}
 */
public class PageThumbnails {

    /** Pages longer than this many pixels on their long side are scaled down. */
    private static final int LONG_SIDE = 200;
    /** Most pages drawn per file; a 900-page PDF shows its first 300. */
    public static final int MAX_PAGES = 300;

    private static final Logger log = LoggerFactory.getLogger(PageThumbnails.class);

    // One thread for the whole app: drawing pages is never allowed to slow down real work much.
    private static final ExecutorService DRAWER = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "ndalamo-thumbnails");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // Only touched on the JavaFX thread.
    private final Map<String, Image> done = new HashMap<>();
    private final Map<String, List<Consumer<Image>>> waiting = new HashMap<>();
    private final Set<Path> started = new HashSet<>();

    /** Starts drawing a file's pages in the background, unless that has already started. */
    public void start(Path file, int pageCount) {
        if (!started.add(file)) return;
        int pages = Math.min(pageCount, MAX_PAGES);
        DRAWER.execute(() -> {
            String ext = FileInspector.extension(file);
            boolean tiff = ext.equals("tif") || ext.equals("tiff");
            if (FileInspector.IMAGE_EXTENSIONS.contains(ext) || tiff) {
                drawPictureFile(file, pages, tiff);
                return;
            }
            try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < Math.min(pages, doc.getNumberOfPages()); i++) {
                    PDRectangle box = doc.getPage(i).getCropBox();
                    float scale = LONG_SIDE / Math.max(box.getWidth(), box.getHeight());
                    Image image = toFxImage(renderer.renderImage(i, scale, ImageType.RGB));
                    int page = i + 1;
                    Platform.runLater(() -> deliver(file, page, image));
                }
            } catch (Exception e) {
                // Password-protected or damaged: the preview just shows blank pages for it.
                log.debug("No thumbnails for {}", file, e);
            }
        });
    }

    /**
     * Thumbnails for picture files. JavaFX can't open TIFFs and ignores the "turn me"
     * note in phone photos, so those go through Java's image reader and are turned
     * the way the phone meant; everything else uses JavaFX's quicker loader.
     */
    private void drawPictureFile(Path file, int pages, boolean tiff) {
        try {
            int orientation = tiff ? 1 : ExifOrientation.read(file);
            if (!tiff && orientation == 1) {
                Image image = new Image(file.toUri().toString(), LONG_SIDE, LONG_SIDE, true, true, false);
                Platform.runLater(() -> deliver(file, 1, image));
                return;
            }
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                var readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    for (int i = 0; i < pages; i++) {
                        BufferedImage frame = ExifOrientation.apply(reader.read(i), orientation);
                        Image image = toFxImage(smaller(frame));
                        int page = i + 1;
                        Platform.runLater(() -> deliver(file, page, image));
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.debug("No thumbnail for {}", file, e);
        }
    }

    /** A copy no longer than LONG_SIDE on its long side, so big scans don't fill memory. */
    private static BufferedImage smaller(BufferedImage picture) {
        double scale = (double) LONG_SIDE / Math.max(picture.getWidth(), picture.getHeight());
        if (scale >= 1) return picture;
        int w = Math.max(1, (int) (picture.getWidth() * scale)), h = Math.max(1, (int) (picture.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(picture, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Calls back with the page's picture now if it's drawn, otherwise as soon as it is. */
    public void whenReady(Path file, int page, Consumer<Image> callback) {
        Image image = done.get(key(file, page));
        if (image != null) {
            callback.accept(image);
        } else {
            waiting.computeIfAbsent(key(file, page), k -> new ArrayList<>()).add(callback);
        }
    }

    private void deliver(Path file, int page, Image image) {
        String key = key(file, page);
        done.put(key, image);
        List<Consumer<Image>> callbacks = waiting.remove(key);
        if (callbacks != null) callbacks.forEach(c -> c.accept(image));
    }

    private static String key(Path file, int page) {
        return file.toAbsolutePath() + "#" + page;
    }

    /** Copies a drawn page into a JavaFX image (there's no ready-made converter without an extra library). */
    private static Image toFxImage(BufferedImage picture) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        int[] pixels = picture.getRGB(0, 0, w, h, null, 0, w);
        WritableImage image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return image;
    }
}
