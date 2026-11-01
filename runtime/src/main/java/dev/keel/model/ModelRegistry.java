package dev.keel.model;

import dev.keel.KeelProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ModelRegistry {

    private final Map<String, ModelClient> clients = new LinkedHashMap<>();

    public ModelRegistry(KeelProperties properties) {
        register(new FakeModel(properties.model().fake()));
        if (properties.model().anthropic().enabled()) {
            register(new AnthropicModel(properties.model().anthropic()));
        }
    }

    public void register(ModelClient client) {
        clients.put(client.id(), client);
    }

    public Optional<ModelClient> find(String id) {
        return Optional.ofNullable(clients.get(id));
    }

    public boolean has(String id) {
        return clients.containsKey(id);
    }
}
