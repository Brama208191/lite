package dev.keel.worker;

import dev.keel.KeelProperties;
import dev.keel.store.Lease;
import dev.keel.store.LeaseStore;
import dev.keel.store.WorkerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnExpression("'${keel.role:all}' != 'api'")
public class Worker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger("keel.worker");

    private record Held(Lease lease, AtomicBoolean lost) {
    }

    private final LeaseStore leases;
    private final WorkerStore workers;
    private final RunDriver driver;
    private final KeelProperties.Worker config;
    private final String id;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private final Semaphore permits;
    private volatile boolean running;
    private ExecutorService drivers;
    private ScheduledExecutorService heartbeat;
    private Thread poller;

    public Worker(LeaseStore leases, WorkerStore workers, RunDriver driver, KeelProperties properties) {
        this.leases = leases;
        this.workers = workers;
        this.driver = driver;
        this.config = properties.worker();
        this.id = config.id().isBlank() ? "w-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(2) : config.id();
        this.permits = new Semaphore(config.concurrency());
    }

    public String id() {
        return id;
    }

    @Override
    public void start() {
        workers.register(id);
        running = true;
        drivers = Executors.newVirtualThreadPerTaskExecutor();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "keel-heartbeat"));
        long every = config.heartbeatInterval().toMillis();
        heartbeat.scheduleWithFixedDelay(this::beat, every, every, TimeUnit.MILLISECONDS);
        poller = new Thread(this::poll, "keel-poller");
        poller.start();
        log.info("keel.worker.ready id={} concurrency={} ttl={}ms", id, config.concurrency(), config.leaseTtl().toMillis());
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        held.values().forEach(h -> {
            h.lost().set(true);
            leases.release(h.lease());
        });
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        if (drivers != null) {
            drivers.shutdownNow();
        }
        log.info("keel.worker.stopped id={} released={}", id, held.size());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        while (running) {
            try {
                if (!permits.tryAcquire(config.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    continue;
                }
                var claimed = claim();
                if (claimed == null) {
                    permits.release();
                    Thread.sleep(config.pollInterval().toMillis());
                    continue;
                }
                var entry = new Held(claimed, new AtomicBoolean(false));
                held.put(claimed.runId(), entry);
                log.info("keel.lease.acquired run={} token={}", claimed.runId(), claimed.token());
                drivers.submit(() -> run(entry));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Lease claim() {
        try {
            return leases.claim(id, config.leaseTtl()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("keel.claim.error reason={}", e.getMessage());
            return null;
        }
    }

    private void run(Held entry) {
        var runId = entry.lease().runId();
        try {
            var outcome = driver.drive(entry.lease(), () -> running && !entry.lost().get());
            log.info("keel.run.{} run={} token={}", outcome.name().toLowerCase(), runId, entry.lease().token());
        } catch (RuntimeException e) {
            log.error("keel.run.error run={} token={}", runId, entry.lease().token(), e);
        } finally {
            held.remove(runId, entry);
            permits.release();
        }
    }

    private void beat() {
        try {
            if (workers.heartbeat(id)) {
                log.warn("keel.halt reason=kill-requested worker={} leases={}", id, held.size());
                System.out.flush();
                Runtime.getRuntime().halt(137);
            }
        } catch (RuntimeException e) {
            log.warn("keel.heartbeat.error reason={}", e.getMessage());
        }
        for (var entry : held.values()) {
            try {
                if (!leases.renew(entry.lease(), config.leaseTtl())) {
                    entry.lost().set(true);
                }
            } catch (RuntimeException e) {
                log.warn("keel.renew.error run={} reason={}", entry.lease().runId(), e.getMessage());
            }
        }
    }
}
