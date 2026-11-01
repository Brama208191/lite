package dev.keel.store;

import dev.keel.core.EventType;
import dev.keel.core.Json;
import dev.keel.core.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunStore {

    public record RunSummary(UUID id, String task, String model, String status, Instant createdAt, Instant updatedAt,
                             int events, String holder) {
    }

    private static final RowMapper<RunSummary> SUMMARY = (rs, i) -> new RunSummary(
            rs.getObject("id", UUID.class),
            rs.getString("task"),
            rs.getString("model"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getInt("events"),
            rs.getString("holder"));

    private static final String SELECT_SUMMARY = """
            select r.id, r.task, r.model, r.status, r.created_at, r.updated_at,
                   (select count(*) from events e where e.run_id = r.id) as events,
                   case when l.expires_at > clock_timestamp() then l.worker_id end as holder
            from runs r join leases l on l.run_id = r.id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RunStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public UUID create(String task, String model, int maxTurns) {
        var id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into runs (id, task, model, status) values (?, ?, ?, 'PENDING')", id, task, model);
            jdbc.update("insert into leases (run_id) values (?)", id);
            jdbc.update("""
                    insert into events (run_id, seq, type, payload, lease_token, worker_id)
                    values (?, 1, ?, ?::jsonb, 0, null)
                    """, id, EventType.RUN_CREATED.name(), Json.write(new Payloads.RunCreated(task, model, maxTurns)));
        });
        return id;
    }

    public List<RunSummary> recent(int limit) {
        return jdbc.query(SELECT_SUMMARY + " order by r.created_at desc limit ?", SUMMARY, limit);
    }

    public Optional<RunSummary> find(UUID id) {
        return jdbc.query(SELECT_SUMMARY + " where r.id = ?", SUMMARY, id).stream().findFirst();
    }

    public int createdSince(Instant since, String model) {
        return jdbc.queryForObject(
                "select count(*) from runs where created_at > ? and (cast(? as text) is null or model = ?)",
                Integer.class, Timestamp.from(since), model, model);
    }
}
