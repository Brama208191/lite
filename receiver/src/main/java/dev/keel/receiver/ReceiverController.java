package dev.keel.receiver;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/v1")
public class ReceiverController {

    public record EmailRequest(@NotBlank @Email String to, @NotBlank @Size(max = 300) String subject,
                               @NotNull @Size(max = 10000) String body) {
    }

    public record ChargeRequest(@NotBlank String customer,
                                @JsonProperty("amount_cents") @NotNull @Min(50) @Max(1_000_000) Long amountCents,
                                @NotBlank @Pattern(regexp = "[a-z]{3}") String currency,
                                @NotBlank @Size(max = 200) String reference) {
    }

    public record TradeRequest(@NotBlank @Pattern(regexp = "[A-Z]{2,6}/USD") String symbol,
                               @NotBlank @Pattern(regexp = "buy|sell") String side,
                               @JsonProperty("notional_cents") @NotNull @Min(100) @Max(1_000_000) Long notionalCents,
                               @NotBlank @Size(max = 200) String reference) {
    }

    public record IncidentRequest(@NotBlank @Pattern(regexp = "critical|warning|info") String severity,
                                  @NotBlank @Size(max = 300) String summary,
                                  @NotBlank @Size(max = 200) String reference) {
    }

    private final EffectLedger ledger;
    private final LedgerQueries queries;
    private final ReceiverProperties properties;

    public ReceiverController(EffectLedger ledger, LedgerQueries queries, ReceiverProperties properties) {
        this.ledger = ledger;
        this.queries = queries;
        this.properties = properties;
    }

    @PostMapping("/emails")
    public ResponseEntity<Object> email(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody EmailRequest request) {
        return handle("email", key, request.to() + " | " + request.subject(), request, Map.of("status", "sent", "to", request.to()));
    }

    @PostMapping("/charges")
    public ResponseEntity<Object> charge(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody ChargeRequest request) {
        return handle("charge", key, request.reference(), request, Map.of(
                "status", "succeeded",
                "amount_cents", request.amountCents(),
                "currency", request.currency(),
                "reference", request.reference()));
    }

    @PostMapping("/trades")
    public ResponseEntity<Object> trade(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody TradeRequest request) {
        return handle("trade", key, request.reference(), request, Map.of(
                "status", "filled",
                "paper", true,
                "symbol", request.symbol(),
                "side", request.side(),
                "notional_cents", request.notionalCents(),
                "reference", request.reference()));
    }

    @PostMapping("/incidents")
    public ResponseEntity<Object> incident(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @Valid @RequestBody IncidentRequest request) {
        return handle("incident", key, request.reference(), request, Map.of(
                "status", "triggered",
                "severity", request.severity(),
                "summary", request.summary(),
                "paged", request.severity().equals("info") ? "nobody, logged only" : "the on-call engineer",
                "reference", request.reference()));
    }

    @GetMapping("/effects")
    public Map<String, Object> effects(@RequestParam String keyPrefix) {
        return Map.of("effects", queries.effectsByKeyPrefix(keyPrefix));
    }

    @GetMapping("/stats")
    public LedgerQueries.Stats stats(@RequestParam(defaultValue = "") String referencePrefix) {
        return queries.stats(referencePrefix);
    }

    private ResponseEntity<Object> handle(String kind, String key, String reference, Object request, Map<String, Object> response) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required (max 200 chars)");
        }
        pause();
        var result = ledger.record(kind, key, reference, request, response);
        pause();
        if (result.outcome() == EffectLedger.Outcome.CONFLICT) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "Idempotency-Key was already used with a different request"));
        }
        if (properties.failAfterCommitRate() > 0 && ThreadLocalRandom.current().nextDouble() < properties.failAfterCommitRate()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Injected failure after commit"));
        }
        return ResponseEntity.ok()
                .header("Idempotent-Replayed", String.valueOf(result.outcome() == EffectLedger.Outcome.REPLAYED))
                .body(result.response());
    }

    private void pause() {
        long min = properties.minLatency().toMillis() / 2;
        long max = Math.max(min, properties.maxLatency().toMillis() / 2);
        long millis = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
