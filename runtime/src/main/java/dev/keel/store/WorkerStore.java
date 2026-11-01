package dev.keel.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkerStore {

    public record WorkerView(String id, Instant startedAt, Instant heartbeatAt, boolean alive, int leases) {
    }

    private final JdbcTemplate jdbc;

    public WorkerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void register(String id) {
        jdbc.update("""
                insert into workers (id) values (?)
                on conflict (id) do update set started_at = clock_timestamp(), heartbeat_at = clock_timestamp(),
                    kill_requested_at = null
                """, id);
    }

    public boolean heartbeat(String id) {
        var killRequested = jdbc.query("""
                insert into workers (id) values (?)
                on conflict (id) do update set heartbeat_at = clock_timestamp()
                returning kill_requested_at is not null
                """, (rs, i) -> rs.getBoolean(1), id);
        return !killRequested.isEmpty() && killRequested.getFirst();
    }

    public Optional<String> requestKill(UUID runId) {
        return jdbc.query("""
                update workers w set kill_requested_at = clock_timestamp()
                from leases l
                where l.run_id = ? and l.worker_id = w.id and l.expires_at > clock_timestamp()
                returning w.id
                """, (rs, i) -> rs.getString(1), runId).stream().findFirst();
    }

    public List<WorkerView> recent(Duration aliveWindow) {
        return jdbc.query("""
                select w.id, w.started_at, w.heartbeat_at,
                       w.heartbeat_at > clock_timestamp() - ? * interval '1 millisecond' as alive,
                       (select count(*) from leases l where l.worker_id = w.id and l.expires_at > clock_timestamp()) as leases
                from workers w
                where w.heartbeat_at > clock_timestamp() - interval '30 seconds'
                order by w.started_at desc
                limit 12
                """, (rs, i) -> new WorkerView(
                rs.getString("id"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant(),
                rs.getBoolean("alive"),
                rs.getInt("leases")), aliveWindow.toMillis());
    }
}
