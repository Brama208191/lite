package dev.keel.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Block.Text.class, name = "text"),
        @JsonSubTypes.Type(value = Block.ToolUse.class, name = "tool_use")
})
public sealed interface Block {

    record Text(String text) implements Block {
    }

    record ToolUse(String id, String name, JsonNode input) implements Block {
    }
}
