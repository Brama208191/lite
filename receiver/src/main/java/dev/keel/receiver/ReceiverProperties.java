package dev.keel.receiver;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("receiver")
public record ReceiverProperties(
        @DefaultValue("20ms") Duration minLatency,
        @DefaultValue("120ms") Duration maxLatency,
        @DefaultValue("0") double failAfterCommitRate) {
}
