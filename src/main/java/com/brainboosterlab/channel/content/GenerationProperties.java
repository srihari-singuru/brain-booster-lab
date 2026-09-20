package com.brainboosterlab.channel.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brain-booster.generation")
public record GenerationProperties(String mode, String model) {
}
