package dev.keel.streamlet;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.KeelProperties;
import dev.keel.core.Json;
import dev.keel.model.ModelRegistry;
import dev.keel.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnExpression("'${keel.role:all}' != 'worker'")
public class StreamletWatcher implements SmartLifecycle {

    public record Status(boolean enabled, String url, String kind, boolean connected, Instant lastEventAt,
                         long windowsSeen, int alertsLastHour, int maxAlertsPerHour, String lastError,
                         SpikeDetector.Rule rule, int tradeAboveBps, long cooldownSeconds, List<Symbol> symbols) {
    }

    public record Symbol(int id, String label) {
    }

    public sealed interface Outcome {
        record Started(UUID runId, AlertTasks.Alert alert) implements Outcome {
        }

        record Skipped(String reason) implements Outcome {
        }
    }

    private static final Logger log = LoggerFactory.getLogger("keel.streamlet");
    private static final String SOURCE = "streamlet";

    private final KeelProperties.Streamlet config;
    private final int maxTurns;
    private final RunStore runs;
    private final ModelRegistry models;
    private final SpikeDetector detector;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Integer, Instant> lastAlert = new ConcurrentHashMap<>();
    private final AtomicLong windowsSeen = new AtomicLong();
    private volatile List<Symbol> symbols = List.of();
    private volatile boolean running;
    private volatile boolean connected;
    private volatile Instant lastEventAt;
    private volatile String lastError;
    private Thread thread;

    public StreamletWatcher(KeelProperties properties, RunStore runs, ModelRegistry models) {
        this.config = properties.streamlet();
        this.maxTurns = properties.api().maxTurns();
        this.runs = runs;
        this.models = models;
        this.detector = new SpikeDetector(new SpikeDetector.Rule(config.history(), config.ratio(), config.minBps(), config.absoluteBps()));
    }

    public Status status() {
        return new Status(config.enabled(), baseUrl(), config.kind(), connected, lastEventAt, windowsSeen.get(),
                runs.createdFromSourceSince(Instant.now().minus(Duration.ofHours(1)), SOURCE), config.maxAlertsPerHour(),
                lastError, detector.rule(), config.tradeAboveBps(), config.cooldown().toSeconds(), symbols);
    }

    public Outcome simulateSpike(int symbol, int changeBps) {
        if (runs.createdFromSourceSince(Instant.now().minus(Duration.ofHours(1)), "streamlet-test") >= config.maxAlertsPerHour()) {
            return new Outcome.Skipped("Test spikes are limited to " + config.maxAlertsPerHour() + " an hour");
        }
        var history = detector.history(symbol);
        var label = history.isEmpty()
                ? symbols.stream().filter(s -> s.id() == symbol).map(Symbol::label).findFirst().orElse("BTC/USD")
                : history.getLast().label();
        long open = history.isEmpty() ? 650_000_000L : history.getLast().close();
        long close = Math.round(open * (1 + changeBps / 10_000.0));
        long length = kindMillis(config.kind());
        long start = (System.currentTimeMillis() / length) * length;
        var window = new Window(symbol, label, config.kind(), start, start + length, open, close, changeBps);
        var spike = SpikeDetector.evaluate(detector.rule(), history, window);
        if (spike.isEmpty()) {
            return new Outcome.Skipped("A " + SpikeDetector.percent(Math.abs(changeBps)) + " move would not trigger the rule");
        }
        var alert = AlertTasks.spike(spike.get(), config.tradeAboveBps(), baseUrl(), true);
        return runs.create(alert.task(), model(), maxTurns, "streamlet-test", alert.triggerKey(), alert.trigger())
                .<Outcome>map(id -> new Outcome.Started(id, alert))
                .orElseGet(() -> new Outcome.Skipped("Duplicate test spike"));
    }

    Outcome onWindow(Window window) {
        windowsSeen.incrementAndGet();
        if (!window.kind().equals(config.kind())) {
            return new Outcome.Skipped("other window size");
        }
        var spike = detector.offer(window);
        if (spike.isEmpty()) {
            return new Outcome.Skipped("no spike");
        }
        var previous = lastAlert.get(window.symbol());
        var now = Instant.now();
        if (previous != null && previous.plus(config.cooldown()).isAfter(now)) {
            log.info("keel.streamlet.cooldown symbol={} start={}", window.label(), window.start());
            return new Outcome.Skipped("cooldown");
        }
        var outcome = launch(AlertTasks.spike(spike.get(), config.tradeAboveBps(), baseUrl(), false));
        if (outcome instanceof Outcome.Started) {
            lastAlert.put(window.symbol(), now);
        }
        return outcome;
    }

    Outcome onEngine(JsonNode event) {
        if (!"down".equals(event.path("event").asText())) {
            return new Outcome.Skipped("engine is up");
        }
        return launch(AlertTasks.engineDown(event.path("at").asLong(System.currentTimeMillis()), baseUrl()));
    }

    Outcome onVerification(JsonNode report) {
        long mismatches = report.path("mismatches").asLong();
        if (mismatches <= 0) {
            return new Outcome.Skipped("verification clean");
        }
        return launch(AlertTasks.mismatch(report.path("checkedAt").asLong(), mismatches,
                report.path("windowsCompared").asLong(), baseUrl()));
    }

    private Outcome launch(AlertTasks.Alert alert) {
        if (runs.createdFromSourceSince(Instant.now().minus(Duration.ofHours(1)), SOURCE) >= config.maxAlertsPerHour()) {
            log.warn("keel.streamlet.capped ref={}", alert.trigger().get("ref"));
            return new Outcome.Skipped("hourly alert cap reached");
        }
        var created = runs.create(alert.task(), model(), maxTurns, SOURCE, alert.triggerKey(), alert.trigger());
        if (created.isEmpty()) {
            return new Outcome.Skipped("already started for " + alert.triggerKey());
        }
        log.info("keel.streamlet.alert run={} key={}", created.get(), alert.triggerKey());
        return new Outcome.Started(created.get(), alert);
    }

    private String model() {
        return models.has(config.model()) ? config.model() : "fake";
    }

    @Override
    public void start() {
        running = true;
        if (!config.enabled()) {
            return;
        }
        thread = new Thread(this::loop, "keel-streamlet");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        long backoff = 1_000;
        while (running) {
            try {
                seed();
                listen();
                backoff = 1_000;
            } catch (IOException | RuntimeException e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("keel.streamlet.disconnected reason={}", lastError);
            } catch (InterruptedException e) {
                return;
            } finally {
                connected = false;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void seed() throws IOException, InterruptedException {
        var listed = new ArrayList<Symbol>();
        for (var node : get("/api/symbols").path("symbols")) {
            listed.add(new Symbol(node.path("id").asInt(), node.path("label").asText()));
        }
        symbols = List.copyOf(listed);
        for (var symbol : listed) {
            var rows = new ArrayList<Window>();
            for (var node : get("/api/windows?symbol=" + symbol.id() + "&kind=" + config.kind() + "&limit=" + config.history())) {
                rows.add(Window.from(node));
            }
            rows.sort((a, b) -> Long.compare(a.start(), b.start()));
            rows.forEach(detector::offer);
        }
    }

    private void listen() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Streamlet answered " + response.statusCode());
        }
        connected = true;
        lastError = null;
        log.info("keel.streamlet.connected url={} kind={}", baseUrl(), config.kind());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String name = "message";
            var data = new StringBuilder();
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        dispatch(name, data.toString());
                    }
                    name = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
        }
        throw new IOException("Streamlet closed the stream");
    }

    void dispatch(String name, String data) {
        lastEventAt = Instant.now();
        try {
            var node = Json.parse(data);
            switch (name) {
                case "window" -> onWindow(Window.from(node));
                case "engine" -> onEngine(node);
                case "verification" -> onVerification(node);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("keel.streamlet.event.error name={} reason={}", name, e.getMessage());
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(path + " answered " + response.statusCode());
        }
        return Json.parse(response.body());
    }

    private String baseUrl() {
        return config.url().replaceAll("/+$", "");
    }

    static long kindMillis(String kind) {
        return switch (kind) {
            case "5s" -> 5_000L;
            case "1h" -> 3_600_000L;
            case "1d" -> 86_400_000L;
            default -> 60_000L;
        };
    }
}
