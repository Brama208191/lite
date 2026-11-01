package dev.keel.receiver;

import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class LedgerQueries {

    public record EffectView(String key, String kind, String reference, Instant appliedAt,
                             @JsonRawValue String response, int requests, int replays) {
    }

    public record Duplicate(String kind, String reference, int effects) {
    }

    public record Stats(int requests, int applied, int replayed, int conflicts, int effects, List<Duplicate> duplicates) {
    }

    private final JdbcTemplate jdbc;

    public LedgerQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EffectView> effectsByKeyPrefix(String prefix) {
        return jdbc.query("""
                select e.idem_key, e.kind, e.reference, e.applied_at, e.response::text,
                       (select count(*) from requests r where r.idem_key = e.idem_key) as requests,
                       (select count(*) from requests r where r.idem_key = e.idem_key and r.outcome = 'REPLAYED') as replays
                from effects e
                where e.idem_key like ? escape '\\'
                order by e.applied_at
                """, (rs, i) -> new EffectView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                rs.getString(5), rs.getInt(6), rs.getInt(7)), like(prefix));
    }

    public Stats stats(String referencePrefix) {
        var pattern = "%" + escape(referencePrefix) + "%";
        var counts = jdbc.queryForMap("""
                select count(*) as requests,
                       count(*) filter (where outcome = 'APPLIED') as applied,
                       count(*) filter (where outcome = 'REPLAYED') as replayed,
                       count(*) filter (where outcome = 'CONFLICT') as conflicts
                from requests where reference like ? escape '\\'
                """, pattern);
        int effects = jdbc.queryForObject("select count(*) from effects where reference like ? escape '\\'",
                Integer.class, pattern);
        var duplicates = jdbc.query("""
                select kind, reference, count(*) from effects
                where reference like ? escape '\\'
                group by kind, reference having count(*) > 1
                order by reference
                """, (rs, i) -> new Duplicate(rs.getString(1), rs.getString(2), rs.getInt(3)), pattern);
        return new Stats(
                ((Number) counts.get("requests")).intValue(),
                ((Number) counts.get("applied")).intValue(),
                ((Number) counts.get("replayed")).intValue(),
                ((Number) counts.get("conflicts")).intValue(),
                effects,
                duplicates);
    }

    private static String like(String prefix) {
        return escape(prefix) + "%";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
