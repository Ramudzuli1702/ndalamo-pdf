package com.ndalamo.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ndalamo.models.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reviews: when the box is offered, and sending with a queue for offline moments. A small local
 * server stands in for the spreadsheet's script, answering like Google does (a redirect to the reply).
 */
class FeedbackServiceTest {

    @TempDir
    Path dir;

    @Test
    void offeredAfterAFewRunsThenWeeklyUntilReviewedOrTurnedOff() {
        Settings s = new Settings();
        LocalDate today = LocalDate.of(2026, 11, 11);
        s.setSuccessfulRuns(4);
        assertFalse(FeedbackService.shouldAsk(s, today), "not before the fifth successful run");
        s.setSuccessfulRuns(5);
        assertTrue(FeedbackService.shouldAsk(s, today));

        s.setFeedbackLastAsked(today.toString());           // "Maybe later"
        s.setRunsAtLastAsk(5);
        s.setSuccessfulRuns(12);
        assertFalse(FeedbackService.shouldAsk(s, today.plusDays(3)), "not again within a week");
        s.setSuccessfulRuns(7);
        assertFalse(FeedbackService.shouldAsk(s, today.plusDays(10)), "not again before a few more runs");
        s.setSuccessfulRuns(12);
        assertTrue(FeedbackService.shouldAsk(s, today.plusDays(10)));

        s.setFeedbackNeverAsk(true);
        assertFalse(FeedbackService.shouldAsk(s, today.plusDays(30)), "Never show this again");
        s.setFeedbackNeverAsk(false);
        s.setFeedbackGiven(true);
        assertFalse(FeedbackService.shouldAsk(s, today.plusDays(30)), "already reviewed");
    }

    @Test
    void aReviewIsSentAndLeavesTheQueue() throws Exception {
        List<String> received = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exec", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location", "/reply");   // like Apps Script
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/reply", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            FeedbackService feedback = new FeedbackService(dir, "http://127.0.0.1:" + server.getAddress().getPort() + "/exec");
            feedback.add(FeedbackService.review(4, "  Compress saved my upload. Please add more languages.  ", "1.0.0"));

            assertEquals(1, feedback.sendWaiting());
            assertTrue(feedback.waiting().isEmpty(), "sent reviews leave the queue");
            JsonObject sent = new Gson().fromJson(received.get(0), JsonObject.class);
            assertEquals(4, sent.get("rating").getAsInt());
            assertEquals("Compress saved my upload. Please add more languages.", sent.get("review").getAsString());
            assertEquals("1.0.0", sent.get("version").getAsString());
            assertEquals("review", sent.get("kind").getAsString());
            assertEquals(6, sent.keySet().size(), "only kind, id, rating, review, version and date: " + sent.keySet());

            feedback.add(FeedbackService.about("Student", List.of("Studies and assignments", "Applications and forms (jobs, bursaries, IDs)"), "1.0.1"));
            assertEquals(1, feedback.sendWaiting());
            JsonObject about = new Gson().fromJson(received.get(1), JsonObject.class);
            assertEquals("about", about.get("kind").getAsString());
            assertEquals("Student", about.get("role").getAsString());
            assertEquals(2, about.getAsJsonArray("uses").size());
            assertEquals(6, about.keySet().size(), "only kind, id, role, uses, version and date: " + about.keySet());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void offlineReviewsWaitAndAreKept() throws Exception {
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closed.getAddress().getPort();
        closed.stop(0);                                    // nothing answers on this port: "offline"
        FeedbackService feedback = new FeedbackService(dir, "http://127.0.0.1:" + port + "/exec");
        feedback.add(FeedbackService.review(5, "Great", "1.0.0"));
        feedback.add(FeedbackService.review(3, "Okay", "1.0.0"));

        assertEquals(0, feedback.sendWaiting());
        assertEquals(2, feedback.waiting().size(), "kept for the next try");
        assertEquals(2, new FeedbackService(dir, null).waiting().size(), "still there after a restart");
    }

    @Test
    void reviewsAreTidiedAndWithoutAnAddressNothingIsSent() throws Exception {
        FeedbackService.Review r = FeedbackService.review(9, "x".repeat(FeedbackService.MAX_LENGTH + 50), "1.0.0");
        assertEquals(5, r.rating(), "stars kept between 1 and 5");
        assertEquals(FeedbackService.MAX_LENGTH, r.review().length());

        FeedbackService none = new FeedbackService(dir, "");
        assertFalse(none.available());
        none.add(r);
        assertEquals(0, none.sendWaiting());
    }
}
