package dev.keel.store;

import dev.keel.core.EventType;
import dev.keel.core.Payloads;
import dev.keel.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "keel.role=api")
class LeaseFencingIT extends PostgresTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    @Autowired
    RunStore runs;
    @Autowired
    LeaseStore leases;
    @Autowired
    EventStore events;

    @Test
    void claimHandsOutIncreasingTokensOnlyAfterExpiry() {
        var runId = runs.create("Process order ORD-1", "fake", 8);

        var first = leases.claim("w-a", TTL).orElseThrow();
        assertThat(first.runId()).isEqualTo(runId);
        assertThat(first.token()).isEqualTo(1);
        assertThat(leases.claim("w-b", TTL)).isEmpty();

        expireLease(runId);
        var second = leases.claim("w-b", TTL).orElseThrow();
        assertThat(second.token()).isEqualTo(2);
        assertThat(leases.renew(first, TTL)).isFalse();
        assertThat(leases.renew(second, TTL)).isTrue();
    }

    @Test
    void zombieWithAStaleTokenCannotAppend() {
        var runId = runs.create("Process order ORD-2", "fake", 8);
        var zombie = leases.claim("w-a", TTL).orElseThrow();
        expireLease(runId);
        var current = leases.claim("w-b", TTL).orElseThrow();

        assertThatThrownBy(() -> events.append(zombie, 2, EventType.LEASE_ACQUIRED,
                new Payloads.LeaseAcquired("w-a", zombie.token()), TTL))
                .isInstanceOf(FencedException.class)
                .hasMessageContaining("lease now has token 2");
        var written = events.append(current, 2, EventType.LEASE_ACQUIRED,
                new Payloads.LeaseAcquired("w-b", current.token()), TTL);
        assertThat(written.leaseToken()).isEqualTo(2);
        assertThat(events.load(runId)).hasSize(2);
    }

    @Test
    void fencedTransactionalWorkNeverRuns() {
        var runId = runs.create("Process order ORD-3", "fake", 8);
        var zombie = leases.claim("w-a", TTL).orElseThrow();
        expireLease(runId);
        leases.claim("w-b", TTL).orElseThrow();

        assertThatThrownBy(() -> events.append(zombie, 2, EventType.TOOL_COMPLETED, db -> {
            db.update("insert into notes (key, line, run_id) values ('orders', 'ORD-3 zombie', ?)", runId);
            return new Payloads.ToolCompleted("t", "ok", false);
        }, TTL)).isInstanceOf(FencedException.class);
        assertThat(notesFor("ORD-3")).isZero();
    }

    @Test
    void sideWorkRollsBackWhenTheEventCannotBeWritten() {
        var runId = runs.create("Process order ORD-4", "fake", 8);
        var lease = leases.claim("w-a", TTL).orElseThrow();
        events.append(lease, 2, EventType.LEASE_ACQUIRED, new Payloads.LeaseAcquired("w-a", 1), TTL);

        assertThatThrownBy(() -> events.append(lease, 2, EventType.TOOL_COMPLETED, db -> {
            db.update("insert into notes (key, line, run_id) values ('orders', 'ORD-4 twice', ?)", runId);
            return new Payloads.ToolCompleted("t", "ok", false);
        }, TTL)).isInstanceOf(FencedException.class).hasMessageContaining("seq 2 already written");
        assertThat(notesFor("ORD-4")).isZero();
    }

    @Test
    void concurrentClaimersNeverShareARun() throws Exception {
        var created = new ArrayList<UUID>();
        for (int i = 0; i < 40; i++) {
            created.add(runs.create("Process order ORD-c" + i, "fake", 8));
        }
        var claimed = Collections.synchronizedList(new ArrayList<UUID>());
        var tasks = new ArrayList<Callable<Void>>();
        for (int w = 0; w < 8; w++) {
            var worker = "w-" + w;
            tasks.add(() -> {
                while (true) {
                    var lease = leases.claim(worker, TTL);
                    if (lease.isEmpty()) {
                        return null;
                    }
                    claimed.add(lease.get().runId());
                }
            });
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
        assertThat(claimed).hasSize(40).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void finishedRunsAreNeverClaimedAgain() {
        var runId = runs.create("Process order ORD-5", "fake", 8);
        var lease = leases.claim("w-a", TTL).orElseThrow();
        events.append(lease, 2, EventType.RUN_COMPLETED, new Payloads.RunCompleted("done"), TTL);
        leases.release(lease);

        assertThat(leases.claim("w-b", TTL)).isEmpty();
        assertThat(runs.find(runId).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(List.of(leases.holder(runId).isPresent())).containsExactly(false);
    }
}
