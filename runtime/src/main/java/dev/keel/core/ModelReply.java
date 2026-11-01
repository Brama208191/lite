package dev.keel.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.Collectors;

public record ModelReply(
        List<Block> content,
        String stopReason,
        String model,
        long inputTokens,
        long outputTokens,
        JsonNode raw) {

    public ModelReply {
        content = List.copyOf(content);
    }

    public List<Block.ToolUse> toolUses() {
        return content.stream()
                .filter(Block.ToolUse.class::isInstance)
                .map(Block.ToolUse.class::cast)
                .toList();
    }

    public String text() {
        return content.stream()
                .filter(Block.Text.class::isInstance)
                .map(b -> ((Block.Text) b).text())
                .collect(Collectors.joining("\n"));
    }
}
