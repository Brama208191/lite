package dev.keel.store;

import java.util.UUID;

public class FencedException extends RuntimeException {

    public FencedException(UUID runId, long token, String reason) {
        super("Run " + runId + " rejected write with token " + token + ": " + reason);
    }
}
