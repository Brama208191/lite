package dev.keel.core;

public final class Payloads {

    private Payloads() {
    }

    public record RunCreated(String task, String model, int maxTurns) {
    }

    public record LeaseAcquired(String workerId, long token) {
    }

    public record ModelResponded(int turn, ModelReply reply) {
    }

    public record ToolStarted(String toolUseId, String name, String idempotencyKey, int attempt) {
    }

    public record ToolCompleted(String toolUseId, String content, boolean isError) {
    }

    public record RunCompleted(String answer) {
    }

    public record RunFailed(String reason) {
    }
}
