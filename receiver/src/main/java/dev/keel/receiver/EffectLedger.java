package dev.keel.receiver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class EffectLedger {

    public enum Outcome { APPLIED, REPLAYED, CONFLICT }

    public record Result(Outcome outcome, JsonNode response) {
    }

    private record Existing(String kind, String hash, String response) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EffectLedger(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Result record(String kind, String key, String reference, Object request, Map<String, Object> response) {
        var payload = write(request);
        var hash = sha256(payload);
        var body = new LinkedHashMap<String, Object>();
        body.put("id", idPrefix(kind) + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        body.putAll(response);
        var inserted = jdbc.query("""
                insert into effects (idem_key, kind, reference, payload, payload_hash, response)
                values (?, ?, ?, ?::jsonb, ?, ?::jsonb)
                on conflict (idem_key) do nothing
                returning response
                """, (rs, i) -> rs.getString(1), key, kind, reference, payload, hash, write(body));
        if (!inserted.isEmpty()) {
            log(key, kind, reference, Outcome.APPLIED);
            return new Result(Outcome.APPLIED, read(inserted.getFirst()));
        }
        var existing = jdbc.queryForObject("select kind, payload_hash, response from effects where idem_key = ?",
                (rs, i) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3)), key);
        if (!existing.kind().equals(kind) || !existing.hash().equals(hash)) {
            log(key, kind, reference, Outcome.CONFLICT);
            return new Result(Outcome.CONFLICT, null);
        }
        log(key, kind, reference, Outcome.REPLAYED);
        return new Result(Outcome.REPLAYED, read(existing.response()));
    }

    private void log(String key, String kind, String reference, Outcome outcome) {
        jdbc.update("insert into requests (idem_key, kind, reference, outcome) values (?, ?, ?, ?)",
                key, kind, reference, outcome.name());
    }

    private static String idPrefix(String kind) {
        return switch (kind) {
            case "charge" -> "ch_";
            case "trade" -> "tr_";
            case "incident" -> "in_";
            default -> "em_";
        };
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
