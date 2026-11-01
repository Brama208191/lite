package dev.keel.core;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface Decision {

    record CallModel(int turn) implements Decision {
    }

    record ExecuteTool(String toolUseId, String name, JsonNode input, String idempotencyKey, int attempt) implements Decision {
    }

    record Complete(String answer) implements Decision {
    }

    record Fail(String reason) implements Decision {
    }

    record Done() implements Decision {
    }
}
