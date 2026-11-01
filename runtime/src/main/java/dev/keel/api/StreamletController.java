package dev.keel.api;

import dev.keel.streamlet.StreamletWatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/streamlet")
@ConditionalOnExpression("'${keel.role:all}' != 'worker'")
public class StreamletController {

    public record SimulateSpike(@Min(0) @Max(9) int symbol, @Min(-2000) @Max(2000) int changeBps) {
    }

    private final StreamletWatcher watcher;

    public StreamletController(StreamletWatcher watcher) {
        this.watcher = watcher;
    }

    @GetMapping
    public StreamletWatcher.Status status() {
        return watcher.status();
    }

    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulate(@Valid @RequestBody SimulateSpike request) {
        return switch (watcher.simulateSpike(request.symbol(), request.changeBps())) {
            case StreamletWatcher.Outcome.Started started -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("id", started.runId(), "task", started.alert().task()));
            case StreamletWatcher.Outcome.Skipped skipped -> ResponseEntity.unprocessableEntity()
                    .body(Map.of("message", skipped.reason()));
        };
    }
}
