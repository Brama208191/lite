package dev.keel.tools;

import dev.keel.core.Json;
import dev.keel.core.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SendEmailTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("send_email",
            "Send one email through the outbound mail provider. The email is delivered when this returns.",
            Schemas.object(new LinkedHashMap<>(Map.of(
                    "to", Schemas.string("Recipient email address"),
                    "subject", Schemas.string("Subject line"),
                    "body", Schemas.string("Plain-text body")))));

    private final ReceiverClient receiver;

    public SendEmailTool(ReceiverClient receiver) {
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
        var to = invocation.requireText("to");
        if (!to.contains("@")) {
            return ToolResult.error("'to' must be an email address");
        }
        var body = Map.of(
                "to", to,
                "subject", invocation.requireText("subject"),
                "body", invocation.requireText("body"));
        return receiver.post("/v1/emails", invocation.idempotencyKey(), Json.tree(body));
    }
}
