package dev.keel.model;

import dev.keel.KeelProperties;
import dev.keel.core.Block;
import dev.keel.core.Json;
import dev.keel.core.ModelReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class FakeModel implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger("keel.model");
    private static final Pattern ORDER = Pattern.compile("ORD-[A-Za-z0-9-]+");

    private final Duration minLatency;
    private final Duration maxLatency;

    public FakeModel(KeelProperties.Fake config) {
        this.minLatency = config.minLatency();
        this.maxLatency = config.maxLatency();
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public ModelReply next(ModelRequest request) {
        int turn = request.turns().size();
        log.info("keel.model.call run={} turn={}", request.runId(), turn);
        pause();
        return script(request.task(), turn, request);
    }

    static ModelReply script(String task, int turn, ModelRequest request) {
        var order = orderId(task);
        return switch (turn) {
            case 0 -> reply("tool_use",
                    new Block.Text("I'll log order " + order + " first, then confirm it and take payment."),
                    toolUse(turn, 0, "notes_append", Map.of("key", "orders", "line", order + " received")));
            case 1 -> reply("tool_use",
                    new Block.Text("Logged. Sending the confirmation and charging the customer."),
                    toolUse(turn, 0, "send_email", Map.of(
                            "to", "customer+" + order.toLowerCase() + "@example.com",
                            "subject", "Order " + order + " confirmed",
                            "body", "Thanks! Your order " + order + " is confirmed.")),
                    toolUse(turn, 1, "charge_payment", Map.of(
                            "customer", "cus_" + order.toLowerCase(),
                            "amount_cents", amountCents(order),
                            "currency", "usd",
                            "reference", order)));
            default -> reply("end_turn", new Block.Text(summary(order, request)));
        };
    }

    static String orderId(String task) {
        var matcher = ORDER.matcher(task == null ? "" : task);
        return matcher.find() ? matcher.group() : "ORD-" + Integer.toHexString(Math.abs(String.valueOf(task).hashCode()));
    }

    private static long amountCents(String order) {
        return 1000 + Math.floorMod(order.hashCode(), 9000);
    }

    private static String summary(String order, ModelRequest request) {
        var errors = request.turns().stream()
                .flatMap(t -> t.results().values().stream())
                .filter(r -> r.isError())
                .count();
        if (errors > 0) {
            return "Order " + order + " finished with " + errors + " tool error(s); see the timeline.";
        }
        return "Order " + order + " is logged, the customer is emailed and the payment is captured.";
    }

    private static ModelReply reply(String stopReason, Block... blocks) {
        return new ModelReply(List.of(blocks), stopReason, "fake-scripted-v1", 0, 0, null);
    }

    private static Block.ToolUse toolUse(int turn, int index, String name, Map<String, Object> input) {
        return new Block.ToolUse("toolu_fake_" + turn + "_" + index, name, Json.tree(input));
    }

    private void pause() {
        long min = minLatency.toMillis();
        long max = Math.max(min, maxLatency.toMillis());
        long millis = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted", true, e);
        }
    }
}
