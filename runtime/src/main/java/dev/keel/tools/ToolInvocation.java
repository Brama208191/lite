package dev.keel.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ToolInvocation(UUID runId, String toolUseId, JsonNode input, String idempotencyKey, int attempt) {

    public String requireText(String field) {
        var node = input.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidToolInput("'" + field + "' must be a non-empty string");
        }
        return node.asText();
    }

    public long requireLong(String field) {
        var node = input.get(field);
        if (node == null || !node.canConvertToLong() || !node.isIntegralNumber()) {
            throw new InvalidToolInput("'" + field + "' must be an integer");
        }
        return node.asLong();
    }
}
