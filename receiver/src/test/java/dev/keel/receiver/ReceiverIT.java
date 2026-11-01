package dev.keel.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "receiver.min-latency=0ms", "receiver.max-latency=0ms"})
class ReceiverIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=receiver");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate effects, requests");
    }

    @Test
    void replayingAKeyReturnsTheOriginalResponseWithoutASecondEffect() {
        var charge = charge("ORD-1", 1200);
        var first = post("/v1/charges", "run:1:1", charge);
        var second = post("/v1/charges", "run:1:1", charge);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText()).startsWith("ch_");
        var stats = http.getForObject("/v1/stats?referencePrefix=ORD-1", LedgerQueries.Stats.class);
        assertThat(stats.requests()).isEqualTo(2);
        assertThat(stats.applied()).isEqualTo(1);
        assertThat(stats.replayed()).isEqualTo(1);
        assertThat(stats.effects()).isEqualTo(1);
        assertThat(stats.duplicates()).isEmpty();
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        post("/v1/charges", "run:2:1", charge("ORD-2", 1200));
        var changed = post("/v1/charges", "run:2:1", charge("ORD-2", 9900));

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var stats = http.getForObject("/v1/stats?referencePrefix=ORD-2", LedgerQueries.Stats.class);
        assertThat(stats.conflicts()).isEqualTo(1);
        assertThat(stats.effects()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyApplyOnce() throws Exception {
        var email = Map.of("to", "ada@example.com", "subject", "Order ORD-3 confirmed", "body", "Thanks");
        var tasks = new ArrayList<Callable<ResponseEntity<JsonNode>>>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> post("/v1/emails", "run:3:0", email));
        }
        var ids = new ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (var future : pool.invokeAll(tasks)) {
                var response = future.get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                ids.add(response.getBody().get("id").asText());
            }
        }
        assertThat(ids).hasSize(16).containsOnly(ids.getFirst());
        var stats = http.getForObject("/v1/stats?referencePrefix=ORD-3", LedgerQueries.Stats.class);
        assertThat(stats.applied()).isEqualTo(1);
        assertThat(stats.replayed()).isEqualTo(15);
    }

    @Test
    void differentKeysForTheSameBusinessEffectShowUpAsDuplicates() {
        post("/v1/charges", "attempt-a", charge("ORD-4", 1200));
        post("/v1/charges", "attempt-b", charge("ORD-4", 1200));

        var stats = http.getForObject("/v1/stats?referencePrefix=ORD-4", LedgerQueries.Stats.class);
        assertThat(stats.effects()).isEqualTo(2);
        assertThat(stats.duplicates()).singleElement()
                .satisfies(d -> assertThat(d.effects()).isEqualTo(2));
    }

    @Test
    void missingKeyOrInvalidBodyIsABadRequest() {
        var headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        var noKey = http.postForEntity("/v1/charges", new HttpEntity<>(charge("ORD-5", 1200), headers), String.class);
        var badAmount = post("/v1/charges", "run:5:1", charge("ORD-5", 1));

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badAmount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void effectsAreListedByKeyPrefix() {
        post("/v1/emails", "run-x:1:0", Map.of("to", "ada@example.com", "subject", "Order ORD-6 confirmed", "body", "b"));
        post("/v1/emails", "run-x:1:0", Map.of("to", "ada@example.com", "subject", "Order ORD-6 confirmed", "body", "b"));

        var effects = http.getForObject("/v1/effects?keyPrefix=run-x:", JsonNode.class).get("effects");
        assertThat(effects).hasSize(1);
        assertThat(effects.get(0).get("requests").asInt()).isEqualTo(2);
        assertThat(effects.get(0).get("replays").asInt()).isEqualTo(1);
        assertThat(effects.get(0).get("response").get("status").asText()).isEqualTo("sent");
    }

    private static Map<String, Object> charge(String reference, long amount) {
        return Map.of("customer", "cus_1", "amount_cents", amount, "currency", "usd", "reference", reference);
    }

    private ResponseEntity<JsonNode> post(String path, String key, Object body) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
