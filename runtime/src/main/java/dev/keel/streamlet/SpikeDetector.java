package dev.keel.streamlet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SpikeDetector {

    public record Rule(int history, double ratio, int minBps, int absoluteBps) {
    }

    public record Spike(Window window, double baselineBps, int previousWindows, String reason) {
    }

    private final Rule rule;
    private final Map<Integer, Deque<Window>> recent = new HashMap<>();

    public SpikeDetector(Rule rule) {
        this.rule = rule;
    }

    public Rule rule() {
        return rule;
    }

    public synchronized Optional<Spike> offer(Window window) {
        var windows = recent.computeIfAbsent(window.symbol(), s -> new ArrayDeque<>());
        if (!windows.isEmpty() && window.start() <= windows.peekLast().start()) {
            return Optional.empty();
        }
        var spike = evaluate(rule, List.copyOf(windows), window);
        windows.addLast(window);
        while (windows.size() > rule.history()) {
            windows.removeFirst();
        }
        return spike;
    }

    public synchronized List<Window> history(int symbol) {
        return List.copyOf(recent.getOrDefault(symbol, new ArrayDeque<>()));
    }

    public static Optional<Spike> evaluate(Rule rule, List<Window> previous, Window current) {
        var window = previous.size() > rule.history() ? previous.subList(previous.size() - rule.history(), previous.size()) : previous;
        int move = Math.abs(current.changeBps());
        double baseline = window.stream().mapToInt(w -> Math.abs(w.changeBps())).average().orElse(0);
        if (move >= rule.absoluteBps()) {
            return Optional.of(new Spike(current, baseline, window.size(),
                    "moved " + percent(move) + ", above the " + percent(rule.absoluteBps()) + " alert line"));
        }
        if (window.size() >= rule.history() && move >= rule.minBps() && move >= rule.ratio() * Math.max(baseline, 1)) {
            return Optional.of(new Spike(current, baseline, window.size(), String.format(
                    "moved %s, %.1fx the average move of the previous %d windows (%s)",
                    percent(move), move / Math.max(baseline, 1), window.size(), percent(baseline))));
        }
        return Optional.empty();
    }

    static String percent(double bps) {
        return String.format("%.2f%%", bps / 100);
    }
}
