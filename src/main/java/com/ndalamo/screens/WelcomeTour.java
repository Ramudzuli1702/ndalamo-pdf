package com.ndalamo.screens;

import com.ndalamo.tools.PdfTool;
import com.ndalamo.tools.Tools;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The welcome tour, shown the first time Ndalamo opens (and from Settings › Help): the window
 * dims except for a spotlight on one part of Home at a time (the drop zone, search, then every
 * tool card in turn, Pipelines, Settings, recent files), with a card beside it explaining it.
 * The spotlight glides from one part to the next. Back, Next and Skip; the arrow keys, Enter and
 * Esc work too.
 */
public final class WelcomeTour extends Pane {

    /** One stop: what to light up (null for a card in the middle), a title and a sentence or two. */
    private record Step(Supplier<Node> target, String title, String text, String counter) {}

    /** What each tool is for, in a sentence or two (the card's description is shorter). */
    private static final Map<String, String> EXPLAINED = Map.ofEntries(
            Map.entry("merge", "Combine several PDFs, or pictures, into one file. Drag the files into the order you want first."),
            Map.entry("split", "Cut a PDF into smaller files: by page ranges like 1-3, 5, every few pages, or by file size."),
            Map.entry("rotate", "Turn pages that came out sideways or upside down, all of them or just the ones you pick."),
            Map.entry("remove-pages", "Take pages out of a PDF, such as blank pages or a cover you don't need."),
            Map.entry("workspace", "See every page as a thumbnail and rearrange, rotate, delete or crop them by hand, across several PDFs."),
            Map.entry("compress", "Make PDFs smaller for email, keeping them readable. Recommended is right for most files."),
            Map.entry("compress-to-size", "Squeeze a PDF under a limit, like 2 MB for an upload portal. Ndalamo tries gentle settings first."),
            Map.entry("repair", "Rescue a PDF that won't open properly, like a download that was cut off, into a clean new copy."),
            Map.entry("pdfa", "Save a copy for long-term keeping (PDF/A), with its fonts embedded, as archives and courts ask for."),
            Map.entry("pdf-to-word", "Turn a PDF into a Word document you can edit, keeping its fonts, spacing, tables and pictures."),
            Map.entry("pdf-to-markdown", "Turn a PDF into Markdown, plain text with headings and lists, for notes and websites."),
            Map.entry("markdown-to-pdf", "Turn Markdown notes into a neatly formatted PDF, with headings, code and tables."),
            Map.entry("pdf-to-excel", "Copy the tables in a PDF into an Excel spreadsheet, one sheet per table."),
            Map.entry("office-to-pdf", "Turn Word, Excel and PowerPoint files into PDFs. This one uses LibreOffice, which is free."),
            Map.entry("image-to-pdf", "Put photos and scans (JPG, PNG, TIFF) into one PDF, the right way up."),
            Map.entry("pdf-to-jpg", "Save each page as a picture, or take out the pictures inside a PDF."),
            Map.entry("ocr", "Make a scan searchable: Ndalamo reads the text in the page images so you can search and copy it."),
            Map.entry("page-numbers", "Number the pages: at the top or bottom, in a few styles, starting where you like."),
            Map.entry("watermark", "Stamp text like DRAFT or CONFIDENTIAL, or your logo, across the pages."),
            Map.entry("crop", "Trim the edges of pages, by margins or by drawing a box on a page."),
            Map.entry("sign", "Add your signature: draw it, type it in a handwriting font, or use a picture, and place it on the page."),
            Map.entry("redact", "Remove sensitive text for good, like ID numbers. It's taken out of the file, not just covered."),
            Map.entry("fill-forms", "Type answers into a PDF form's boxes and tick boxes, and lock them in if you like."),
            Map.entry("protect", "Lock a PDF with a password, and choose whether people may print or copy from it."),
            Map.entry("unlock", "Take the password off a PDF you know the password to, so it opens freely."),
            Map.entry("compare", "See what changed between two versions of a document, marked page by page."));

    private static final double PAD = 8;            // the spotlight's room around its target

    private final MainWindow window;
    private final HomeScreen home;
    private final Runnable onDone;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;

    private final DoubleProperty x = new SimpleDoubleProperty(), y = new SimpleDoubleProperty();
    private final DoubleProperty w = new SimpleDoubleProperty(), h = new SimpleDoubleProperty();
    private Shape shade = new Rectangle();
    private final Rectangle ring = new Rectangle();
    private final VBox card = new VBox(10);
    private final Label counter = new Label(), title = new Label(), text = new Label();
    private final javafx.scene.Node logo = LogoMark.appIcon(52);       // on the welcome and closing cards
    private final ProgressBar progress = new ProgressBar(0);
    private final Button back = new Button("Back"), next = new Button("Next");
    private Timeline glide;

    public WelcomeTour(MainWindow window, HomeScreen home, Runnable onDone) {
        this.window = window;
        this.home = home;
        this.onDone = onDone;
        getStyleClass().add("welcome-tour");
        setFocusTraversable(true);
        // The tour sits over the whole window and takes every click, so Home can't be used underneath it.
        setOnMouseClicked(e -> e.consume());
        setOnMousePressed(e -> e.consume());

        ring.getStyleClass().add("tour-ring");
        ring.setArcWidth(18);
        ring.setArcHeight(18);
        ring.setMouseTransparent(true);
        ring.xProperty().bind(x);
        ring.yProperty().bind(y);
        ring.widthProperty().bind(w);
        ring.heightProperty().bind(h);
        ring.visibleProperty().bind(w.greaterThan(1));
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(ring.strokeWidthProperty(), 2.5)),
                new KeyFrame(Duration.millis(900), new KeyValue(ring.strokeWidthProperty(), 5, Interpolator.EASE_BOTH)));
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
        for (DoubleProperty p : List.of(x, y, w, h)) p.addListener((obs, was, now) -> redrawShade());
        widthProperty().addListener((obs, was, now) -> place(false));
        heightProperty().addListener((obs, was, now) -> place(false));

        buildCard();
        buildSteps();
        getChildren().addAll(shade, ring, card);
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.ENTER) go(index + 1);
            else if (e.getCode() == KeyCode.LEFT) go(index - 1);
            else if (e.getCode() == KeyCode.ESCAPE) finish();
            else return;
            e.consume();
        });
    }

    /** Starts the tour over the window. */
    public void start() {
        window.getChildren().add(this);
        setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(260), this);
        in.setToValue(1);
        in.play();
        home.showAllTools();
        javafx.application.Platform.runLater(() -> {
            go(0);
            requestFocus();
        });
    }

    private void buildCard() {
        card.getStyleClass().add("tour-card");
        card.setMaxWidth(380);
        card.setPrefWidth(380);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        counter.getStyleClass().add("tour-counter");
        title.getStyleClass().add("tour-title");
        title.setWrapText(true);
        text.getStyleClass().add("tour-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        progress.getStyleClass().add("tour-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        Hyperlink skip = new Hyperlink("Skip tour");
        skip.setOnAction(e -> finish());
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        back.getStyleClass().add("button-secondary");
        back.setOnAction(e -> go(index - 1));
        next.getStyleClass().add("button-primary");
        next.setDefaultButton(false);
        next.setOnAction(e -> go(index + 1));
        HBox buttons = new HBox(8, skip, push, back, next);
        buttons.setAlignment(Pos.CENTER_LEFT);
        logo.managedProperty().bind(logo.visibleProperty());
        card.getChildren().addAll(logo, counter, title, text, progress, buttons);
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setAccessibleRole(javafx.scene.AccessibleRole.NODE);
    }

    private void buildSteps() {
        steps.add(new Step(null, "Welcome to Ndalamo",
                "A free PDF toolkit that works entirely on this PC: your files never leave it. Here's a quick look around, "
                + "then every tool in turn. It takes about two minutes, and you can skip it any time.", ""));
        steps.add(new Step(home::dropZoneNode, "Start with your files",
                "Drop files anywhere on the window, or click Choose files. Ndalamo looks at them and suggests the tool "
                + "that fits, like OCR for a scan or Compress for a big file.", ""));
        steps.add(new Step(() -> home.header().searchBox(), "Find a tool quickly",
                "Type what you want to do, like “compress” or “to Word”. Press Ctrl+K from anywhere to jump here.", ""));
        List<PdfTool> tools = Tools.all().stream().filter(PdfTool::isReady).toList();
        for (int i = 0; i < tools.size(); i++) {
            PdfTool tool = tools.get(i);
            steps.add(new Step(() -> home.card(tool), tool.name(),
                    EXPLAINED.getOrDefault(tool.id(), tool.description() + "."),
                    tool.category() + " · tool " + (i + 1) + " of " + tools.size()));
        }
        steps.add(new Step(() -> home.header().pipelinesButton(), "Pipelines: several tools in one click",
                "Chain tools you use together, like OCR, then Compress, then Protect, and run them on any files at once. "
                + "Watch folders run a pipeline on every file put in a folder.", ""));
        steps.add(new Step(() -> home.header().settingsButton(), "Settings",
                "Where results are saved and how they're named, dark mode, the Word add-in, and this tour again.", ""));
        steps.add(new Step(home::sidebarNode, "Recent files and history",
                "Files you've worked on show up here, with what you last did. History lists everything, "
                + "with Open and Use again. It all stays on this PC.", ""));
        steps.add(new Step(null, "You're ready",
                "Drop a file to begin. The original file is never changed: every tool saves a new one next to it.", ""));
    }

    private void go(int to) {
        if (to < 0) return;
        if (to >= steps.size()) {
            finish();
            return;
        }
        index = to;
        Step step = steps.get(index);
        Node target = step.target() == null ? null : step.target().get();
        back.setDisable(index == 0);
        next.setText(index == steps.size() - 1 ? "Start using Ndalamo" : index == 0 ? "Show me around" : "Next");
        // Fade the card out, move the spotlight (scrolling Home first if the target is off screen), fade the card back in.
        FadeTransition out = new FadeTransition(Duration.millis(110), card);
        out.setToValue(0);
        out.setOnFinished(e -> {
            logo.setVisible(step.target() == null);
            counter.setText(step.counter().isEmpty() ? (index + 1) + " of " + steps.size() : step.counter());
            title.setText(step.title());
            text.setText(step.text());
            progress.setProgress((index + 1) / (double) steps.size());
            card.setAccessibleText(step.title() + ". " + step.text());
            if (target != null && isInToolList(target)) home.scrollIntoView(target, () -> place(true));
            else place(true);
        });
        out.play();
    }

    private boolean isInToolList(Node target) {
        return target != home.header().searchBox() && target != home.header().pipelinesButton()
               && target != home.header().settingsButton() && target != home.sidebarNode();
    }

    /** Moves the spotlight to the current step's target (gliding, or at once after a resize) and places the card. */
    private void place(boolean animate) {
        if (index < 0 || getWidth() <= 0) return;
        Step step = steps.get(index);
        Node target = step.target() == null ? null : step.target().get();
        double tx, ty, tw, th;
        if (target == null || target.getScene() == null) {
            tx = getWidth() / 2;
            ty = getHeight() / 2;
            tw = 0;
            th = 0;
        } else {
            Bounds b = sceneToLocal(target.localToScene(target.getBoundsInLocal()));
            tx = b.getMinX() - PAD;
            ty = b.getMinY() - PAD;
            tw = b.getWidth() + 2 * PAD;
            th = b.getHeight() + 2 * PAD;
        }
        if (glide != null) glide.stop();
        if (animate) {
            glide = new Timeline(new KeyFrame(Duration.millis(420),
                    new KeyValue(x, tx, Interpolator.EASE_BOTH), new KeyValue(y, ty, Interpolator.EASE_BOTH),
                    new KeyValue(w, tw, Interpolator.EASE_BOTH), new KeyValue(h, th, Interpolator.EASE_BOTH)));
            glide.setOnFinished(e -> showCard(tx, ty, tw, th, true));
            glide.play();
        } else {
            x.set(tx);
            y.set(ty);
            w.set(tw);
            h.set(th);
            showCard(tx, ty, tw, th, false);
        }
    }

    /** Puts the card below the spotlight if there's room, else above, else beside it; centred when there's no target. */
    private void showCard(double tx, double ty, double tw, double th, boolean animate) {
        card.applyCss();
        card.autosize();
        double cw = card.prefWidth(-1), ch = card.prefHeight(cw);
        double cx, cy;
        if (tw <= 1) {
            cx = (getWidth() - cw) / 2;
            cy = (getHeight() - ch) / 2;
        } else if (ty + th + 16 + ch < getHeight() - 16) {
            cx = tx;
            cy = ty + th + 16;
        } else if (ty - 16 - ch > 16) {
            cx = tx;
            cy = ty - 16 - ch;
        } else {
            cx = tx + tw + 16;
            cy = ty;
            if (cx + cw > getWidth() - 16) cx = tx - 16 - cw;
        }
        cx = Math.max(16, Math.min(getWidth() - cw - 16, cx));
        cy = Math.max(16, Math.min(getHeight() - ch - 16, cy));
        card.resizeRelocate(cx, cy, cw, ch);
        if (!animate) {
            card.setOpacity(1);
            return;
        }
        card.setTranslateY(10);
        FadeTransition in = new FadeTransition(Duration.millis(220), card);
        in.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(220), card);
        rise.setToY(0);
        new ParallelTransition(in, rise).play();
        next.requestFocus();
    }

    /** The dimmed layer: the whole window less a rounded hole around the spotlight. */
    private void redrawShade() {
        Rectangle all = new Rectangle(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
        Shape dim;
        if (w.get() > 1 && h.get() > 1) {
            Rectangle hole = new Rectangle(x.get(), y.get(), w.get(), h.get());
            hole.setArcWidth(18);
            hole.setArcHeight(18);
            dim = Shape.subtract(all, hole);
        } else {
            dim = all;
        }
        dim.setFill(Color.rgb(12, 16, 28, 0.58));
        dim.setMouseTransparent(true);
        int at = getChildren().indexOf(shade);
        if (at >= 0) getChildren().set(at, dim);
        shade = dim;
    }

    private void finish() {
        FadeTransition out = new FadeTransition(Duration.millis(220), this);
        out.setToValue(0);
        out.setOnFinished(e -> {
            window.getChildren().remove(this);
            home.scrollIntoView(home.dropZoneNode(), () -> {});      // back to the top of Home
            onDone.run();
        });
        out.play();
    }
}
