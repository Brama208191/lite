package dev.keel.model;

import dev.keel.core.RunState;
import dev.keel.core.ToolSpec;

import java.util.List;
import java.util.UUID;

public record ModelRequest(UUID runId, String task, List<RunState.Turn> turns, List<ToolSpec> tools) {

    public static ModelRequest from(RunState state, List<ToolSpec> tools) {
        return new ModelRequest(state.runId(), state.task(), state.turns(), tools);
    }
}
