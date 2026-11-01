package dev.keel.api;

import dev.keel.KeelProperties;
import dev.keel.core.Event;
import dev.keel.model.ModelRegistry;
import dev.keel.store.EventStore;
import dev.keel.store.RunStore;
import dev.keel.store.WorkerStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@ConditionalOnExpression("'${keel.role:all}' != 'worker'")
public class RunController {

    public record CreateRun(@NotBlank @Size(max = 2000) String task, @Pattern(regexp = "fake|anthropic") String model) {
    }

    public record RunDetail(RunStore.RunSummary run, List<Event> events) {
    }

    private final RunStore runs;
    private final EventStore events;
    private final WorkerStore workers;
    private final EventStreamHub hub;
    private final ModelRegistry models;
    private final ReceiverProxy receiver;
    private final KeelProperties properties;

    public RunController(RunStore runs, EventStore events, WorkerStore workers, EventStreamHub hub,
                         ModelRegistry models, ReceiverProxy receiver, KeelProperties properties) {
        this.runs = runs;
        this.events = events;
        this.workers = workers;
        this.hub = hub;
        this.models = models;
        this.receiver = receiver;
        this.properties = properties;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "models", models.has("anthropic") ? List.of("fake", "anthropic") : List.of("fake"),
                "anthropicModel", properties.model().anthropic().model(),
                "leaseTtlMs", properties.worker().leaseTtl().toMillis(),
                "stableKeys", properties.idempotency().stableKeys());
    }

    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRun request) {
        var model = request.model() == null ? "fake" : request.model();
        if (!models.has(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model '" + model + "' is not configured");
        }
        var api = properties.api();
        if (runs.createdSince(Instant.now().minus(Duration.ofMinutes(1)), null) >= api.maxRunsPerMinute()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many runs this minute");
        }
        if (model.equals("anthropic")
                && runs.createdSince(Instant.now().minus(Duration.ofHours(1)), model) >= api.maxAnthropicRunsPerHour()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Claude runs are rate-limited on this demo");
        }
        var id = runs.create(request.task().strip(), model, api.maxTurns());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @GetMapping("/runs")
    public List<RunStore.RunSummary> recent() {
        return runs.recent(30);
    }

    @GetMapping("/runs/{id}")
    public RunDetail get(@PathVariable UUID id) {
        var run = runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new RunDetail(run, events.load(id));
    }

    @GetMapping(path = "/runs/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable UUID id,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        if (runs.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        int after = 0;
        if (lastEventId != null && lastEventId.matches("\\d{1,9}")) {
            after = Integer.parseInt(lastEventId);
        }
        return hub.subscribe(id, after);
    }

    @PostMapping("/runs/{id}/kill")
    public ResponseEntity<Map<String, Object>> kill(@PathVariable UUID id) {
        return workers.requestKill(id)
                .map(worker -> ResponseEntity.accepted().body(Map.<String, Object>of("worker", worker)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "No live worker holds this run right now")));
    }

    @GetMapping("/runs/{id}/effects")
    public Object effects(@PathVariable UUID id) {
        return receiver.effectsFor(id);
    }

    @GetMapping("/workers")
    public List<WorkerStore.WorkerView> workers() {
        return workers.recent(properties.worker().leaseTtl());
    }
}
