package dev.keel.store;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.core.Event;
import dev.keel.core.EventType;
import dev.keel.core.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class EventStore {

    private static final RowMapper<Event> EVENT = (rs, i) -> new Event(
            rs.getObject("run_id", UUID.class),
            rs.getInt("seq"),
            EventType.valueOf(rs.getString("type")),
            Json.parse(rs.getString("payload")),
            rs.getLong("lease_token"),
            rs.getString("worker_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public EventStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<Event> load(UUID runId) {
        return loadAfter(runId, 0);
    }

    public List<Event> loadAfter(UUID runId, int afterSeq) {
        return jdbc.query("""
                select run_id, seq, type, payload, lease_token, worker_id, created_at
                from events where run_id = ? and seq > ? order by seq
                """, EVENT, runId, afterSeq);
    }

    public Event append(Lease lease, int seq, EventType type, Object payload, Duration ttl) {
        return append(lease, seq, type, db -> Json.tree(payload), ttl);
    }

    public Event append(Lease lease, int seq, EventType type, Function<JdbcTemplate, ?> payloadInTx, Duration ttl) {
        try {
            return tx.execute(status -> {
                var current = jdbc.query("select token from leases where run_id = ? for update",
                        (rs, i) -> rs.getLong(1), lease.runId());
                if (current.isEmpty()) {
                    throw new FencedException(lease.runId(), lease.token(), "no lease row");
                }
                if (current.getFirst() != lease.token()) {
                    throw new FencedException(lease.runId(), lease.token(), "lease now has token " + current.getFirst());
                }
                JsonNode payload = Json.tree(payloadInTx.apply(jdbc));
                var event = jdbc.queryForObject("""
                        insert into events (run_id, seq, type, payload, lease_token, worker_id)
                        values (?, ?, ?, ?::jsonb, ?, ?)
                        returning run_id, seq, type, payload, lease_token, worker_id, created_at
                        """, EVENT, lease.runId(), seq, type.name(), Json.write(payload), lease.token(), lease.workerId());
                jdbc.update("update runs set status = ?, updated_at = clock_timestamp() where id = ?",
                        statusAfter(type), lease.runId());
                jdbc.update("update leases set expires_at = clock_timestamp() + ? * interval '1 millisecond' where run_id = ?",
                        ttl.toMillis(), lease.runId());
                return event;
            });
        } catch (DuplicateKeyException e) {
            throw new FencedException(lease.runId(), lease.token(), "seq " + seq + " already written");
        }
    }

    private static String statusAfter(EventType type) {
        return switch (type) {
            case RUN_COMPLETED -> "COMPLETED";
            case RUN_FAILED -> "FAILED";
            default -> "RUNNING";
        };
    }
}
