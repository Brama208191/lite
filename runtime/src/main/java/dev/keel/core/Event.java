package dev.keel.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Event(
        UUID runId,
        int seq,
        EventType type,
        JsonNode payload,
        long leaseToken,
        String workerId,
        Instant createdAt) {
}
