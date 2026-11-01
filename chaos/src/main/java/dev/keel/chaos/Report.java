package dev.keel.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Report {

    record Percentiles(int samples, long p50, long p95, long p99, long max) {

        static Percentiles of(List<Long> values) {
            var sorted = values.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return new Percentiles(0, 0, 0, 0, 0);
            }
            return new Percentiles(sorted.size(), rank(sorted, 50), rank(sorted, 95), rank(sorted, 99), sorted.getLast());
        }

        private static long rank(List<Long> sorted, int percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        String format() {
            return samples == 0 ? "n/a" : String.format("%.2f s / %.2f s / %.2f s (max %.2f s)",
                    p50 / 1000.0, p95 / 1000.0, p99 / 1000.0, max / 1000.0);
        }
    }

    record Result(Map<String, Object> data, List<Map<String, Object>> interruptions, String markdown, boolean clean) {

        void write(Path dir) throws IOException {
            var stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(dir.resolve("chaos-report-" + stamp + ".md"), markdown);
            Files.writeString(dir.resolve("chaos-report-" + stamp + ".json"), mapper.writeValueAsString(data));
            Files.writeString(dir.resolve("chaos-report.md"), markdown);
            Files.writeString(dir.resolve("chaos-report.json"), mapper.writeValueAsString(data));
            Files.writeString(dir.resolve("chaos-interruptions.json"), mapper.writeValueAsString(interruptions));
        }
    }

    private static final Map<String, String> IN_FLIGHT = Map.of(
            "LEASE_ACQUIRED", "rebuilding state, then a model call or a tool call",
            "MODEL_RESPONDED", "about to record a tool start",
            "TOOL_STARTED", "a tool call: the effect may have happened but is not recorded",
            "TOOL_COMPLETED", "the next tool call or model call",
            "RUN_CREATED", "nothing yet");

    private final ChaosConfig config;
    private final String session;
    private final Connection db;
    private final JsonNode receiver;
    private final List<ChaosMain.Interruption> interruptions;
    private final int replacedAfterHalt;
    private final Duration wall;
    private final String pattern;

    Report(ChaosConfig config, String session, Connection db, JsonNode receiver, List<ChaosMain.Interruption> interruptions,
           int replacedAfterHalt, Duration wall) {
        this.config = config;
        this.session = session;
        this.db = db;
        this.receiver = receiver;
        this.interruptions = interruptions;
        this.replacedAfterHalt = replacedAfterHalt;
        this.wall = wall;
        this.pattern = "Process order ORD-" + session + "-%";
    }

    Result build() throws SQLException {
        var statuses = new TreeMap<String, Long>();
        query("select status, count(*) from runs where task like ? group by status", pattern, rs -> statuses.put(rs.getString(1), rs.getLong(2)));
        long completed = statuses.getOrDefault("COMPLETED", 0L);
        long failed = statuses.getOrDefault("FAILED", 0L);
        long total = statuses.values().stream().mapToLong(Long::longValue).sum();
        long unfinished = total - completed - failed;

        var takeover = new LinkedHashMap<String, List<Long>>();
        var progress = new ArrayList<Long>();
        var phases = new TreeMap<String, Integer>();
        int interruptedRuns = 0;
        int notResumed = 0;
        int continuedByOwner = 0;
        int inFlightCommits = 0;
        int reinterrupted = 0;
        var detail = new ArrayList<Map<String, Object>>();
        for (var interruption : interruptions) {
            for (var entry : interruption.tokens().entrySet()) {
                interruptedRuns++;
                var lastEvent = interruption.lastEvents().get(entry.getKey());
                phases.merge(lastEvent == null ? "?" : lastEvent.type(), 1, Integer::sum);
                var resumedAt = first("select min(created_at) from events where run_id = ? and type = 'LEASE_ACQUIRED' and lease_token > ?",
                        entry.getKey(), entry.getValue());
                var progressedAt = first("select min(created_at) from events where run_id = ? and type <> 'LEASE_ACQUIRED' and lease_token > ?",
                        entry.getKey(), entry.getValue());
                var row = new LinkedHashMap<String, Object>();
                row.put("trial", interruption.trial());
                row.put("kind", interruption.kind());
                row.put("worker", interruption.worker());
                row.put("run", entry.getKey().toString());
                row.put("token", entry.getValue());
                row.put("at", interruption.at().toString());
                row.put("lastEvent", lastEvent == null ? null : lastEvent.type());
                row.put("resumedAt", resumedAt == null ? null : resumedAt.toString());
                detail.add(row);
                var next = nextInterruption(entry.getKey(), entry.getValue(), interruption.at());
                if (next != null && (resumedAt == null || resumedAt.isAfter(next))) {
                    reinterrupted++;
                    row.put("outcome", "re-interrupted before the new owner wrote");
                    continue;
                }
                if (resumedAt == null) {
                    long ownerWritesAfter = lastEvent == null ? 0 : countAfter(entry.getKey(), entry.getValue(), lastEvent.seq());
                    if (ownerWritesAfter > 0 && interruption.kind().equals("SIGSTOP")) {
                        continuedByOwner++;
                    } else if (ownerWritesAfter > 0) {
                        inFlightCommits++;
                    } else {
                        notResumed++;
                    }
                    continue;
                }
                takeover.computeIfAbsent(interruption.kind(), k -> new ArrayList<>())
                        .add(Duration.between(interruption.at(), resumedAt).toMillis());
                if (progressedAt != null) {
                    progress.add(Duration.between(interruption.at(), progressedAt).toMillis());
                }
            }
        }
        var allTakeovers = takeover.values().stream().flatMap(List::stream).toList();

        long staleTokenWrites = count("""
                select count(*) from (
                  select e.lease_token, max(e.lease_token) over (partition by e.run_id order by e.seq
                         rows between unbounded preceding and 1 preceding) as previous
                  from events e join runs r on r.id = e.run_id where r.task like ?) t
                where lease_token < previous
                """);
        long seqGaps = count("""
                select count(*) from (select e.run_id from events e join runs r on r.id = e.run_id where r.task like ?
                                      group by e.run_id having max(e.seq) <> count(*)) t
                """);
        long duplicateModelTurns = count("""
                select count(*) from (select e.run_id, e.payload->>'turn' from events e join runs r on r.id = e.run_id
                                      where r.task like ? and e.type = 'MODEL_RESPONDED'
                                      group by 1, 2 having count(*) > 1) t
                """);
        long duplicateToolResults = count("""
                select count(*) from (select e.run_id, e.payload->>'toolUseId' from events e join runs r on r.id = e.run_id
                                      where r.task like ? and e.type = 'TOOL_COMPLETED'
                                      group by 1, 2 having count(*) > 1) t
                """);
        long modelResponses = count("""
                select count(*) from events e join runs r on r.id = e.run_id where r.task like ? and e.type = 'MODEL_RESPONDED'
                """);
        long toolRetries = count("""
                select count(*) from events e join runs r on r.id = e.run_id
                where r.task like ? and e.type = 'TOOL_STARTED' and (e.payload->>'attempt')::int > 1
                """);
        var notePattern = "ORD-" + session + "-%";
        long noteLines = count("select count(*) from notes where line like ?", notePattern);
        long distinctNotes = count("select count(distinct line) from notes where line like ?", notePattern);
        long missingNotes = count("""
                select count(*) from runs r where r.task like ? and r.status = 'COMPLETED'
                and not exists (select 1 from notes n where n.line = substring(r.task from 'ORD-[A-Za-z0-9-]+') || ' received')
                """);

        long effects = receiver.path("effects").asLong();
        long extraEffects = 0;
        var duplicateSamples = new ArrayList<String>();
        for (var duplicate : receiver.path("duplicates")) {
            extraEffects += duplicate.path("effects").asLong() - 1;
            if (duplicateSamples.size() < 5) {
                duplicateSamples.add(duplicate.path("kind").asText() + " " + duplicate.path("reference").asText()
                        + " x" + duplicate.path("effects").asLong());
            }
        }
        long expectedEffects = 2 * completed;
        long missingEffects = Math.max(0, expectedEffects - (effects - extraEffects));
        long conflicts = receiver.path("conflicts").asLong();

        boolean clean = extraEffects == 0 && missingEffects == 0 && conflicts == 0
                && noteLines == distinctNotes && missingNotes == 0
                && unfinished == 0 && failed == 0 && notResumed == 0
                && staleTokenWrites == 0 && seqGaps == 0 && duplicateModelTurns == 0 && duplicateToolResults == 0;

        var data = new LinkedHashMap<String, Object>();
        data.put("generatedAt", Instant.now());
        data.put("session", session);
        data.put("seed", config.seed());
        data.put("config", Map.of(
                "killTrials", config.killTrials(), "pauseTrials", config.pauseTrials(), "workers", config.workers(),
                "workerConcurrency", config.workerConcurrency(), "inflight", config.inflight(),
                "leaseTtlMs", config.leaseTtl().toMillis(), "stableKeys", config.stableKeys(),
                "haltAfterEffectRate", config.haltAfterEffectRate(), "model", "fake"));
        data.put("clean", clean);
        data.put("wallSeconds", wall.toSeconds());
        data.put("runs", Map.of("total", total, "completed", completed, "failed", failed, "unfinished", unfinished));
        data.put("interruptions", Map.of(
                "sigkill", interruptions.stream().filter(i -> i.kind().equals("SIGKILL")).count(),
                "sigstop", interruptions.stream().filter(i -> i.kind().equals("SIGSTOP")).count(),
                "haltsAfterEffect", WorkerProcess.HALTS.get(),
                "workersReplacedAfterHalt", replacedAfterHalt,
                "interruptedRuns", interruptedRuns,
                "notResumed", notResumed,
                "pausedRunsContinuedByTheirOwner", continuedByOwner,
                "killedRunsFinishedByAnInFlightCommit", inFlightCommits,
                "takeoversCutShortByAnotherInterruption", reinterrupted,
                "lastEventBeforeInterruption", phases));
        data.put("takeoverMs", Map.of(
                "all", Percentiles.of(allTakeovers),
                "sigkill", Percentiles.of(takeover.getOrDefault("SIGKILL", List.of())),
                "sigstop", Percentiles.of(takeover.getOrDefault("SIGSTOP", List.of()))));
        data.put("firstProgressMs", Percentiles.of(progress));
        data.put("receiver", Map.of(
                "requests", receiver.path("requests").asLong(),
                "applied", receiver.path("applied").asLong(),
                "replayedByKey", receiver.path("replayed").asLong(),
                "keyConflicts", conflicts,
                "effects", effects,
                "expectedEffects", expectedEffects,
                "duplicateEffects", extraEffects,
                "missingEffects", missingEffects,
                "duplicateSamples", duplicateSamples));
        data.put("notes", Map.of("lines", noteLines, "duplicates", noteLines - distinctNotes, "missing", missingNotes));
        data.put("eventLog", Map.of(
                "staleTokenWrites", staleTokenWrites, "seqGaps", seqGaps,
                "duplicateModelTurns", duplicateModelTurns, "duplicateToolResults", duplicateToolResults,
                "toolReExecutions", toolRetries));
        data.put("zombies", Map.of("fencedWrites", WorkerProcess.FENCED.get(), "leasesLost", WorkerProcess.LEASES_LOST.get()));
        data.put("model", Map.of("callsMade", WorkerProcess.MODEL_CALLS.get(), "responsesRecorded", modelResponses,
                "callsLostToCrashes", Math.max(0, WorkerProcess.MODEL_CALLS.get() - modelResponses)));

        var md = new StringBuilder();
        md.append("# Keel Lite chaos report\n\n");
        md.append(clean ? "**Result: clean.** " : "**Result: NOT clean.** ")
                .append("Fake model only, no LLM calls. Session `").append(session).append("`, seed `").append(config.seed())
                .append("`, generated ").append(Instant.now()).append(".\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        row(md, "`kill -9` trials (SIGKILL on a worker holding live leases)", interruptions.stream().filter(i -> i.kind().equals("SIGKILL")).count());
        row(md, "Zombie trials (SIGSTOP past the lease TTL, then SIGCONT)", interruptions.stream().filter(i -> i.kind().equals("SIGSTOP")).count());
        row(md, "Injected halts right after an effect, before it was recorded", WorkerProcess.HALTS.get());
        row(md, "Runs interrupted mid-flight", interruptedRuns + (notResumed > 0 ? " (" + notResumed + " never resumed)" : ""));
        row(md, "Runs completed", completed + " / " + total + (failed > 0 ? " (" + failed + " failed)" : "") + (unfinished > 0 ? " (" + unfinished + " unfinished)" : ""));
        row(md, "**Duplicate side effects, counted at the receiver**", "**" + extraEffects + "**");
        row(md, "Missing side effects", missingEffects);
        row(md, "Retries absorbed by idempotency keys", receiver.path("replayed").asLong());
        row(md, "Takeover after interruption, p50 / p95 / p99", Percentiles.of(allTakeovers).format());
        row(md, "First new event after interruption, p50 / p95 / p99", Percentiles.of(progress).format());
        row(md, "Zombie writes rejected by fencing", WorkerProcess.FENCED.get());
        md.append("\n## Setup\n\n");
        md.append(String.format("%d worker JVMs x %d concurrent runs, %d runs kept in flight, lease TTL %d ms, heartbeat 500 ms, "
                        + "stable idempotency keys: %s, halt-after-effect rate %.3f. Wall time %d s.%n",
                config.workers(), config.workerConcurrency(), config.inflight(), config.leaseTtl().toMillis(),
                config.stableKeys(), config.haltAfterEffectRate(), wall.toSeconds()));
        md.append("\nEvery run logs a note (transactional tool), sends an email and charges a card (external effects). ")
                .append("The receiver is a separate service; it dedupes by `Idempotency-Key` like Stripe does and ")
                .append("counts business-level duplicates by order reference, independent of keys.\n");
        md.append("\n## Side effects at the receiver\n\n| | |\n|---|---|\n");
        row(md, "Requests received", receiver.path("requests").asLong());
        row(md, "Effects applied", effects + " (expected " + expectedEffects + ")");
        row(md, "Requests answered from the key ledger (replays)", receiver.path("replayed").asLong());
        row(md, "Key reused with a different payload", conflicts);
        row(md, "Duplicate effects (same order, more than once)", extraEffects + (duplicateSamples.isEmpty() ? "" : " e.g. " + String.join("; ", duplicateSamples)));
        row(md, "Notes lines (transactional tool) / duplicates / missing", noteLines + " / " + (noteLines - distinctNotes) + " / " + missingNotes);
        md.append("\n## Event log integrity\n\n| | |\n|---|---|\n");
        row(md, "Writes with a stale lease token", staleTokenWrites);
        row(md, "Runs with a gap in `seq`", seqGaps);
        row(md, "Model turns recorded twice", duplicateModelTurns);
        row(md, "Tool results recorded twice", duplicateToolResults);
        row(md, "Tool calls re-sent after a crash (same key)", toolRetries);
        row(md, "Model calls made / responses recorded", WorkerProcess.MODEL_CALLS.get() + " / " + modelResponses
                + " (" + Math.max(0, WorkerProcess.MODEL_CALLS.get() - modelResponses) + " lost to crashes; a resumed run reuses every recorded response)");
        md.append("\n## Where the interruptions landed\n\nLast event in the log when the worker died or froze:\n\n"
                + "| Last event | What was in flight | Runs |\n|---|---|---|\n");
        phases.forEach((phase, n) -> md.append("| ").append(phase).append(" | ")
                .append(IN_FLIGHT.getOrDefault(phase, "")).append(" | ").append(n).append(" |\n"));
        md.append("\n## Takeover time by trial type\n\n| | p50 / p95 / p99 |\n|---|---|\n");
        row(md, "SIGKILL", Percentiles.of(takeover.getOrDefault("SIGKILL", List.of())).format());
        row(md, "SIGSTOP", Percentiles.of(takeover.getOrDefault("SIGSTOP", List.of())).format());
        if (reinterrupted > 0) {
            md.append("\n").append(reinterrupted).append(" interruption(s) are left out of the takeover times because the worker ")
                    .append("that claimed the run was itself killed before it wrote anything; that run's next takeover is ")
                    .append("measured from the second interruption.\n");
        }
        if (inFlightCommits > 0) {
            md.append("\n").append(inFlightCommits).append(" killed run(s) needed no takeover: the worker's last COMMIT was ")
                    .append("already on its way to Postgres and landed after the kill, finishing the run under its own token.\n");
        }
        if (continuedByOwner > 0) {
            md.append("\n").append(continuedByOwner).append(" paused run(s) were not claimed by anyone during the pause, so ")
                    .append("their owner's lease was still current after SIGCONT and it carried on with the same token.\n");
        }
        md.append("\nTakeover is bounded by the lease TTL: a dead worker stops renewing, its lease expires, and the next ")
                .append("poll claims the run with a higher fencing token.\n");
        return new Result(data, detail, md.toString(), clean);
    }

    private static void row(StringBuilder md, String name, Object value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private interface Row {
        void accept(java.sql.ResultSet rs) throws SQLException;
    }

    private void query(String sql, String parameter, Row row) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    row.accept(rs);
                }
            }
        }
    }

    private long count(String sql) throws SQLException {
        return count(sql, pattern);
    }

    private long count(String sql, String parameter) throws SQLException {
        long[] value = {0};
        query(sql, parameter, rs -> value[0] = rs.getLong(1));
        return value[0];
    }

    private Instant nextInterruption(Object runId, long token, Instant after) {
        Instant next = null;
        for (var other : interruptions) {
            var otherToken = other.tokens().get(runId);
            if (otherToken != null && otherToken > token && other.at().isAfter(after) && (next == null || other.at().isBefore(next))) {
                next = other.at();
            }
        }
        return next;
    }

    private long countAfter(Object runId, long token, int seq) throws SQLException {
        try (var statement = db.prepareStatement("select count(*) from events where run_id = ? and lease_token = ? and seq > ?")) {
            statement.setObject(1, runId);
            statement.setLong(2, token);
            statement.setInt(3, seq);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Instant first(String sql, Object runId, long token) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setLong(2, token);
            try (var rs = statement.executeQuery()) {
                rs.next();
                Timestamp value = rs.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }
}
