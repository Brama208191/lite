package dev.keel.streamlet;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AlertTasks {

    public record Alert(String task, String triggerKey, Map<String, Object> trigger) {
    }

    static final long HEDGE_CENTS = 50_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private AlertTasks() {
    }

    public static Alert spike(SpikeDetector.Spike spike, int tradeAboveBps, String streamletUrl, boolean simulated) {
        var w = spike.window();
        var asset = w.label().split("/")[0];
        var ref = (simulated ? "TEST-" : "ALT-") + asset + "-" + w.start() / 1000;
        var direction = w.changeBps() >= 0 ? "rose" : "fell";
        var task = new StringBuilder()
                .append("ALERT ").append(ref).append(": ").append(w.label()).append(' ').append(direction).append(' ')
                .append(SpikeDetector.percent(Math.abs(w.changeBps()))).append(" in the ").append(w.kind())
                .append(" window ending ").append(CLOCK.format(Instant.ofEpochMilli(w.end())))
                .append(" (close $").append(price(w.close())).append("). ")
                .append(spike.previousWindows() > 0
                        ? "The previous " + spike.previousWindows() + " windows moved " + SpikeDetector.percent(spike.baselineBps()) + " on average. "
                        : "")
                .append("Investigate: log it to the alerts notes and email ops@example.com");
        boolean hedge = Math.abs(w.changeBps()) >= tradeAboveBps;
        if (hedge) {
            task.append(", then place a $").append(String.format("%,.2f", HEDGE_CENTS / 100.0)).append(" paper hedge trade (")
                    .append(w.changeBps() >= 0 ? "sell " : "buy ").append(w.label()).append(")");
        }
        task.append('.');
        var trigger = new LinkedHashMap<String, Object>();
        trigger.put("type", "spike");
        trigger.put("ref", ref);
        trigger.put("symbol", w.symbol());
        trigger.put("label", w.label());
        trigger.put("kind", w.kind());
        trigger.put("start", w.start());
        trigger.put("end", w.end());
        trigger.put("close", w.close());
        trigger.put("changeBps", w.changeBps());
        trigger.put("baselineBps", Math.round(spike.baselineBps() * 100) / 100.0);
        trigger.put("reason", spike.reason());
        trigger.put("hedge", hedge);
        trigger.put("simulated", simulated);
        trigger.put("streamletUrl", streamletUrl);
        var key = simulated
                ? "streamlet:test:" + UUID.randomUUID()
                : "streamlet:spike:" + w.symbol() + ":" + w.kind() + ":" + w.start();
        return new Alert(task.toString(), key, trigger);
    }

    public static Alert engineDown(long at, String streamletUrl) {
        var ref = "INC-" + at / 1000;
        var task = "ALERT " + ref + ": the Streamlet engine went down at " + CLOCK.format(Instant.ofEpochMilli(at))
                + ". Investigate: log it to the alerts notes and email ops@example.com.";
        return new Alert(task, "streamlet:engine-down:" + at, Map.of(
                "type", "engine-down", "ref", ref, "at", at, "simulated", false, "streamletUrl", streamletUrl));
    }

    public static Alert mismatch(long checkedAt, long mismatches, long compared, String streamletUrl) {
        var ref = "DATA-" + checkedAt / 1000;
        var task = "ALERT " + ref + ": Streamlet's verifier found " + mismatches + " of " + compared
                + " windows that disagree with the reference. Investigate: log it to the alerts notes and email ops@example.com.";
        return new Alert(task, "streamlet:mismatch:" + checkedAt, Map.of(
                "type", "mismatch", "ref", ref, "mismatches", mismatches, "simulated", false, "streamletUrl", streamletUrl));
    }

    static String price(long units) {
        double value = units / 10_000.0;
        return value >= 1 ? String.format("%,.2f", value) : String.format("%.4f", value);
    }
}
