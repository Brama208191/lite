package dev.keel.worker;

import dev.keel.KeelProperties;
import dev.keel.core.EventType;
import dev.keel.core.Json;
import dev.keel.core.Payloads;
import dev.keel.core.ToolSpec;
import dev.keel.model.FakeModel;
import dev.keel.model.ModelRegistry;
import dev.keel.store.EventStore;
import dev.keel.store.LeaseStore;
import dev.keel.store.RunStore;
import dev.keel.support.CountingModel;
import dev.keel.support.PostgresTest;
import dev.keel.tools.Tool;
import dev.keel.tools.ToolInvocation;
import dev.keel.tools.ToolRegistry;
import dev.keel.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "keel.role=api")
class CrashResumeIT extends PostgresTest {

    static final class SimulatedCrash extends Error {
    }

    private static final Duration TTL = Duration.ofSeconds(30);

    @Autowired
    RunStore runs;
    @Autowired
    LeaseStore leases;
    @Autowired
    EventStore events;
    @Autowired
    ModelRegistry models;
    @Autowired
    List<Tool> tools;
    @Autowired
    RunDriver driver;
    @Autowired
    KeelProperties properties;

    CountingModel model;

    @BeforeEach
    void countModelCalls() {
        model = new CountingModel(new FakeModel(properties.model().fake()));
        models.register(model);
    }

    @Test
    void runsToCompletionWithEachEffectDeliveredOnce() {
        var runId = runs.create("Process order ORD-100 for Ada", "fake", 8);
        var lease = leases.claim("w-a", TTL).orElseThrow();

        assertThat(driver.drive(lease, () -> true)).isEqualTo(RunDriver.Outcome.COMPLETED);

        assertThat(RECEIVER.requestsFor(runId.toString())).hasSize(2);
        assertThat(RECEIVER.effectsFor(runId.toString())).isEqualTo(2);
        assertThat(notesFor("ORD-100")).isEqualTo(1);
        assertThat(model.callsFor(runId)).isEqualTo(3);
        assertThat(leases.holder(runId)).isEmpty();
    }

    @Test
    void crashAfterTheEffectButBeforeTheRecordIsAbsorbedByTheKey() {
        var runId = runs.create("Process order ORD-101 for Ada", "fake", 8);
        var crashing = new RunDriver(events, leases, models, registry(after("send_email", () -> {
            throw new SimulatedCrash();
        })), properties);
        var first = leases.claim("w-a", TTL).orElseThrow();
        assertThatThrownBy(() -> crashing.drive(first, () -> true)).isInstanceOf(SimulatedCrash.class);
        assertThat(RECEIVER.effectsFor(runId.toString())).isEqualTo(1);

        expireLease(runId);
        var second = leases.claim("w-b", TTL).orElseThrow();
        assertThat(driver.drive(second, () -> true)).isEqualTo(RunDriver.Outcome.COMPLETED);

        var emailKey = runId + ":1:0";
        assertThat(RECEIVER.requestsFor(emailKey)).hasSize(2);
        assertThat(RECEIVER.effectsFor(runId.toString())).isEqualTo(2);
        assertThat(notesFor("ORD-101")).isEqualTo(1);
        assertThat(model.callsFor(runId)).isEqualTo(3);
        assertThat(started(runId)).extracting(Payloads.ToolStarted::idempotencyKey)
                .containsExactly(runId + ":0:0", emailKey, emailKey, runId + ":1:1");
        assertThat(started(runId)).extracting(Payloads.ToolStarted::attempt).containsExactly(1, 1, 2, 1);
    }

    @Test
    void pausedZombieIsFencedAfterAnotherWorkerTakesOver() throws Exception {
        var runId = runs.create("Process order ORD-102 for Ada", "fake", 8);
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var zombieDriver = new RunDriver(events, leases, models, registry(after("send_email", () -> {
            paused.countDown();
            await(resume);
        })), properties);
        var zombie = leases.claim("w-a", TTL).orElseThrow();
        var zombieRun = CompletableFuture.supplyAsync(() -> zombieDriver.drive(zombie, () -> true));
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();

        expireLease(runId);
        var current = leases.claim("w-b", TTL).orElseThrow();
        assertThat(driver.drive(current, () -> true)).isEqualTo(RunDriver.Outcome.COMPLETED);
        resume.countDown();

        assertThat(zombieRun.get(10, TimeUnit.SECONDS)).isEqualTo(RunDriver.Outcome.FENCED);
        assertThat(RECEIVER.requestsFor(runId + ":1:0")).hasSize(2);
        assertThat(RECEIVER.effectsFor(runId.toString())).isEqualTo(2);
        assertThat(events.load(runId)).allSatisfy(e -> assertThat(e.leaseToken()).isIn(0L, 1L, 2L))
                .filteredOn(e -> e.leaseToken() == 1).extracting(e -> e.type())
                .doesNotContain(EventType.RUN_COMPLETED);
    }

    @Test
    void unknownOutcomeIsRetriedWithTheSameKey() {
        var runId = runs.create("Process order ORD-103 for Ada", "fake", 8);
        RECEIVER.failNext(3);
        var lease = leases.claim("w-a", TTL).orElseThrow();

        assertThat(driver.drive(lease, () -> true)).isEqualTo(RunDriver.Outcome.COMPLETED);

        var emailKey = runId + ":1:0";
        assertThat(RECEIVER.requestsFor(emailKey)).hasSize(4);
        assertThat(RECEIVER.effectsFor(runId.toString())).isEqualTo(2);
        assertThat(started(runId)).filteredOn(s -> s.name().equals("send_email"))
                .extracting(Payloads.ToolStarted::attempt).containsExactly(1, 2);
    }

    @Test
    void withoutStableKeysTheSameCrashChargesTwice() {
        var unstable = new KeelProperties(properties.worker(), new KeelProperties.Idempotency(false), properties.chaos(),
                properties.model(), properties.receiver(), properties.tools(), properties.api());
        var runId = runs.create("Process order ORD-104 for Ada", "fake", 8);
        var crashing = new RunDriver(events, leases, models, registry(after("charge_payment", () -> {
            throw new SimulatedCrash();
        })), unstable);
        var first = leases.claim("w-a", TTL).orElseThrow();
        assertThatThrownBy(() -> crashing.drive(first, () -> true)).isInstanceOf(SimulatedCrash.class);

        expireLease(runId);
        var second = leases.claim("w-b", TTL).orElseThrow();
        var naive = new RunDriver(events, leases, models, registry(null), unstable);
        assertThat(naive.drive(second, () -> true)).isEqualTo(RunDriver.Outcome.COMPLETED);

        var charges = RECEIVER.requests().stream().filter(r -> r.path().equals("/v1/charges")).toList();
        assertThat(charges).hasSize(2);
        assertThat(charges.get(0).key()).isNotEqualTo(charges.get(1).key());
    }

    private List<Payloads.ToolStarted> started(UUID runId) {
        return events.load(runId).stream()
                .filter(e -> e.type() == EventType.TOOL_STARTED)
                .map(e -> Json.read(e.payload(), Payloads.ToolStarted.class))
                .toList();
    }

    private ToolRegistry registry(Tool replacement) {
        var list = new ArrayList<Tool>();
        for (var tool : tools) {
            list.add(replacement != null && replacement.spec().name().equals(tool.spec().name()) ? replacement : tool);
        }
        return new ToolRegistry(list);
    }

    private Tool after(String name, Runnable afterEffect) {
        var delegate = tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return delegate.spec();
            }

            @Override
            public Kind kind() {
                return delegate.kind();
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                var result = delegate.execute(invocation);
                afterEffect.run();
                return result;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
