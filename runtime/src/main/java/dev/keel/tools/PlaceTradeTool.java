package dev.keel.tools;

import dev.keel.core.Json;
import dev.keel.core.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PlaceTradeTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("place_trade",
            "Place a paper (simulated) market order with the broker. The order is filled when this returns.",
            Schemas.object(new LinkedHashMap<>(Map.of(
                    "symbol", Schemas.string("Market, e.g. BTC/USD"),
                    "side", Schemas.string("buy or sell"),
                    "notional_cents", Schemas.integer("Order size in US cents, 100 to 1000000"),
                    "reference", Schemas.string("Your alert or ticket reference")))));

    private final ReceiverClient receiver;

    public PlaceTradeTool(ReceiverClient receiver) {
        this.receiver = receiver;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Kind kind() {
        return Kind.EXTERNAL_EFFECT;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        var symbol = invocation.requireText("symbol").toUpperCase();
        if (!symbol.matches("[A-Z]{2,6}/USD")) {
            return ToolResult.error("'symbol' must look like BTC/USD");
        }
        var side = invocation.requireText("side").toLowerCase();
        if (!side.equals("buy") && !side.equals("sell")) {
            return ToolResult.error("'side' must be buy or sell");
        }
        long notional = invocation.requireLong("notional_cents");
        if (notional < 100 || notional > 1_000_000) {
            return ToolResult.error("'notional_cents' must be between 100 and 1000000");
        }
        var body = Map.of(
                "symbol", symbol,
                "side", side,
                "notional_cents", notional,
                "reference", invocation.requireText("reference"));
        return receiver.post("/v1/trades", invocation.idempotencyKey(), Json.tree(body));
    }
}
