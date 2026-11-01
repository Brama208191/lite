package dev.keel.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keel.role=all", "keel.worker.poll-interval=50ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiIT extends PostgresTest {

    @Autowired
    TestRestTemplate http;
    @LocalServerPort
    int port;

    @Test
    void aRunStartedOverHttpStreamsItsWholeLogAndCompletes() throws Exception {
        var created = http.postForEntity("/api/runs", Map.of("task", "Process order ORD-900 for Ada", "model", "fake"), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = created.getBody().get("id").asText();

        var types = CompletableFuture.supplyAsync(() -> streamUntilDone(id)).get(30, TimeUnit.SECONDS);

        assertThat(types).startsWith("RUN_CREATED", "LEASE_ACQUIRED", "MODEL_RESPONDED").endsWith("RUN_COMPLETED");
        var detail = http.getForObject("/api/runs/" + id, JsonNode.class);
        assertThat(detail.get("run").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.get("events")).hasSize(types.size());
        assertThat(RECEIVER.effectsFor(id)).isEqualTo(2);
    }

    @Test
    void rejectsBadInputAndUnconfiguredModels() {
        assertThat(http.postForEntity("/api/runs", Map.of("task", " "), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/runs", Map.of("task", "hi", "model", "gpt"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var anthropic = http.postForEntity("/api/runs", Map.of("task", "hi", "model", "anthropic"), JsonNode.class);
        assertThat(anthropic.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(anthropic.getBody().get("message").asText()).contains("not configured");
    }

    @Test
    void killingARunNobodyHoldsIsAConflict() throws Exception {
        var id = http.postForEntity("/api/runs", Map.of("task", "Process order ORD-901"), JsonNode.class).getBody().get("id").asText();
        var deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)
                && !http.getForObject("/api/runs/" + id, JsonNode.class).get("run").get("status").asText().equals("COMPLETED")) {
            Thread.sleep(100);
        }
        assertThat(http.postForEntity("/api/runs/" + id + "/kill", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        var heartbeatDeadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(heartbeatDeadline) && http.getForObject("/api/workers", JsonNode.class).isEmpty()) {
            Thread.sleep(100);
        }
        assertThat(http.getForObject("/api/workers", JsonNode.class)).isNotEmpty();
    }

    private ArrayList<String> streamUntilDone(String id) {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runs/" + id + "/stream"))
                .timeout(Duration.ofSeconds(30)).build();
        var types = new ArrayList<String>();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        var type = line.replaceAll(".*\"type\":\"([A-Z_]+)\".*", "$1");
                        types.add(type);
                        if (type.equals("RUN_COMPLETED") || type.equals("RUN_FAILED")) {
                            return types;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return types;
    }
}
