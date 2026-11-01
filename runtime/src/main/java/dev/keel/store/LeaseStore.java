package dev.keel.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LeaseStore {

    private final JdbcTemplate jdbc;

    public LeaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Lease> claim(String workerId, Duration ttl) {
        return jdbc.query("""
                update leases l
                set token = l.token + 1,
                    worker_id = ?,
                    expires_at = clock_timestamp() + ? * interval '1 millisecond'
                where l.run_id = (
                    select c.run_id from leases c join runs r on r.id = c.run_id
                    where r.status in ('PENDING', 'RUNNING') and c.expires_at < clock_timestamp()
                    order by r.created_at
                    limit 1
                    for update of c skip locked)
                returning l.run_id, l.token
                """, (rs, i) -> new Lease(rs.getObject("run_id", UUID.class), rs.getLong("token"), workerId),
                workerId, ttl.toMillis()).stream().findFirst();
    }

    public boolean renew(Lease lease, Duration ttl) {
        return jdbc.update("""
                update leases set expires_at = clock_timestamp() + ? * interval '1 millisecond'
                where run_id = ? and token = ?
                """, ttl.toMillis(), lease.runId(), lease.token()) == 1;
    }

    public void release(Lease lease) {
        jdbc.update("update leases set worker_id = null, expires_at = '-infinity' where run_id = ? and token = ?",
                lease.runId(), lease.token());
    }

    public Optional<String> holder(UUID runId) {
        return jdbc.query("""
                select worker_id from leases
                where run_id = ? and worker_id is not null and expires_at > clock_timestamp()
                """, (rs, i) -> rs.getString(1), runId).stream().findFirst();
    }
}
