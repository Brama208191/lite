package dev.keel.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunState(
        UUID runId,
        String task,
        String model,
        int maxTurns,
        Status status,
        int lastSeq,
        long leaseToken,
        List<Turn> turns,
        String answer,
        String failure) {

    public enum Status { NEW, RUNNING, COMPLETED, FAILED }

    public record Turn(int index, ModelReply reply, Map<String, Payloads.ToolCompleted> results, Map<String, Integer> attempts) {

        public Turn {
            results = Map.copyOf(results);
            attempts = Map.copyOf(attempts);
        }

        Turn withAttempt(String toolUseId, int attempt) {
            var next = new LinkedHashMap<>(attempts);
            next.put(toolUseId, attempt);
            return new Turn(index, reply, results, next);
        }

        Turn withResult(Payloads.ToolCompleted result) {
            var next = new LinkedHashMap<>(results);
            next.put(result.toolUseId(), result);
            return new Turn(index, reply, next, attempts);
        }
    }

    public RunState {
        turns = List.copyOf(turns);
    }

    public static RunState empty(UUID runId) {
        return new RunState(runId, null, null, 0, Status.NEW, 0, 0, List.of(), null, null);
    }

    public static RunState fold(UUID runId, List<Event> events) {
        var state = empty(runId);
        for (var event : events) {
            state = state.apply(event);
        }
        return state;
    }

    public RunState apply(Event event) {
        if (!event.runId().equals(runId)) {
            throw new IllegalStateException("Event for run " + event.runId() + " applied to " + runId);
        }
        if (event.seq() != lastSeq + 1) {
            throw new IllegalStateException("Run " + runId + " expected seq " + (lastSeq + 1) + " but got " + event.seq());
        }
        if (event.leaseToken() < leaseToken) {
            throw new IllegalStateException("Run " + runId + " seq " + event.seq() + " was written with stale token "
                    + event.leaseToken() + " after token " + leaseToken);
        }
        if (status == Status.NEW && event.type() != EventType.RUN_CREATED) {
            throw new IllegalStateException("Run " + runId + " must start with RUN_CREATED");
        }
        var seq = event.seq();
        var token = event.leaseToken();
        return switch (event.type()) {
            case RUN_CREATED -> {
                var created = Json.read(event.payload(), Payloads.RunCreated.class);
                yield new RunState(runId, created.task(), created.model(), created.maxTurns(), Status.RUNNING,
                        seq, token, List.of(), null, null);
            }
            case LEASE_ACQUIRED -> with(seq, token, turns);
            case MODEL_RESPONDED -> {
                var responded = Json.read(event.payload(), Payloads.ModelResponded.class);
                if (responded.turn() != turns.size()) {
                    throw new IllegalStateException("Run " + runId + " got turn " + responded.turn() + " but has " + turns.size());
                }
                var next = new ArrayList<>(turns);
                next.add(new Turn(responded.turn(), responded.reply(), Map.of(), Map.of()));
                yield with(seq, token, next);
            }
            case TOOL_STARTED -> {
                var started = Json.read(event.payload(), Payloads.ToolStarted.class);
                yield with(seq, token, replaceLast(lastTurn().withAttempt(started.toolUseId(), started.attempt())));
            }
            case TOOL_COMPLETED -> {
                var completed = Json.read(event.payload(), Payloads.ToolCompleted.class);
                yield with(seq, token, replaceLast(lastTurn().withResult(completed)));
            }
            case RUN_COMPLETED -> {
                var done = Json.read(event.payload(), Payloads.RunCompleted.class);
                yield new RunState(runId, task, model, maxTurns, Status.COMPLETED, seq, token, turns, done.answer(), null);
            }
            case RUN_FAILED -> {
                var failed = Json.read(event.payload(), Payloads.RunFailed.class);
                yield new RunState(runId, task, model, maxTurns, Status.FAILED, seq, token, turns, null, failed.reason());
            }
        };
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    public int nextSeq() {
        return lastSeq + 1;
    }

    private Turn lastTurn() {
        if (turns.isEmpty()) {
            throw new IllegalStateException("Run " + runId + " has a tool event before any model response");
        }
        return turns.getLast();
    }

    private List<Turn> replaceLast(Turn turn) {
        var next = new ArrayList<>(turns);
        next.set(next.size() - 1, turn);
        return next;
    }

    private RunState with(int seq, long token, List<Turn> nextTurns) {
        return new RunState(runId, task, model, maxTurns, status, seq, token, nextTurns, answer, failure);
    }
}
