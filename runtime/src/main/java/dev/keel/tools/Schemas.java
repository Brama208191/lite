package dev.keel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.core.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Schemas {

    private Schemas() {
    }

    static JsonNode object(Map<String, Map<String, Object>> properties) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return Json.tree(schema);
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
