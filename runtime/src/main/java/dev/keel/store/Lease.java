package dev.keel.store;

import java.util.UUID;

public record Lease(UUID runId, long token, String workerId) {
}
