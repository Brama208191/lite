package dev.keel.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class ChaosMain {

    record LastEvent(String type, int seq) {
    }

    record Interruption(int trial, String kind, String worker, Instant at, Map<UUID, Long> tokens, Map<UUID, LastEvent> lastEvents) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChaosConfig config;
    private final Random random;
    private final String session;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, WorkerProcess> workers = new LinkedHashMap<>();
    private final List<Interruption> interruptions = new ArrayList<>();
    private Connection db;
    private int workerSeq;
    private int orderSeq;
    private int replacedAfterHalt;

    private ChaosMain(ChaosConfig config) {
        this.config = config;
        this.random = new Random(config.seed());
        this.session = Long.toString(System.currentTimeMillis(), 36).substring(2);
    }

    public static void main(String[] args) throws Exception {
        var config = ChaosConfig.fromEnv(System.getenv());
        Files.createDirectories(config.outDir());
        System.exit(new ChaosMain(config).run());
    }

    private int run() throws Exception {
        log("session %s seed %d: %d SIGKILL + %d SIGSTOP trials, %d workers x %d runs, lease ttl %s, stable keys %s",
                session, config.seed(), config.killTrials(), config.pauseTrials(), config.workers(),
                config.workerConcurrency(), config.leaseTtl(), config.stableKeys());
        db = connect();
        waitForApi();
        for (int i = 0; i < config.workers(); i++) {
            spawn();
        }
        waitUntil(() -> workers.values().stream().allMatch(WorkerProcess::ready), Duration.ofSeconds(90), "workers to start");

        var plan = new ArrayList<String>();
        plan.addAll(Collections.nCopies(config.killTrials(), "kill"));
        plan.addAll(Collections.nCopies(config.pauseTrials(), "pause"));
        Collections.shuffle(plan, random);

        var started = Instant.now();
        for (int trial = 1; trial <= plan.size(); trial++) {
            replaceExited();
            topUp();
            sleepMillis(between(config.minGap().toMillis(), config.maxGap().toMillis()));
            var victim = chooseVictim();
            interruptions.add(plan.get(trial - 1).equals("kill") ? kill(trial, victim) : pause(trial, victim));
            if (trial % 25 == 0 || trial == plan.size()) {
                log("trial %d/%d, %d workers alive, %d halts injected", trial, plan.size(),
                        workers.values().stream().filter(WorkerProcess::alive).count(), WorkerProcess.HALTS.get());
            }
        }
        log("trials done in %ds, draining", Duration.between(started, Instant.now()).toSeconds());
        var drainDeadline = Instant.now().plus(Duration.ofMinutes(4));
        while (activeRuns() > 0 && Instant.now().isBefore(drainDeadline)) {
            replaceExited();
            sleepMillis(250);
        }
        var finished = Instant.now();
        workers.values().forEach(WorkerProcess::destroy);

        var report = new Report(config, session, db, receiverStats(), interruptions, replacedAfterHalt,
                Duration.between(started, finished)).build();
        report.write(config.outDir());
        System.out.println(report.markdown());
        return config.expectClean() && !report.clean() ? 1 : 0;
    }

    private Interruption kill(int trial, WorkerProcess victim) throws Exception {
        victim.kill9();
        var at = dbNow();
        var tokens = heldBy(victim.id);
        var interruption = new Interruption(trial, "SIGKILL", victim.id, at, tokens, lastEvents(tokens.keySet()));
        workers.remove(victim.id);
        spawn();
        return interruption;
    }

    private Interruption pause(int trial, WorkerProcess victim) throws Exception {
        victim.pause();
        var at = dbNow();
        var tokens = heldBy(victim.id);
        var interruption = new Interruption(trial, "SIGSTOP", victim.id, at, tokens, lastEvents(tokens.keySet()));
        sleepMillis(config.leaseTtl().toMillis() + 1500);
        victim.resume();
        return interruption;
    }

    private WorkerProcess chooseVictim() throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            replaceExited();
            topUp();
            long ready = workers.values().stream().filter(WorkerProcess::ready).count();
            if (ready >= 2) {
                var candidates = new ArrayList<WorkerProcess>();
                try (var statement = db.prepareStatement("""
                        select distinct l.worker_id from leases l join runs r on r.id = l.run_id
                        where l.expires_at > clock_timestamp() and r.status = 'RUNNING'
                        """); var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        var worker = workers.get(rs.getString(1));
                        if (worker != null && worker.ready()) {
                            candidates.add(worker);
                        }
                    }
                }
                if (!candidates.isEmpty()) {
                    return candidates.get(random.nextInt(candidates.size()));
                }
            }
            sleepMillis(50);
        }
        throw new IllegalStateException("No worker held a running lease for 60s");
    }

    private Map<UUID, Long> heldBy(String workerId) throws SQLException {
        var held = new LinkedHashMap<UUID, Long>();
        try (var statement = db.prepareStatement("""
                select l.run_id, l.token from leases l join runs r on r.id = l.run_id
                where l.worker_id = ? and r.status = 'RUNNING'
                """)) {
            statement.setString(1, workerId);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    held.put(rs.getObject(1, UUID.class), rs.getLong(2));
                }
            }
        }
        return held;
    }

    private Map<UUID, LastEvent> lastEvents(Iterable<UUID> runIds) throws SQLException {
        var last = new LinkedHashMap<UUID, LastEvent>();
        try (var statement = db.prepareStatement("select type, seq from events where run_id = ? order by seq desc limit 1")) {
            for (var runId : runIds) {
                statement.setObject(1, runId);
                try (var rs = statement.executeQuery()) {
                    if (rs.next()) {
                        last.put(runId, new LastEvent(rs.getString(1), rs.getInt(2)));
                    }
                }
            }
        }
        return last;
    }

    private void replaceExited() throws IOException {
        for (var worker : List.copyOf(workers.values())) {
            if (worker.exitedOnItsOwn()) {
                workers.remove(worker.id);
                replacedAfterHalt++;
                spawn();
            }
        }
    }

    private void spawn() throws IOException {
        var id = "w-" + session + "-" + (++workerSeq);
        workers.put(id, WorkerProcess.start(id, config));
    }

    private void topUp() throws Exception {
        for (long active = activeRuns(); active < config.inflight(); active++) {
            int n = ++orderSeq;
            var body = JSON.writeValueAsString(Map.of(
                    "task", "Process order ORD-" + session + "-" + n + " for customer " + n,
                    "model", "fake"));
            var response = http.send(HttpRequest.newBuilder(URI.create(config.apiUrl() + "/api/runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("Creating a run failed: " + response.statusCode() + " " + response.body());
            }
        }
    }

    private long activeRuns() throws SQLException {
        try (var statement = db.prepareStatement(
                "select count(*) from runs where status in ('PENDING', 'RUNNING') and task like ?")) {
            statement.setString(1, "Process order ORD-" + session + "-%");
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private JsonNode receiverStats() throws Exception {
        var response = http.send(HttpRequest.newBuilder(
                        URI.create(config.receiverUrl() + "/v1/stats?referencePrefix=ORD-" + session + "-")).build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    private Instant dbNow() throws SQLException {
        try (var statement = db.createStatement(); var rs = statement.executeQuery("select clock_timestamp()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    private void waitForApi() {
        waitUntil(() -> {
            try {
                return http.send(HttpRequest.newBuilder(URI.create(config.apiUrl() + "/api/info")).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, Duration.ofSeconds(120), "the API");
    }

    private Connection connect() throws SQLException {
        var deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (true) {
            try {
                return DriverManager.getConnection(config.jdbcUrl(), config.dbUser(), config.dbPassword());
            } catch (SQLException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
                sleepMillis(1000);
            }
        }
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitUntil(Check check, Duration timeout, String what) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (check.ok()) {
                    return;
                }
            } catch (Exception ignored) {
            }
            sleepMillis(250);
        }
        throw new IllegalStateException("Timed out waiting for " + what);
    }

    private long between(long min, long max) {
        return min >= max ? min : min + (long) (random.nextDouble() * (max - min));
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void log(String format, Object... args) {
        System.out.printf("[chaos] " + format + "%n", args);
    }
}
