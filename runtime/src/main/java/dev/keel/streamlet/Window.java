package dev.keel.streamlet;

import com.fasterxml.jackson.databind.JsonNode;

public record Window(int symbol, String label, String kind, long start, long end, long open, long close, int changeBps) {

    public static Window from(JsonNode node) {
        return new Window(
                node.path("symbol").asInt(),
                node.path("label").asText(),
                node.path("kind").asText(),
                node.path("start").asLong(),
                node.path("end").asLong(),
                node.path("open").asLong(),
                node.path("close").asLong(),
                node.path("changeBps").asInt());
    }
}
