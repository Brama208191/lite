package dev.keel.tools;

import dev.keel.core.Json;
import dev.keel.core.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PageOncallTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("page_oncall",
            "Open an incident with the on-call pager. Critical and warning incidents page the on-call engineer when this returns.",
            Schemas.object(new LinkedHashMap<>(Map.of(
                    "severity", Schemas.string("critical, warning or info"),
                    "summary", Schemas.string("One line the on-call engineer reads on their phone"),
                    "reference", Schemas.string("Your alert or ticket reference")))));

    private final ReceiverClient receiver;

    public PageOncallTool(ReceiverClient receiver) {
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
        var severity = invocation.requireText("severity").toLowerCase();
        if (!severity.matches("critical|warning|info")) {
            return ToolResult.error("'severity' must be critical, warning or info");
        }
        var summary = invocation.requireText("summary");
        if (summary.length() > 300) {
            return ToolResult.error("'summary' is limited to 300 characters");
        }
        var body = Map.of(
                "severity", severity,
                "summary", summary,
                "reference", invocation.requireText("reference"));
        return receiver.post("/v1/incidents", invocation.idempotencyKey(), Json.tree(body));
    }
}
