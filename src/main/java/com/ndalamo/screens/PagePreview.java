package com.ndalamo.screens;

import com.ndalamo.models.FileInfo;
import com.ndalamo.models.PageRef;
import com.ndalamo.services.PageThumbnails;
import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.PdfTool.Preview;
import com.ndalamo.utils.Icons;
import com.ndalamo.utils.PageRanges;
import com.ndalamo.utils.PageRanges.Range;
import com.ndalamo.utils.Theme;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The page preview under the file list on the tool screen. What it shows depends
 * on the tool (PdfTool.preview()):
 *
 *   MERGE   every page of the result in order, tagged A, B, C by file; drag to reorder
 *   ROTATE  each file's pages, chosen ones shown turned; click to choose
 *   REMOVE  each file's pages, chosen ones faded and crossed; click to choose
 *   SPLIT   pages coloured by the new file they go into; click a page to start a new file there
 *
 * Clicking never changes anything directly: it writes the page list into the tool's
 * page box (e.g. "2, 5-7") through {@code onPagesText}, so the box and the preview
 * always agree and the tool only ever reads its options.
 */
public class PagePreview extends VBox {

    private static final double PAGE_W = 96;
    private static final double PAGE_H = 136;
    // Background tints for Split's new files, cycled. All light enough for dark text.
    private static final String[] PART_TINTS = {"#E8EDFB", "#E6F4EC", "#FDEEE3", "#F1ECFB", "#FFF4E5", "#E4F3F5"};

    private final PdfTool tool;
    private final PageThumbnails thumbnails;
    private final Consumer<String> onPagesText;

    private List<Path> files = List.of();
    private Map<Path, FileInfo> info = Map.of();
    private Map<String, Object> options = Map.of();
    private List<PageRef> mergeOrder;              // null until the user drags a page

    public PagePreview(PdfTool tool, PageThumbnails thumbnails, Consumer<String> onPagesText) {
        super(12);
        this.tool = tool;
        this.thumbnails = thumbnails;
        this.onPagesText = onPagesText;
        getStyleClass().add("page-preview");
    }

    /** Redraws the preview for these files and options. Called after any change on the tool screen. */
    public void show(List<Path> newFiles, Map<Path, FileInfo> newInfo, Map<String, Object> newOptions) {
        if (!newFiles.equals(files)) mergeOrder = null;    // a different file list means a fresh page order
        files = List.copyOf(newFiles);
        info = new HashMap<>(newInfo);
        options = newOptions;
        redraw();
    }

    /** The page order the user dragged into place, or null if they didn't drag anything. */
    public List<PageRef> mergeOrder() {
        return mergeOrder;
    }

    private void redraw() {
        getChildren().clear();
        if (files.isEmpty()) return;

        Label title = new Label("Preview");
        title.getStyleClass().add("section-title");
        Label hint = new Label(hint());
        hint.getStyleClass().add("small-muted");
        hint.setWrapText(true);
        HBox heading = new HBox(12, title, hint);
        heading.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(hint, Priority.ALWAYS);
        if (mergeOrder != null) {
            Hyperlink reset = new Hyperlink("Reset order");
            reset.getStyleClass().add("link");
            reset.setOnAction(e -> {
                mergeOrder = null;
                redraw();
            });
            heading.getChildren().add(reset);
        }
        getChildren().add(heading);

        if (tool.preview() == Preview.MERGE) {
            getChildren().add(mergePages());
        } else {
            for (Path file : files) getChildren().add(filePages(file));
        }
    }

    private String hint() {
        String forEvery = files.size() > 1 ? " Your choices apply to every file." : "";
        return switch (tool.preview()) {
            case MERGE -> "The merged PDF, page by page. Drag pages to change their order.";
            case ROTATE -> "Click pages to choose which ones to turn." + forEvery;
            case REMOVE -> "Click pages to mark them for removal." + forEvery;
            case SPLIT -> "Each colour is a new file. Click a page to start a new file there." + forEvery;
            case NONE -> "";
        };
    }

    // ── Merge: one strip of all pages, drag to reorder ───────────────────

    private FlowPane mergePages() {
        List<PageRef> order = mergeOrder != null ? mergeOrder : defaultOrder();
        FlowPane grid = grid();
        for (int i = 0; i < order.size(); i++) {
            PageRef ref = order.get(i);
            String letter = String.valueOf((char) ('A' + Math.min(files.indexOf(ref.file()), 25)));
            VBox card = card(ref.file(), ref.page(), letter + " · " + ref.page(), null);
            card.setStyle("-fx-cursor: move;");
            Tooltip.install(card, new Tooltip(ref.file().getFileName() + ", page " + ref.page()));
            makeDraggable(card, i, order);
            grid.getChildren().add(card);
        }
        return grid;
    }

    private List<PageRef> defaultOrder() {
        List<PageRef> order = new ArrayList<>();
        for (Path file : files) {
            for (int p = 1; p <= shownPages(file); p++) order.add(new PageRef(file, p));
        }
        return order;
    }

    private void makeDraggable(VBox card, int index, List<PageRef> order) {
        card.setOnDragDetected(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("page:" + index);
            card.startDragAndDrop(TransferMode.MOVE).setContent(content);
            e.consume();
        });
        card.setOnDragOver(e -> {
            if (e.getDragboard().hasString() && e.getDragboard().getString().startsWith("page:")) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        card.setOnDragEntered(e -> card.getStyleClass().add("page-drop-target"));
        card.setOnDragExited(e -> card.getStyleClass().remove("page-drop-target"));
        card.setOnDragDropped(e -> {
            int from = Integer.parseInt(e.getDragboard().getString().substring(5));
            List<PageRef> newOrder = new ArrayList<>(order);
            newOrder.add(index, newOrder.remove(from));
            mergeOrder = newOrder;
            e.setDropCompleted(true);
            e.consume();
            redraw();
        });
    }

    // ── Rotate / Remove / Split: each file's own pages ───────────────────

    private VBox filePages(Path file) {
        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("file-name");
        VBox section = new VBox(8, name);

        FileInfo fileInfo = info.get(file);
        if (fileInfo == null) {
            section.getChildren().add(note("Checking pages…"));
            return section;
        }
        if (fileInfo.isPasswordProtected() || fileInfo.isDamaged()) {
            section.getChildren().add(note(fileInfo.isPasswordProtected()
                    ? "Can't show pages: this file needs a password." : "Can't show pages: the file may be damaged."));
            return section;
        }

        int pageCount = fileInfo.getPages();
        List<Integer> chosen = tool.previewPages(options, pageCount);
        int turn = tool.previewTurn(options);
        List<Range> parts = tool.preview() == Preview.SPLIT ? tool.previewParts(options, pageCount) : null;

        FlowPane grid = grid();
        for (int p = 1; p <= shownPages(file); p++) {
            int page = p;
            int part = partOf(parts, page);
            if (parts != null && part > 0 && parts.get(part).from() == page) grid.getChildren().add(cutMarker());

            VBox card = switch (tool.preview()) {
                case ROTATE -> chosen.contains(page) ? rotated(card(file, page, String.valueOf(page), null), turn)
                        : card(file, page, String.valueOf(page), null);
                case REMOVE -> chosen.contains(page) ? removed(card(file, page, String.valueOf(page), null))
                        : card(file, page, String.valueOf(page), null);
                default -> splitCard(file, page, part, parts);
            };
            card.setOnMouseClicked(e -> clicked(page, pageCount, chosen, parts));
            card.setStyle("-fx-cursor: hand;");
            grid.getChildren().add(card);
        }
        section.getChildren().add(grid);

        if (pageCount > PageThumbnails.MAX_PAGES) {
            section.getChildren().add(note("Showing the first " + PageThumbnails.MAX_PAGES + " of " + pageCount
                    + " pages. Type later pages in the box on the right."));
        }
        if (tool.preview() == Preview.SPLIT && parts == null) {
            section.getChildren().add(note("The files are worked out by size when it runs."));
        }
        return section;
    }

    private void clicked(int page, int pageCount, List<Integer> chosen, List<Range> parts) {
        if (tool.preview() == Preview.SPLIT) {
            if (page == 1) return;                // a new file can't start before the first page
            // Current starts of new files, minus or plus this page.
            TreeSet<Integer> starts = new TreeSet<>();
            if (parts != null) parts.forEach(r -> { if (r.from() > 1) starts.add(r.from()); });
            if (!starts.remove(page)) starts.add(page);
            onPagesText.accept(cutsToText(starts));
            return;
        }
        Set<Integer> pages = new TreeSet<>(chosen);
        if (!pages.remove(page)) pages.add(page);
        onPagesText.accept(PageRanges.format(pages));
    }

    /** New files starting at these pages, as ranges covering the whole file: {4, 9} → "1-3, 4-8, 9-". */
    static String cutsToText(TreeSet<Integer> starts) {
        List<String> ranges = new ArrayList<>();
        int from = 1;
        for (int start : starts) {
            ranges.add(start - 1 == from ? String.valueOf(from) : from + "-" + (start - 1));
            from = start;
        }
        ranges.add(from + "-");                   // the last file runs to the end, however long each file is
        return String.join(", ", ranges);
    }

    private static int partOf(List<Range> parts, int page) {
        if (parts == null) return -1;
        for (int i = 0; i < parts.size(); i++) {
            if (page >= parts.get(i).from() && page <= parts.get(i).to()) return i;
        }
        return -1;
    }

    // ── Page cards ───────────────────────────────────────────────────────

    /** A page picture in a page-shaped frame, with a label under it. */
    private VBox card(Path file, int page, String label, String tint) {
        ImageView picture = new ImageView();
        picture.setFitWidth(PAGE_W - 6);
        picture.setFitHeight(PAGE_H - 6);
        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        thumbnails.whenReady(file, page, picture::setImage);

        StackPane frame = new StackPane(picture);
        frame.getStyleClass().add("page-frame");
        frame.setMinSize(PAGE_W, PAGE_H);
        frame.setMaxSize(PAGE_W, PAGE_H);
        if (tint != null) frame.setStyle("-fx-background-color: " + tint + ";");

        Label caption = new Label(label);
        caption.getStyleClass().add("page-caption");
        VBox card = new VBox(4, frame, caption);
        card.setAlignment(Pos.TOP_CENTER);
        return card;
    }

    private VBox rotated(VBox card, int turn) {
        StackPane frame = (StackPane) card.getChildren().get(0);
        ImageView picture = (ImageView) frame.getChildren().get(0);
        if (turn % 180 != 0) {                    // sideways: fit inside a square so it doesn't spill out
            picture.setFitWidth(PAGE_W - 10);
            picture.setFitHeight(PAGE_W - 10);
        }
        picture.setRotate(turn);
        frame.getStyleClass().add("page-chosen");
        frame.getChildren().add(badge(Icons.check(12, "#FFFFFF"), Theme.ACCENT));
        return card;
    }

    private VBox removed(VBox card) {
        StackPane frame = (StackPane) card.getChildren().get(0);
        frame.getChildren().get(0).setOpacity(0.3);
        frame.getStyleClass().add("page-removed");
        frame.getChildren().add(badge(Icons.cross(12, "#FFFFFF"), Theme.DANGER));
        ((Label) card.getChildren().get(1)).setText(((Label) card.getChildren().get(1)).getText() + " · removed");
        return card;
    }

    private VBox splitCard(Path file, int page, int part, List<Range> parts) {
        if (parts == null || parts.isEmpty()) return card(file, page, String.valueOf(page), null);
        if (part < 0) {
            VBox card = card(file, page, page + " · left out", null);
            card.getChildren().get(0).setOpacity(0.4);
            return card;
        }
        return card(file, page, page + " · file " + (part + 1), PART_TINTS[part % PART_TINTS.length]);
    }

    private static StackPane badge(Node icon, String colour) {
        StackPane badge = new StackPane(icon);
        badge.setMinSize(22, 22);
        badge.setMaxSize(22, 22);
        badge.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 999;");
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        badge.setTranslateX(6);
        badge.setTranslateY(-6);
        return badge;
    }

    /** The line drawn before the first page of each new file in Split's preview. */
    private static Region cutMarker() {
        Region line = new Region();
        line.getStyleClass().add("cut-marker");
        line.setMinSize(3, PAGE_H);
        line.setMaxSize(3, PAGE_H);
        return line;
    }

    private int shownPages(Path file) {
        FileInfo fileInfo = info.get(file);
        if (fileInfo == null || fileInfo.isDamaged() || fileInfo.isPasswordProtected()) return 0;
        return Math.min(fileInfo.getPages(), PageThumbnails.MAX_PAGES);
    }

    private static FlowPane grid() {
        return new FlowPane(12, 14);
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("small-muted");
        label.setWrapText(true);
        return label;
    }
}
