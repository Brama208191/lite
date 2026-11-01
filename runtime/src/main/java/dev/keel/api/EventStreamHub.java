package dev.keel.api;

import dev.keel.store.EventStore;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnExpression("'${keel.role:all}' != 'worker'")
public class EventStreamHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger("keel.stream");
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final class Subscriber {
        final UUID runId;
        final SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        int lastSeq;

        Subscriber(UUID runId, int lastSeq) {
            this.runId = runId;
            this.lastSeq = lastSeq;
        }

        synchronized void flush() {
            try {
                for (var event : events.loadAfter(runId, lastSeq)) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.seq()))
                            .name("event")
                            .data(event, MediaType.APPLICATION_JSON));
                    lastSeq = event.seq();
                }
            } catch (IOException | IllegalStateException e) {
                remove(this);
            } catch (RuntimeException e) {
                log.warn("keel.stream.flush.error run={} reason={}", runId, e.getMessage());
            }
        }

        synchronized void ping() {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                remove(this);
            }
        }
    }

    private final EventStore events;
    private final DataSourceProperties dataSource;
    private final ConcurrentHashMap<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread listener;
    private ScheduledExecutorService sweeper;

    public EventStreamHub(EventStore events, DataSourceProperties dataSource) {
        this.events = events;
        this.dataSource = dataSource;
    }

    public SseEmitter subscribe(UUID runId, int afterSeq) {
        var subscriber = new Subscriber(runId, afterSeq);
        subscribers.computeIfAbsent(runId, id -> new CopyOnWriteArraySet<>()).add(subscriber);
        subscriber.emitter.onCompletion(() -> remove(subscriber));
        subscriber.emitter.onTimeout(() -> remove(subscriber));
        subscriber.emitter.onError(e -> remove(subscriber));
        subscriber.flush();
        return subscriber.emitter;
    }

    public void publish(UUID runId) {
        var set = subscribers.get(runId);
        if (set != null) {
            set.forEach(Subscriber::flush);
        }
    }

    private void remove(Subscriber subscriber) {
        subscribers.computeIfPresent(subscriber.runId, (id, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    @Override
    public void start() {
        running = true;
        listener = new Thread(this::listen, "keel-listen");
        listener.setDaemon(true);
        listener.start();
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "keel-sweep"));
        sweeper.scheduleWithFixedDelay(() -> subscribers.keySet().forEach(this::publish), 2, 2, TimeUnit.SECONDS);
        sweeper.scheduleWithFixedDelay(() -> subscribers.values().forEach(set -> set.forEach(Subscriber::ping)),
                15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        subscribers.values().forEach(set -> set.forEach(s -> s.emitter.complete()));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        while (running) {
            try (Connection connection = DriverManager.getConnection(
                    dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword())) {
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN keel_events");
                }
                var pg = connection.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications == null) {
                        continue;
                    }
                    for (var notification : notifications) {
                        var parameter = notification.getParameter();
                        var runId = UUID.fromString(parameter.substring(0, parameter.indexOf(':')));
                        publish(runId);
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    log.warn("keel.stream.listen.error reason={}", e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }
    }
}
