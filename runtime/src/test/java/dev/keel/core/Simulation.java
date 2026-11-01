package dev.keel.core;

import dev.keel.KeelProperties;
import dev.keel.model.FakeModel;
import dev.keel.model.ModelRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

final class Simulation {

    static final FakeModel FAKE = new FakeModel(new KeelProperties.Fake(Duration.ZERO, Duration.ZERO));

    private Simulation() {
    }

    static Event event(UUID runId, int seq, EventType type, Object payload, long token) {
        return new Event(runId, seq, type, Json.tree(payload), token, token == 0 ? null : "w-test", Instant.now());
    }

    static List<Event> start(UUID runId, String task) {
        var log = new ArrayList<Event>();
        log.add(event(runId, 1, EventType.RUN_CREATED, new Payloads.RunCreated(task, "fake", 8), 0));
        log.add(event(runId, 2, EventType.LEASE_ACQUIRED, new Payloads.LeaseAcquired("w-test", 1), 1));
        return log;
    }

    static List<Event> runToEnd(UUID runId, String task, Function<Decision.ExecuteTool, Payloads.ToolCompleted> tools) {
        var log = start(runId, task);
        var state = RunState.fold(runId, log);
        for (int guard = 0; guard < 100; guard++) {
            var produced = step(state, tools);
            if (produced.isEmpty()) {
                return log;
            }
            for (var event : produced) {
                log.add(event);
                state = state.apply(event);
            }
        }
        throw new AssertionError("Run did not finish");
    }

    static List<Event> step(RunState state, Function<Decision.ExecuteTool, Payloads.ToolCompleted> tools) {
        var runId = state.runId();
        return switch (Decider.decide(state)) {
            case Decision.Done done -> List.of();
            case Decision.CallModel call -> List.of(event(runId, state.nextSeq(), EventType.MODEL_RESPONDED,
                    new Payloads.ModelResponded(call.turn(), FAKE.next(ModelRequest.from(state, List.of()))), 1));
            case Decision.ExecuteTool call -> List.of(
                    event(runId, state.nextSeq(), EventType.TOOL_STARTED,
                            new Payloads.ToolStarted(call.toolUseId(), call.name(), call.idempotencyKey(), call.attempt()), 1),
                    event(runId, state.nextSeq() + 1, EventType.TOOL_COMPLETED, tools.apply(call), 1));
            case Decision.Complete complete -> List.of(event(runId, state.nextSeq(), EventType.RUN_COMPLETED,
                    new Payloads.RunCompleted(complete.answer()), 1));
            case Decision.Fail fail -> List.of(event(runId, state.nextSeq(), EventType.RUN_FAILED,
                    new Payloads.RunFailed(fail.reason()), 1));
        };
    }

    static Payloads.ToolCompleted ok(Decision.ExecuteTool call) {
        return new Payloads.ToolCompleted(call.toolUseId(), "{\"id\":\"x_" + call.idempotencyKey() + "\"}", false);
    }
}
