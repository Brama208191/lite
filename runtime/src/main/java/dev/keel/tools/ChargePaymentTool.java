package dev.keel.tools;

import dev.keel.core.Json;
import dev.keel.core.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChargePaymentTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("charge_payment",
            "Charge a customer's saved card through the payment provider. Money moves when this returns.",
            Schemas.object(new LinkedHashMap<>(Map.of(
                    "customer", Schemas.string("Customer id, e.g. cus_123"),
                    "amount_cents", Schemas.integer("Amount in the smallest currency unit, 50 to 1000000"),
                    "currency", Schemas.string("Three-letter ISO currency code, e.g. usd"),
                    "reference", Schemas.string("Your order or invoice reference")))));

    private final ReceiverClient receiver;

    public ChargePaymentTool(ReceiverClient receiver) {
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
        long amount = invocation.requireLong("amount_cents");
        if (amount < 50 || amount > 1_000_000) {
            return ToolResult.error("'amount_cents' must be between 50 and 1000000");
        }
        var currency = invocation.requireText("currency").toLowerCase();
        if (!currency.matches("[a-z]{3}")) {
            return ToolResult.error("'currency' must be a three-letter code");
        }
        var body = Map.of(
                "customer", invocation.requireText("customer"),
                "amount_cents", amount,
                "currency", currency,
                "reference", invocation.requireText("reference"));
        return receiver.post("/v1/charges", invocation.idempotencyKey(), Json.tree(body));
    }
}
