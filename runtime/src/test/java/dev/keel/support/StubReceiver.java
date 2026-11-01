package dev.keel.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class StubReceiver implements AutoCloseable {

    public record Request(String path, String key, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> applied = new ConcurrentHashMap<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    public StubReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/", exchange -> {
            var key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestURI().getPath(), key, body));
            var response = applied.computeIfAbsent(key, k -> "{\"id\":\"fx_" + sequence.incrementAndGet() + "\"}");
            int status = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : 200;
            var bytes = (status == 200 ? response : "{\"error\":\"down\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void failNext(int count) {
        failuresLeft.set(count);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requestsFor(String keyPrefix) {
        return requests.stream().filter(r -> r.key() != null && r.key().startsWith(keyPrefix)).toList();
    }

    public long effectsFor(String keyPrefix) {
        return applied.keySet().stream().filter(k -> k.startsWith(keyPrefix)).count();
    }

    public void reset() {
        requests.clear();
        applied.clear();
        failuresLeft.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
