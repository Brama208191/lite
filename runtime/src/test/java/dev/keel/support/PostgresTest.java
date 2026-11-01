package dev.keel.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

public abstract class PostgresTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final StubReceiver RECEIVER = new StubReceiver();

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("keel.receiver.url", RECEIVER::url);
        registry.add("keel.model.fake.min-latency", () -> "0ms");
        registry.add("keel.model.fake.max-latency", () -> "0ms");
        registry.add("keel.worker.retry-backoff", () -> "10ms");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate events, leases, notes, workers, runs");
        RECEIVER.reset();
    }

    protected void expireLease(UUID runId) {
        jdbc.update("update leases set expires_at = clock_timestamp() - interval '1 second' where run_id = ?", runId);
    }

    protected int notesFor(String linePrefix) {
        return jdbc.queryForObject("select count(*) from notes where line like ?", Integer.class, linePrefix + "%");
    }
}
