package dev.keel.support;

import dev.keel.core.ModelReply;
import dev.keel.model.ModelClient;
import dev.keel.model.ModelRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CountingModel implements ModelClient {

    private final ModelClient delegate;
    private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

    public CountingModel(ModelClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public ModelReply next(ModelRequest request) {
        calls.computeIfAbsent(request.runId(), id -> new AtomicInteger()).incrementAndGet();
        return delegate.next(request);
    }

    public int callsFor(UUID runId) {
        var count = calls.get(runId);
        return count == null ? 0 : count.get();
    }
}
