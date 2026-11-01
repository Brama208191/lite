package dev.keel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("keel")
public record KeelProperties(
        @DefaultValue Worker worker,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Chaos chaos,
        @DefaultValue Model model,
        @DefaultValue Receiver receiver,
        @DefaultValue Tools tools,
        @DefaultValue Api api,
        @DefaultValue Streamlet streamlet) {

    public record Streamlet(
            @DefaultValue("") String url,
            @DefaultValue("1m") String kind,
            @DefaultValue("5") int history,
            @DefaultValue("4.0") double ratio,
            @DefaultValue("100") int minBps,
            @DefaultValue("150") int absoluteBps,
            @DefaultValue("300") int tradeAboveBps,
            @DefaultValue("5m") Duration cooldown,
            @DefaultValue("20") int maxAlertsPerHour,
            @DefaultValue("fake") String model) {

        public boolean enabled() {
            return !url.isBlank();
        }
    }

    public record Worker(
            @DefaultValue("") String id,
            @DefaultValue("8") int concurrency,
            @DefaultValue("200ms") Duration pollInterval,
            @DefaultValue("3s") Duration leaseTtl,
            @DefaultValue("500ms") Duration heartbeatInterval,
            @DefaultValue("300ms") Duration retryBackoff) {
    }

    public record Idempotency(@DefaultValue("true") boolean stableKeys) {
    }

    public record Chaos(@DefaultValue("0") double haltAfterEffectRate) {
    }

    public record Model(@DefaultValue Fake fake, @DefaultValue Anthropic anthropic) {
    }

    public record Fake(@DefaultValue("50ms") Duration minLatency, @DefaultValue("200ms") Duration maxLatency) {
    }

    public record Anthropic(
            @DefaultValue("") String apiKey,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("8000") long maxTokens,
            @DefaultValue("low") String effort,
            @DefaultValue("true") boolean fallbacks) {

        public boolean enabled() {
            return !apiKey.isBlank();
        }
    }

    public record Receiver(@DefaultValue("http://localhost:8090") String url, @DefaultValue("3s") Duration timeout) {
    }

    public record Tools(@DefaultValue HttpFetch httpFetch) {
    }

    public record HttpFetch(
            @DefaultValue({"example.com", "api.github.com", "en.wikipedia.org"}) List<String> allowedHosts,
            @DefaultValue("4000") int maxChars) {
    }

    public record Api(
            @DefaultValue("8") int maxTurns,
            @DefaultValue("60") int maxRunsPerMinute,
            @DefaultValue("20") int maxAnthropicRunsPerHour) {
    }
}
