package com.brainboosterlab.channel.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brain-booster.artwork")
public record ArtworkProperties(String mode, String model) {
}
