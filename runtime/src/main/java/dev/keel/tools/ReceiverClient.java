package dev.keel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.KeelProperties;
import dev.keel.core.Json;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ReceiverClient {

    private static final int ATTEMPTS = 3;

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    public ReceiverClient(KeelProperties properties) {
        this.baseUrl = properties.receiver().url().replaceAll("/+$", "");
        this.timeout = properties.receiver().timeout();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public ToolResult post(String path, String idempotencyKey, JsonNode body) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return ToolResult.ok(response.body());
                }
                if (status == 400 || status == 422) {
                    return ToolResult.error("Receiver rejected the request (" + status + "): " + response.body());
                }
                last = new IOException("Receiver answered " + status);
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OutcomeUnknownException("Interrupted while calling " + path, e);
            }
            backoff(attempt);
        }
        throw new OutcomeUnknownException("No answer from " + path + " after " + ATTEMPTS + " tries", last);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(100L << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
