package dev.keel.chaos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerProcess {

    static final AtomicInteger FENCED = new AtomicInteger();
    static final AtomicInteger LEASES_LOST = new AtomicInteger();
    static final AtomicInteger HALTS = new AtomicInteger();
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    final String id;
    private final Process process;
    private volatile boolean ready;
    private volatile boolean killedByHarness;

    private WorkerProcess(String id, Process process, Path log) {
        this.id = id;
        this.process = process;
        var reader = new Thread(() -> pump(log), "out-" + id);
        reader.setDaemon(true);
        reader.start();
    }

    static WorkerProcess start(String id, ChaosConfig config) throws IOException {
        var command = List.of("java",
                "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx192m", "-Xss512k", "-Xshare:auto",
                "-jar", config.runtimeJar().toString());
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(config.workerEnv());
        builder.environment().put("KEEL_WORKER_ID", id);
        var logs = config.outDir().resolve("logs");
        Files.createDirectories(logs);
        return new WorkerProcess(id, builder.start(), logs.resolve(id + ".log"));
    }

    long pid() {
        return process.pid();
    }

    boolean ready() {
        return ready && process.isAlive();
    }

    boolean alive() {
        return process.isAlive();
    }

    boolean exitedOnItsOwn() {
        return !process.isAlive() && !killedByHarness;
    }

    void kill9() throws IOException, InterruptedException {
        killedByHarness = true;
        signal("-9");
        process.waitFor(10, TimeUnit.SECONDS);
    }

    void pause() throws IOException, InterruptedException {
        signal("-STOP");
    }

    void resume() throws IOException, InterruptedException {
        signal("-CONT");
    }

    void destroy() {
        killedByHarness = true;
        process.destroyForcibly();
    }

    private void signal(String signal) throws IOException, InterruptedException {
        var kill = new ProcessBuilder("sh", "-c", "kill " + signal + " " + process.pid()).inheritIO().start();
        if (kill.waitFor() != 0 && process.isAlive()) {
            throw new IOException("kill " + signal + " " + process.pid() + " failed");
        }
    }

    private void pump(Path log) {
        try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var out = new PrintWriter(Files.newBufferedWriter(log), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.println(line);
                if (line.contains("keel.worker.ready")) {
                    ready = true;
                } else if (line.contains("keel.fenced")) {
                    FENCED.incrementAndGet();
                } else if (line.contains("keel.lease.lost")) {
                    LEASES_LOST.incrementAndGet();
                } else if (line.contains("keel.halt reason=after-effect")) {
                    HALTS.incrementAndGet();
                } else if (line.contains("keel.model.call")) {
                    MODEL_CALLS.incrementAndGet();
                }
            }
        } catch (IOException ignored) {
        }
    }
}
