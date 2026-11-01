package dev.keel.chaos;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

record ChaosConfig(
        int killTrials,
        int pauseTrials,
        int workers,
        int inflight,
        int workerConcurrency,
        Duration leaseTtl,
        Duration minGap,
        Duration maxGap,
        boolean stableKeys,
        double haltAfterEffectRate,
        boolean expectClean,
        long seed,
        String apiUrl,
        String receiverUrl,
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        Path runtimeJar,
        Path outDir,
        Map<String, String> workerEnv) {

    static ChaosConfig fromEnv(Map<String, String> env) {
        var host = env.getOrDefault("PGHOST", "localhost");
        var port = env.getOrDefault("PGPORT", "5432");
        var database = env.getOrDefault("PGDATABASE", "keel");
        var user = env.getOrDefault("PGUSER", "keel");
        var password = env.getOrDefault("PGPASSWORD", "keel");
        var receiver = env.getOrDefault("KEEL_RECEIVER_URL", "http://localhost:8090");
        var ttl = env.getOrDefault("KEEL_LEASE_TTL", "2s");
        var stableKeys = Boolean.parseBoolean(env.getOrDefault("KEEL_STABLE_KEYS", "true"));
        var haltRate = env.getOrDefault("KEEL_HALT_AFTER_EFFECT_RATE", "0.005");
        var concurrency = Integer.parseInt(env.getOrDefault("KEEL_WORKER_CONCURRENCY", "8"));
        var workerEnv = Map.ofEntries(
                Map.entry("KEEL_ROLE", "worker"),
                Map.entry("SPRING_MAIN_WEB_APPLICATION_TYPE", "none"),
                Map.entry("PGHOST", host),
                Map.entry("PGPORT", port),
                Map.entry("PGDATABASE", database),
                Map.entry("PGUSER", user),
                Map.entry("PGPASSWORD", password),
                Map.entry("KEEL_DB_POOL", "4"),
                Map.entry("KEEL_RECEIVER_URL", receiver),
                Map.entry("KEEL_LEASE_TTL", ttl),
                Map.entry("KEEL_STABLE_KEYS", String.valueOf(stableKeys)),
                Map.entry("KEEL_HALT_AFTER_EFFECT_RATE", haltRate),
                Map.entry("KEEL_WORKER_CONCURRENCY", String.valueOf(concurrency)),
                Map.entry("KEEL_FAKE_MIN_LATENCY", env.getOrDefault("KEEL_FAKE_MIN_LATENCY", "80ms")),
                Map.entry("KEEL_FAKE_MAX_LATENCY", env.getOrDefault("KEEL_FAKE_MAX_LATENCY", "250ms")),
                Map.entry("LOGGING_LEVEL_ROOT", "WARN"),
                Map.entry("LOGGING_LEVEL_KEEL", "INFO"),
                Map.entry("SPRING_MAIN_BANNER_MODE", "off"));
        return new ChaosConfig(
                Integer.parseInt(env.getOrDefault("TRIALS", "50")),
                Integer.parseInt(env.getOrDefault("PAUSE_TRIALS", "5")),
                Integer.parseInt(env.getOrDefault("WORKERS", "5")),
                Integer.parseInt(env.getOrDefault("INFLIGHT", "12")),
                concurrency,
                ttl.endsWith("ms") ? Duration.ofMillis(Long.parseLong(ttl.replace("ms", ""))) : Duration.parse("PT" + ttl.toUpperCase()),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("MIN_GAP_MS", "300"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("MAX_GAP_MS", "1500"))),
                stableKeys,
                Double.parseDouble(haltRate),
                Boolean.parseBoolean(env.getOrDefault("EXPECT_CLEAN", String.valueOf(stableKeys))),
                env.getOrDefault("SEED", "").isBlank() ? System.nanoTime() : Long.parseLong(env.get("SEED")),
                env.getOrDefault("API_URL", "http://localhost:8080"),
                receiver,
                "jdbc:postgresql://" + host + ":" + port + "/" + database,
                user,
                password,
                Path.of(env.getOrDefault("RUNTIME_JAR", "/app/keel-runtime.jar")),
                Path.of(env.getOrDefault("OUT_DIR", "/out")),
                workerEnv);
    }
}
