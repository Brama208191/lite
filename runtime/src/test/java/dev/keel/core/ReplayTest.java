package dev.keel.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayTest {

    private static final String TASK = "Process order ORD-7 for Ada";

    @Test
    void fakeRunCallsThreeToolsAndCompletes() {
        var runId = UUID.randomUUID();
        var log = Simulation.runToEnd(runId, TASK, Simulation::ok);
        var types = log.stream().map(Event::type).toList();

        assertThat(types).containsExactly(
                EventType.RUN_CREATED, EventType.LEASE_ACQUIRED,
                EventType.MODEL_RESPONDED, EventType.TOOL_STARTED, EventType.TOOL_COMPLETED,
                EventType.MODEL_RESPONDED, EventType.TOOL_STARTED, EventType.TOOL_COMPLETED,
                EventType.TOOL_STARTED, EventType.TOOL_COMPLETED,
                EventType.MODEL_RESPONDED, EventType.RUN_COMPLETED);
        var state = RunState.fold(runId, log);
        assertThat(state.status()).isEqualTo(RunState.Status.COMPLETED);
        assertThat(state.answer()).contains("ORD-7");
        assertThat(Decider.decide(state)).isInstanceOf(Decision.Done.class);
    }

    @Test
    void everyCrashPointResumesWithTheSameNextStepAndKeys() {
        var runId = UUID.randomUUID();
        var full = Simulation.runToEnd(runId, TASK, Simulation::ok);
        var keysInFullRun = startedKeys(full);

        for (int cut = 2; cut < full.size(); cut++) {
            var prefix = new ArrayList<>(full.subList(0, cut));
            var resumed = RunState.fold(runId, prefix);
            var decision = Decider.decide(resumed);
            var nextType = full.get(cut).type();
            switch (decision) {
                case Decision.CallModel call -> {
                    assertThat(nextType).isEqualTo(EventType.MODEL_RESPONDED);
                    assertThat(call.turn()).isEqualTo(resumed.turns().size());
                }
                case Decision.ExecuteTool call -> {
                    var completedNext = nextType == EventType.TOOL_COMPLETED;
                    assertThat(nextType).isIn(EventType.TOOL_STARTED, EventType.TOOL_COMPLETED);
                    assertThat(call.attempt()).isEqualTo(completedNext ? 2 : 1);
                    assertThat(keysInFullRun).contains(call.idempotencyKey());
                }
                case Decision.Complete complete -> assertThat(nextType).isEqualTo(EventType.RUN_COMPLETED);
                default -> throw new AssertionError("Unexpected " + decision + " at cut " + cut);
            }

            var finished = finishFrom(prefix);
            assertThat(RunState.fold(runId, finished).status()).isEqualTo(RunState.Status.COMPLETED);
            assertThat(completedToolUses(finished)).isEqualTo(completedToolUses(full));
            assertThat(startedKeys(finished)).containsOnlyOnceElementsOf(keysInFullRun);
        }
    }

    @Test
    void keysAreStablePerRunTurnAndToolPosition() {
        var runId = UUID.randomUUID();
        var keys = startedKeys(Simulation.runToEnd(runId, TASK, Simulation::ok));
        assertThat(keys).containsExactly(runId + ":0:0", runId + ":1:0", runId + ":1:1");
    }

    @Test
    void toolThatNeverCompletesFailsTheRunAfterFiveAttempts() {
        var runId = UUID.randomUUID();
        var log = Simulation.start(runId, TASK);
        var state = RunState.fold(runId, log);
        state = state.apply(Simulation.step(state, Simulation::ok).getFirst());
        for (int attempt = 1; attempt <= Decider.MAX_TOOL_ATTEMPTS; attempt++) {
            var decision = (Decision.ExecuteTool) Decider.decide(state);
            assertThat(decision.attempt()).isEqualTo(attempt);
            state = state.apply(Simulation.event(runId, state.nextSeq(), EventType.TOOL_STARTED,
                    new Payloads.ToolStarted(decision.toolUseId(), decision.name(), decision.idempotencyKey(), attempt), 1));
        }
        assertThat(Decider.decide(state)).isInstanceOf(Decision.Fail.class);
    }

    @Test
    void foldRejectsStaleTokensGapsAndForeignRuns() {
        var runId = UUID.randomUUID();
        var log = Simulation.start(runId, TASK);
        var state = RunState.fold(runId, log);

        var later = state.apply(Simulation.event(runId, 3, EventType.LEASE_ACQUIRED, new Payloads.LeaseAcquired("w-b", 2), 2));
        assertThatThrownBy(() -> later.apply(Simulation.event(runId, 4, EventType.RUN_FAILED, new Payloads.RunFailed("x"), 1)))
                .hasMessageContaining("stale token");
        assertThatThrownBy(() -> state.apply(Simulation.event(runId, 5, EventType.RUN_FAILED, new Payloads.RunFailed("x"), 1)))
                .hasMessageContaining("expected seq 3");
        assertThatThrownBy(() -> state.apply(Simulation.event(UUID.randomUUID(), 3, EventType.RUN_FAILED, new Payloads.RunFailed("x"), 1)))
                .hasMessageContaining("applied to");
    }

    @Test
    void refusalAndTurnLimitFailTheRun() {
        var runId = UUID.randomUUID();
        var log = new ArrayList<>(Simulation.start(runId, TASK));
        var refusal = new ModelReply(List.of(), "refusal", "m", 0, 0, null);
        log.add(Simulation.event(runId, 3, EventType.MODEL_RESPONDED, new Payloads.ModelResponded(0, refusal), 1));
        assertThat(Decider.decide(RunState.fold(runId, log))).isInstanceOf(Decision.Fail.class);

        var limited = new ArrayList<Event>();
        limited.add(Simulation.event(runId, 1, EventType.RUN_CREATED, new Payloads.RunCreated(TASK, "fake", 1), 0));
        var state = RunState.fold(runId, limited);
        state = state.apply(Simulation.step(state, Simulation::ok).getFirst());
        for (var event : Simulation.step(state, Simulation::ok)) {
            state = state.apply(event);
        }
        assertThat(Decider.decide(state)).isInstanceOf(Decision.Fail.class);
    }

    private static List<Event> finishFrom(List<Event> prefix) {
        var log = new ArrayList<>(prefix);
        var state = RunState.fold(prefix.getFirst().runId(), log);
        for (int guard = 0; guard < 100; guard++) {
            var produced = Simulation.step(state, Simulation::ok);
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

    private static List<String> startedKeys(List<Event> log) {
        return log.stream()
                .filter(e -> e.type() == EventType.TOOL_STARTED)
                .map(e -> Json.read(e.payload(), Payloads.ToolStarted.class).idempotencyKey())
                .distinct()
                .toList();
    }

    private static List<String> completedToolUses(List<Event> log) {
        return log.stream()
                .filter(e -> e.type() == EventType.TOOL_COMPLETED)
                .map(e -> Json.read(e.payload(), Payloads.ToolCompleted.class).toolUseId())
                .toList();
    }
}
