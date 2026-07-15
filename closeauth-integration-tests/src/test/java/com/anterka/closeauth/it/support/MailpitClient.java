package com.anterka.closeauth.it.support;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A thin client over Mailpit's REST API (default port 8025). Its reason for existing is the reusable
 * {@link #waitForMessageTo} helper — "wait for an email to this recipient to arrive" with a bounded poll (NOT
 * sleep-and-hope) — which every email-driven journey in later stages will use. Reads only the plain-text body; the
 * caller extracts codes/links from it.
 *
 * <p>Deliberately independent of REST Assured's global base URI/path (the app's) — every call sets its own
 * {@code baseUri} + empty {@code basePath} so it always targets Mailpit, never the app.
 */
public final class MailpitClient {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final String baseUri;

    public MailpitClient(String baseUri) {
        this.baseUri = baseUri;
    }

    /**
     * Polls Mailpit until an email addressed to {@code recipient} arrives, then returns it (body included). Fails with a
     * clear {@link AssertionError} if none arrives within {@code timeout}.
     */
    public MailpitMessage waitForMessageTo(String recipient, Duration timeout) {
        String target = recipient.toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> id = findMessageIdTo(target);
            if (id.isPresent()) {
                return fetchMessage(id.get());
            }
            sleep();
        }
        throw new AssertionError("No email addressed to '" + recipient + "' arrived in Mailpit within " + timeout);
    }

    private Optional<String> findMessageIdTo(String target) {
        Response response = mailpit().get("/api/v1/messages?limit=50");
        List<Map<String, Object>> messages = response.jsonPath().getList("messages");
        if (messages == null) {
            return Optional.empty();
        }
        for (Map<String, Object> message : messages) {
            if (addressedTo(message, target)) {
                return Optional.ofNullable(message.get("ID")).map(String::valueOf);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static boolean addressedTo(Map<String, Object> message, String target) {
        Object to = message.get("To");
        if (!(to instanceof List<?> recipients)) {
            return false;
        }
        for (Object entry : recipients) {
            if (entry instanceof Map<?, ?> address
                    && target.equalsIgnoreCase(String.valueOf(((Map<String, Object>) address).get("Address")))) {
                return true;
            }
        }
        return false;
    }

    private MailpitMessage fetchMessage(String id) {
        Response response = mailpit().get("/api/v1/message/{id}", id);
        response.then().statusCode(200);
        return new MailpitMessage(
                id,
                response.jsonPath().getList("To.Address"),
                response.jsonPath().getString("Subject"),
                response.jsonPath().getString("Text"));
    }

    private io.restassured.specification.RequestSpecification mailpit() {
        return RestAssured.given().baseUri(baseUri).basePath("");
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Mailpit", e);
        }
    }
}
