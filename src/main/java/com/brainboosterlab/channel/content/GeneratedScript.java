package com.brainboosterlab.channel.content;

public record GeneratedScript(
        String scriptText,
        String model,
        String responseId,
        Integer inputTokens,
        Integer outputTokens
) {
}
