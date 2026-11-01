package dev.keel.worker;

import dev.keel.KeelProperties;
import dev.keel.core.Decider;
import dev.keel.core.Decision;
import dev.keel.core.EventType;
import dev.keel.core.Payloads;
import dev.keel.core.RunState;
import dev.keel.model.ModelException;
import dev.keel.model.ModelRegistry;
import dev.keel.model.ModelRequest;
import dev.keel.store.EventStore;
import dev.keel.store.FencedException;
import dev.keel.store.Lease;
import dev.keel.store.LeaseStore;
import dev.keel.tools.InvalidToolInput;
import dev.keel.tools.OutcomeUnknownException;
import dev.keel.tools.Tool;
import dev.keel.tools.ToolInvocation;
import dev.keel.tools.ToolRegistry;
import dev.keel.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

@Component
public class RunDriver {

    public enum Outcome { COMPLETED, FAILED, FENCED, LOST }

    private static final Logger log = LoggerFactory.getLogger("keel.driver");
    private static final int MAX_MODEL_FAILURES = 5;

    private final EventStore events;
    private final LeaseStore leases;
    private final ModelRegistry models;
    private final ToolRegistry tools;
    private final KeelProperties properties;

    public RunDriver(EventStore events, LeaseStore leases, ModelRegistry models, ToolRegistry tools, KeelProperties properties) {
        this.events = events;
        this.leases = leases;
        this.models = models;
        this.tools = tools;
        this.properties = properties;
    }

    public Outcome drive(Lease lease, BooleanSupplier stillHeld) {
        try {
            var state = reload(lease);
            state = append(state, lease, EventType.LEASE_ACQUIRED, new Payloads.LeaseAcquired(lease.workerId(), lease.token()));
            int modelFailures = 0;
            while (true) {
                if (!stillHeld.getAsBoolean()) {
                    log.warn("keel.lease.lost run={} token={}", lease.runId(), lease.token());
                    return Outcome.LOST;
                }
                var decision = Decider.decide(state);
                try {
                    switch (decision) {
                        case Decision.Done done -> {
                            leases.release(lease);
                            return state.status() == RunState.Status.COMPLETED ? Outcome.COMPLETED : Outcome.FAILED;
                        }
                        case Decision.Complete complete -> state = append(state, lease, EventType.RUN_COMPLETED,
                                new Payloads.RunCompleted(complete.answer()));
                        case Decision.Fail fail -> state = append(state, lease, EventType.RUN_FAILED,
                                new Payloads.RunFailed(fail.reason()));
                        case Decision.CallModel call -> state = callModel(state, lease, call);
                        case Decision.ExecuteTool call -> state = executeTool(state, lease, call);
                    }
                    modelFailures = 0;
                } catch (ModelException e) {
                    state = reload(lease);
                    if (!e.retryable()) {
                        state = append(state, lease, EventType.RUN_FAILED, new Payloads.RunFailed(e.getMessage()));
                    } else if (++modelFailures >= MAX_MODEL_FAILURES) {
                        state = append(state, lease, EventType.RUN_FAILED,
                                new Payloads.RunFailed("Model unavailable after " + modelFailures + " tries: " + e.getMessage()));
                    } else {
                        log.warn("keel.model.retry run={} failures={} reason={}", lease.runId(), modelFailures, e.getMessage());
                        backoff(modelFailures);
                    }
                } catch (OutcomeUnknownException e) {
                    log.warn("keel.tool.retry run={} reason={}", lease.runId(), e.getMessage());
                    state = reload(lease);
                    backoff(1);
                }
            }
        } catch (FencedException e) {
            log.warn("keel.fenced run={} token={} reason={}", lease.runId(), lease.token(), e.getMessage());
            return Outcome.FENCED;
        }
    }

    private RunState callModel(RunState state, Lease lease, Decision.CallModel call) {
        var model = models.find(state.model()).orElseThrow(() ->
                new ModelException("Model '" + state.model() + "' is not configured on this worker", false, null));
        var reply = model.next(ModelRequest.from(state, tools.specs()));
        return append(state, lease, EventType.MODEL_RESPONDED, new Payloads.ModelResponded(call.turn(), reply));
    }

    private RunState executeTool(RunState state, Lease lease, Decision.ExecuteTool call) {
        var found = tools.find(call.name());
        if (found.isEmpty()) {
            return append(state, lease, EventType.TOOL_COMPLETED,
                    new Payloads.ToolCompleted(call.toolUseId(), "Unknown tool: " + call.name(), true));
        }
        var tool = found.get();
        var key = properties.idempotency().stableKeys() ? call.idempotencyKey() : UUID.randomUUID().toString();
        state = append(state, lease, EventType.TOOL_STARTED,
                new Payloads.ToolStarted(call.toolUseId(), call.name(), key, call.attempt()));
        var invocation = new ToolInvocation(state.runId(), call.toolUseId(), call.input(), key, call.attempt());
        if (tool.kind() == Tool.Kind.TRANSACTIONAL) {
            var event = events.append(lease, state.nextSeq(), EventType.TOOL_COMPLETED,
                    db -> completed(call, guard(() -> tool.executeInTransaction(invocation, db), tool)), ttl());
            return state.apply(event);
        }
        var result = guard(() -> tool.execute(invocation), tool);
        if (tool.kind() == Tool.Kind.EXTERNAL_EFFECT) {
            maybeHaltAfterEffect(lease, key);
        }
        return append(state, lease, EventType.TOOL_COMPLETED, completed(call, result));
    }

    private static Payloads.ToolCompleted completed(Decision.ExecuteTool call, ToolResult result) {
        return new Payloads.ToolCompleted(call.toolUseId(), result.content(), result.isError());
    }

    private static ToolResult guard(java.util.function.Supplier<ToolResult> body, Tool tool) {
        try {
            return body.get();
        } catch (InvalidToolInput e) {
            return ToolResult.error("Invalid input: " + e.getMessage());
        } catch (OutcomeUnknownException e) {
            throw e;
        } catch (RuntimeException e) {
            if (tool.kind() == Tool.Kind.EXTERNAL_EFFECT) {
                throw new OutcomeUnknownException(tool.spec().name() + " failed: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    private void maybeHaltAfterEffect(Lease lease, String key) {
        double rate = properties.chaos().haltAfterEffectRate();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            log.warn("keel.halt reason=after-effect run={} key={}", lease.runId(), key);
            System.out.flush();
            Runtime.getRuntime().halt(137);
        }
    }

    private RunState reload(Lease lease) {
        return RunState.fold(lease.runId(), events.load(lease.runId()));
    }

    private RunState append(RunState state, Lease lease, EventType type, Object payload) {
        return state.apply(events.append(lease, state.nextSeq(), type, payload, ttl()));
    }

    private Duration ttl() {
        return properties.worker().leaseTtl();
    }

    private void backoff(int failures) {
        try {
            Thread.sleep(properties.worker().retryBackoff().toMillis() * failures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
