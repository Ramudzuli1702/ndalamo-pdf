package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.ndalamo.models.Settings;
import com.ndalamo.utils.SafeSave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * "Tell us what you think": star ratings and reviews, sent to the owner's spreadsheet (a Google
 * Sheet with a small Apps Script that adds each review as a row, decision 020). This is the one
 * thing Ndalamo ever sends: the stars, the words, Ndalamo's version and the date, and only after
 * the person presses Submit. No files, file names or details about the PC.
 *
 * A review is written to feedback-queue.json in %APPDATA%\Ndalamo first, then sent; without
 * internet it waits there and goes out on a later try (every 15 minutes, and at start-up).
 * Sending runs off the JavaFX thread.
 */
public final class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Longest review sent; the box stops there too. */
    public static final int MAX_LENGTH = 2000;
    /** First ask after this many successful tool runs... */
    static final int RUNS_BEFORE_ASKING = 5;
    /** ...then, after "Maybe later", at most once a week and only after this many more. */
    static final int RUNS_BETWEEN_ASKS = 5;
    static final int DAYS_BETWEEN_ASKS = 7;

    /** One review, as stored while it waits and as sent. */
    public record Review(String kind, String id, int rating, String review, String version, String written) {}

    /** Answers to the welcome question: who uses NdalamoPDF and what for (WelcomeQuestionDialog). */
    public record About(String kind, String id, String role, List<String> uses, String version, String written) {}

    private final Path queueFile;
    private final String endpoint;
    private HttpClient http;
    private ScheduledExecutorService retries;

    public FeedbackService(Path folder, String endpoint) {
        this.queueFile = folder.resolve("feedback-queue.json");
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : endpoint.strip();
    }

    /**
     * The spreadsheet's address, from feedback.properties in the app (endpoint=...). Null when
     * none is set: then Ndalamo neither shows "Tell us what you think" nor asks.
     */
    public static String configuredEndpoint() {
        try (InputStream in = FeedbackService.class.getResourceAsStream("/feedback.properties")) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            String url = p.getProperty("endpoint", "").strip();
            return url.startsWith("https://") ? url : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether reviews can be sent anywhere (an address is set). */
    public boolean available() {
        return endpoint != null;
    }

    /**
     * Whether to offer the review box now, after a successful tool run: not if the person chose
     * "Never show this again" or has already sent a review; first after a few successful runs, then
     * at most once a week after a few more.
     */
    public static boolean shouldAsk(Settings settings, LocalDate today) {
        if (settings.isFeedbackNeverAsk() || settings.isFeedbackGiven()) return false;
        int runs = settings.getSuccessfulRuns();
        if (runs < RUNS_BEFORE_ASKING) return false;
        String last = settings.getFeedbackLastAsked();
        if (last == null || last.isBlank()) return true;
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(last), today);
            return days >= DAYS_BETWEEN_ASKS && runs - settings.getRunsAtLastAsk() >= RUNS_BETWEEN_ASKS;
        } catch (RuntimeException e) {
            return true;                                   // an unreadable date: ask again
        }
    }

    /** A new review with its own id (so a review sent twice after a lost reply can be spotted). */
    public static Review review(int rating, String text, String version) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() > MAX_LENGTH) trimmed = trimmed.substring(0, MAX_LENGTH);
        return new Review("review", UUID.randomUUID().toString(), Math.max(1, Math.min(5, rating)), trimmed, version,
                LocalDateTime.now().withNano(0).toString());
    }

    /** Answers to the welcome question, with their own id. Null role or no uses are fine. */
    public static About about(String role, List<String> uses, String version) {
        return new About("about", UUID.randomUUID().toString(), role, List.copyOf(uses), version,
                LocalDateTime.now().withNano(0).toString());
    }

    /** Puts a review or answers in the queue, to be sent by {@link #sendWaiting()}. */
    public synchronized void add(Object item) throws IOException {
        List<JsonObject> queue = new ArrayList<>(waiting());
        queue.add(GSON.toJsonTree(item).getAsJsonObject());
        save(queue);
    }

    /** What's not sent yet, as the JSON that will be sent. */
    public synchronized List<JsonObject> waiting() {
        if (!Files.exists(queueFile)) return List.of();
        try {
            List<JsonObject> saved = GSON.fromJson(Files.readString(queueFile), new TypeToken<List<JsonObject>>() {}.getType());
            return saved == null ? List.of() : saved;
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read {}; no reviews waiting", queueFile, e);
            return List.of();
        }
    }

    private void save(List<JsonObject> queue) throws IOException {
        Files.createDirectories(queueFile.getParent());
        if (queue.isEmpty()) {
            Files.deleteIfExists(queueFile);
            return;
        }
        String json = GSON.toJson(queue);
        SafeSave.write(queueFile, temp -> Files.writeString(temp, json, StandardCharsets.UTF_8));
    }

    /**
     * Sends every review waiting, oldest first, and keeps the ones that couldn't go (no internet,
     * the spreadsheet not answering). Returns how many were sent. Never throws.
     */
    public synchronized int sendWaiting() {
        if (endpoint == null) return 0;
        List<JsonObject> queue = new ArrayList<>(waiting());
        int sent = 0;
        while (!queue.isEmpty() && send(queue.get(0))) {
            queue.remove(0);
            sent++;
        }
        if (sent > 0) {
            try {
                save(queue);
            } catch (IOException e) {
                log.warn("Could not update {}", queueFile, e);
            }
        }
        return sent;
    }

    /**
     * Posts one review. Google answers a script's POST with a redirect to the reply, which is
     * fetched with GET (followed here by hand: Java's own redirect handling may repeat the POST).
     */
    private boolean send(JsonObject review) {
        try {
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build();
            }
            HttpRequest post = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(review), StandardCharsets.UTF_8)).build();
            HttpResponse<String> reply = http.send(post, HttpResponse.BodyHandlers.ofString());
            for (int hops = 0; hops < 3 && reply.statusCode() / 100 == 3; hops++) {
                String next = reply.headers().firstValue("Location").orElse(null);
                if (next == null) break;
                reply = http.send(HttpRequest.newBuilder(URI.create(endpoint).resolve(next))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            }
            boolean ok = reply.statusCode() == 200 && reply.body().contains("\"ok\":true");
            if (!ok) log.info("Review not accepted yet (HTTP {}); it stays queued", reply.statusCode());
            return ok;
        } catch (IOException | IllegalArgumentException e) {
            log.info("Review not sent yet ({}); it stays queued", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Tries the queue soon after start-up and every 15 minutes after that, on a background thread. */
    public void startRetrying() {
        if (endpoint == null || retries != null) return;
        retries = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feedback-sender");
            t.setDaemon(true);
            return t;
        });
        retries.scheduleWithFixedDelay(() -> {
            if (!waiting().isEmpty()) sendWaiting();
        }, 30, 15 * 60, TimeUnit.SECONDS);
    }

    public void stop() {
        if (retries != null) retries.shutdownNow();
    }
}
