package dev.keel.streamlet;

import dev.keel.store.RunStore;
import dev.keel.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"keel.role=api", "keel.streamlet.max-alerts-per-hour=4"})
class StreamletIT extends PostgresTest {

    private static final long T0 = 1_800_000_000_000L;

    @Autowired
    StreamletWatcher watcher;
    @Autowired
    RunStore runs;

    private static String window(int symbol, String label, int minute, int changeBps) {
        long start = T0 + minute * 60_000L;
        return """
                {"symbol":%d,"label":"%s","kind":"1m","start":%d,"end":%d,"open":650000000,"high":0,"low":0,
                 "close":650000000,"volume":1,"ticks":100,"mean":0,"vwap":0,"changeBps":%d}
                """.formatted(symbol, label, start, start + 60_000, changeBps);
    }

    private int streamletRuns() {
        return jdbc.queryForObject("select count(*) from runs where source = 'streamlet'", Integer.class);
    }

    @Test
    void aSpikeInTheLiveStreamStartsExactlyOneRun() {
        for (int minute = 0; minute < 5; minute++) {
            watcher.dispatch("window", window(2, "SOL/USD", minute, 9));
        }
        assertThat(streamletRuns()).isZero();

        watcher.dispatch("window", window(2, "SOL/USD", 5, -410));
        watcher.dispatch("window", window(2, "SOL/USD", 5, -410));

        assertThat(streamletRuns()).isEqualTo(1);
        var run = runs.recent(5).getFirst();
        assertThat(run.source()).isEqualTo("streamlet");
        assertThat(run.task()).startsWith("ALERT ALT-SOL-").contains("SOL/USD fell 4.10%").contains("paper hedge trade (buy SOL/USD)");
        assertThat(run.trigger().get("reason").asText()).contains("above the 1.50% alert line");
        assertThat(jdbc.queryForObject("select trigger_key from runs where id = ?", String.class, run.id()))
                .isEqualTo("streamlet:spike:2:1m:" + (T0 + 5 * 60_000L));
    }

    @Test
    void aSecondSpikeOnTheSameCoinWaitsForTheCooldown() {
        watcher.dispatch("window", window(3, "XRP/USD", 0, 200));
        watcher.dispatch("window", window(3, "XRP/USD", 3, -250));
        watcher.dispatch("window", window(4, "ADA/USD", 3, 260));

        assertThat(streamletRuns()).isEqualTo(2);
    }

    @Test
    void theSameTriggerNeverStartsTwoRunsEvenAfterARestart() {
        var first = runs.create("ALERT X: test.", "fake", 8, "streamlet", "streamlet:spike:9:1m:1", null);
        var replay = runs.create("ALERT X: test.", "fake", 8, "streamlet", "streamlet:spike:9:1m:1", null);

        assertThat(first).isPresent();
        assertThat(replay).isEmpty();
        assertThat(streamletRuns()).isEqualTo(1);
    }

    @Test
    void engineOutagesAndVerifierMismatchesBecomeIncidents() {
        watcher.dispatch("engine", "{\"event\":\"down\",\"at\":1800000123000}");
        watcher.dispatch("engine", "{\"event\":\"up\",\"at\":1800000124000,\"recoveryMs\":410}");
        watcher.dispatch("verification", "{\"checkedAt\":1800000200000,\"windowsCompared\":900,\"mismatches\":0}");
        watcher.dispatch("verification", "{\"checkedAt\":1800000230000,\"windowsCompared\":900,\"mismatches\":2}");
        watcher.dispatch("engine", "{\"event\":\"down\",\"at\":1800000123000}");

        assertThat(jdbc.queryForList("select task from runs where source = 'streamlet' order by created_at", String.class))
                .satisfiesExactly(
                        task -> assertThat(task).startsWith("ALERT INC-1800000123: the Streamlet engine went down"),
                        task -> assertThat(task).startsWith("ALERT DATA-1800000230: Streamlet's verifier found 2 of 900"));
    }

    @Test
    void testSpikesGoThroughTheSameRule() {
        var started = watcher.simulateSpike(5, 520);
        var ignored = watcher.simulateSpike(5, 40);

        assertThat(started).isInstanceOfSatisfying(StreamletWatcher.Outcome.Started.class, s ->
                assertThat(s.alert().task()).contains("rose 5.20%"));
        assertThat(ignored).isInstanceOfSatisfying(StreamletWatcher.Outcome.Skipped.class, s ->
                assertThat(s.reason()).contains("would not trigger"));
        assertThat(jdbc.queryForObject("select count(*) from runs where source = 'streamlet-test'", Integer.class)).isEqualTo(1);
    }

    @Test
    void alertsStopAtTheHourlyCap() {
        for (int symbol = 6; symbol <= 9; symbol++) {
            watcher.dispatch("window", window(symbol, "C" + symbol + "/USD", 20, 300));
        }
        watcher.dispatch("verification", "{\"checkedAt\":1800000999000,\"windowsCompared\":900,\"mismatches\":1}");

        assertThat(streamletRuns()).isEqualTo(4);
        assertThat(watcher.status().alertsLastHour()).isEqualTo(4);
    }
}
