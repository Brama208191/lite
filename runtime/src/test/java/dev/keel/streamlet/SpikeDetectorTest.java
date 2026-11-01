package dev.keel.streamlet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpikeDetectorTest {

    private static final SpikeDetector.Rule RULE = new SpikeDetector.Rule(5, 4.0, 100, 150);

    private static Window window(int minute, int changeBps) {
        long start = 1_800_000_000_000L + minute * 60_000L;
        return new Window(0, "BTC/USD", "1m", start, start + 60_000, 650_000_000, 650_000_000, changeBps);
    }

    private static List<Window> history(int... changes) {
        var windows = new ArrayList<Window>();
        for (int i = 0; i < changes.length; i++) {
            windows.add(window(i, changes[i]));
        }
        return windows;
    }

    @Test
    void aBigMoveAlwaysAlertsEvenWithoutHistory() {
        assertThat(SpikeDetector.evaluate(RULE, List.of(), window(0, 150))).isPresent();
        assertThat(SpikeDetector.evaluate(RULE, List.of(), window(0, -520))).hasValueSatisfying(spike ->
                assertThat(spike.reason()).contains("5.20%").contains("1.50% alert line"));
        assertThat(SpikeDetector.evaluate(RULE, List.of(), window(0, 149))).isEmpty();
    }

    @Test
    void aJumpFarAboveTheRecentAverageAlerts() {
        var calm = history(8, -12, 10, 9, -11);
        var spike = SpikeDetector.evaluate(RULE, calm, window(5, 120));
        assertThat(spike).hasValueSatisfying(s -> {
            assertThat(s.baselineBps()).isEqualTo(10.0);
            assertThat(s.reason()).contains("12.0x the average move of the previous 5 windows");
        });
    }

    @Test
    void aMoveInLineWithAlreadyVolatileWindowsDoesNot() {
        var volatileHistory = history(90, -110, 100, 95, -105);
        assertThat(SpikeDetector.evaluate(RULE, volatileHistory, window(5, 140))).isEmpty();
        assertThat(SpikeDetector.evaluate(RULE, volatileHistory, window(5, 500))).isPresent();
    }

    @Test
    void smallMovesNeverAlertHoweverCalmTheMarketWas() {
        var flat = history(1, 0, -1, 1, 0);
        assertThat(SpikeDetector.evaluate(RULE, flat, window(5, 60))).isEmpty();
    }

    @Test
    void theRelativeRuleWaitsForAFullHistory() {
        assertThat(SpikeDetector.evaluate(RULE, history(5, 5), window(2, 120))).isEmpty();
    }

    @Test
    void replayedAndOutOfOrderWindowsAreIgnored() {
        var detector = new SpikeDetector(RULE);
        assertThat(detector.offer(window(0, 300))).isPresent();
        assertThat(detector.offer(window(0, 300))).isEmpty();
        assertThat(detector.offer(window(1, 10))).isEmpty();
        assertThat(detector.offer(window(0, 900))).isEmpty();
        assertThat(detector.history(0)).hasSize(2);
    }

    @Test
    void historyKeepsOnlyTheLastWindows() {
        var detector = new SpikeDetector(RULE);
        for (int i = 0; i < 12; i++) {
            detector.offer(window(i, 5));
        }
        assertThat(detector.history(0)).hasSize(5).first().satisfies(w -> assertThat(w.start()).isEqualTo(window(7, 0).start()));
    }
}
