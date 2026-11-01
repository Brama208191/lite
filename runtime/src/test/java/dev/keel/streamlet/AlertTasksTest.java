package dev.keel.streamlet;

import dev.keel.KeelProperties;
import dev.keel.core.Block;
import dev.keel.core.RunState;
import dev.keel.model.FakeModel;
import dev.keel.model.ModelRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertTasksTest {

    private static final FakeModel MODEL = new FakeModel(new KeelProperties.Fake(Duration.ZERO, Duration.ZERO));

    private static SpikeDetector.Spike spike(int changeBps) {
        var window = new Window(0, "BTC/USD", "1m", 1_793_555_460_000L, 1_793_555_520_000L, 590_844_089L, 621_567_982L, changeBps);
        return new SpikeDetector.Spike(window, 9.4, 5, "moved");
    }

    @Test
    void aLargeSpikeAsksForAHedgeAgainstTheMove() {
        var alert = AlertTasks.spike(spike(520), 300, "http://streamlet", false);

        assertThat(alert.task()).startsWith("ALERT ALT-BTC-1793555460: BTC/USD rose 5.20% in the 1m window ending")
                .contains("(close $62,156.80)")
                .contains("The previous 5 windows moved 0.09% on average.")
                .endsWith("place a $500.00 paper hedge trade (sell BTC/USD).");
        assertThat(alert.triggerKey()).isEqualTo("streamlet:spike:0:1m:1793555460000");
        assertThat(alert.trigger()).containsEntry("hedge", true).containsEntry("changeBps", 520);
    }

    @Test
    void aSmallerSpikeOnlyLogsAndEmails() {
        var alert = AlertTasks.spike(spike(-180), 300, "http://streamlet", false);

        assertThat(alert.task()).contains("BTC/USD fell 1.80%").doesNotContain("hedge");
        assertThat(AlertTasks.spike(spike(-180), 300, "http://streamlet", true).triggerKey()).startsWith("streamlet:test:");
    }

    @Test
    void theScriptedModelLogsEmailsAndHedges() {
        var task = AlertTasks.spike(spike(-520), 300, "http://streamlet", false).task();
        var request = new ModelRequest(UUID.randomUUID(), task, List.of(), List.of());

        var first = MODEL.next(request).toolUses();
        assertThat(first).singleElement().satisfies(use -> {
            assertThat(use.name()).isEqualTo("notes_append");
            assertThat(use.input().get("line").asText()).startsWith("ALT-BTC-1793555460 BTC/USD fell 5.20%");
        });

        var second = scriptTurn(task, 1);
        assertThat(second).extracting(Block.ToolUse::name).containsExactly("send_email", "place_trade");
        var trade = second.get(1).input();
        assertThat(trade.get("side").asText()).isEqualTo("buy");
        assertThat(trade.get("symbol").asText()).isEqualTo("BTC/USD");
        assertThat(trade.get("notional_cents").asLong()).isEqualTo(50_000);
        assertThat(trade.get("reference").asText()).isEqualTo("ALT-BTC-1793555460");
    }

    @Test
    void incidentsGetLoggedAndEmailedWithoutATrade() {
        var task = AlertTasks.engineDown(1_793_555_502_000L, "http://streamlet").task();
        assertThat(task).startsWith("ALERT INC-1793555502: the Streamlet engine went down at");
        assertThat(scriptTurn(task, 1)).extracting(Block.ToolUse::name).containsExactly("send_email");
    }

    private static List<Block.ToolUse> scriptTurn(String task, int turn) {
        var turns = new ArrayList<RunState.Turn>();
        for (int i = 0; i < turn; i++) {
            var reply = MODEL.next(new ModelRequest(UUID.randomUUID(), task, List.copyOf(turns), List.of()));
            turns.add(new RunState.Turn(i, reply, Map.of(), Map.of()));
        }
        return MODEL.next(new ModelRequest(UUID.randomUUID(), task, List.copyOf(turns), List.of())).toolUses();
    }
}
