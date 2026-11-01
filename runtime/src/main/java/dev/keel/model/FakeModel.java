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
    private static final Pattern ALERT = Pattern.compile("^ALERT (\\S+): ([^.]+(?:\\.\\d+[^.]*)*)\\.");
    private static final Pattern HEDGE = Pattern.compile("place a \\$([\\d,.]+) paper hedge trade \\((buy|sell) ([A-Z]+/USD)\\)");

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
        if (task != null && task.startsWith("ALERT ")) {
            return alertScript(task, turn, request);
        }
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

    static ModelReply alertScript(String task, int turn, ModelRequest request) {
        var header = ALERT.matcher(task);
        boolean parsed = header.find();
        var ref = parsed ? header.group(1) : "ALT-" + Integer.toHexString(Math.abs(task.hashCode()));
        var headline = parsed ? header.group(2) : task;
        var hedge = HEDGE.matcher(task);
        boolean trade = hedge.find();
        boolean critical = trade || ref.contains("INC-") || ref.contains("DATA-");
        return switch (turn) {
            case 0 -> reply("tool_use",
                    new Block.Text("Alert " + ref + ": " + headline + ". I'll record it first, then page the on-call team"
                            + (trade ? " and hedge the position." : ".")),
                    toolUse(turn, 0, "notes_append", Map.of("key", "alerts", "line", ref + " " + headline)));
            case 1 -> {
                var page = toolUse(turn, 0, "page_oncall", Map.of(
                        "severity", critical ? "critical" : "warning",
                        "summary", "[" + ref + "] " + headline,
                        "reference", ref));
                if (!trade) {
                    yield reply("tool_use", new Block.Text("Recorded. Paging the on-call team."), page);
                }
                yield reply("tool_use", new Block.Text("Recorded. Paging the on-call team and placing the paper hedge."), page,
                        toolUse(turn, 1, "place_trade", Map.of(
                                "symbol", hedge.group(3),
                                "side", hedge.group(2),
                                "notional_cents", Math.round(Double.parseDouble(hedge.group(1).replace(",", "")) * 100),
                                "reference", ref)));
            }
            default -> reply("end_turn", new Block.Text(alertSummary(ref, trade, request)));
        };
    }

    private static String alertSummary(String ref, boolean trade, ModelRequest request) {
        var errors = request.turns().stream()
                .flatMap(t -> t.results().values().stream())
                .filter(r -> r.isError())
                .count();
        if (errors > 0) {
            return "Alert " + ref + " handled with " + errors + " tool error(s); see the timeline.";
        }
        return "Alert " + ref + " handled: logged in the alerts notes, on-call paged" + (trade ? ", paper hedge placed." : ".");
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
