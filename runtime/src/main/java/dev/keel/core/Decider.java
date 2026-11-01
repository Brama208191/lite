package dev.keel.core;

public final class Decider {

    public static final int MAX_TOOL_ATTEMPTS = 5;

    private Decider() {
    }

    public static Decision decide(RunState state) {
        if (state.status() == RunState.Status.NEW) {
            throw new IllegalStateException("Run " + state.runId() + " has no events");
        }
        if (state.isTerminal()) {
            return new Decision.Done();
        }
        if (state.turns().isEmpty()) {
            return new Decision.CallModel(0);
        }
        var turn = state.turns().getLast();
        var toolUses = turn.reply().toolUses();
        if (toolUses.isEmpty()) {
            return switch (turn.reply().stopReason()) {
                case "refusal" -> new Decision.Fail("The model declined the task");
                case "max_tokens" -> new Decision.Fail("The model ran out of output tokens");
                case null, default -> new Decision.Complete(turn.reply().text());
            };
        }
        for (int i = 0; i < toolUses.size(); i++) {
            var use = toolUses.get(i);
            if (turn.results().containsKey(use.id())) {
                continue;
            }
            int attempts = turn.attempts().getOrDefault(use.id(), 0);
            if (attempts >= MAX_TOOL_ATTEMPTS) {
                return new Decision.Fail("Tool " + use.name() + " did not finish after " + attempts + " attempts");
            }
            return new Decision.ExecuteTool(use.id(), use.name(), use.input(), idempotencyKey(state, turn.index(), i), attempts + 1);
        }
        if (state.turns().size() >= state.maxTurns()) {
            return new Decision.Fail("Stopped after " + state.maxTurns() + " model turns");
        }
        return new Decision.CallModel(state.turns().size());
    }

    public static String idempotencyKey(RunState state, int turn, int toolIndex) {
        return state.runId() + ":" + turn + ":" + toolIndex;
    }
}
