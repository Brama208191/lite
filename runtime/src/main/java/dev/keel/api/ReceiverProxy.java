package dev.keel.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.KeelProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
@ConditionalOnExpression("'${keel.role:all}' != 'worker'")
public class ReceiverProxy {

    private final RestClient client;

    public ReceiverProxy(KeelProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.receiver().timeout());
        factory.setReadTimeout(properties.receiver().timeout());
        this.client = RestClient.builder()
                .baseUrl(properties.receiver().url())
                .requestFactory(factory)
                .build();
    }

    public JsonNode effectsFor(UUID runId) {
        try {
            return client.get()
                    .uri(uri -> uri.path("/v1/effects").queryParam("keyPrefix", runId + ":").build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receiver unavailable");
        }
    }
}
