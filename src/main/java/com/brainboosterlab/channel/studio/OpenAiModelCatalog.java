package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Small cached projection of the account-visible /v1/models list for the local settings dropdowns. */
@Component
class OpenAiModelCatalog {
    record Catalog(List<String> text, List<String> image, List<String> speech, List<String> voices, Instant fetchedAt, boolean live) {}
    private final OpenAIClient client;
    private Catalog cached;
    private Instant expiresAt = Instant.EPOCH;

    OpenAiModelCatalog(@Value("${brain-booster.generation.mode:mock}") String generationMode,
                       @Value("${brain-booster.artwork.mode:mock}") String artworkMode) {
        client = ("live".equals(generationMode) || "live".equals(artworkMode))
            ? OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(15)).maxRetries(0).build() : null;
    }

    synchronized Catalog list() {
        if (cached != null && Instant.now().isBefore(expiresAt)) return cached;
        if (client == null) return fallback();
        try {
            var ids = client.models().list().data().stream().map(model -> model.id().trim()).distinct().sorted(Comparator.naturalOrder()).toList();
            cached = new Catalog(filter(ids, id -> id.startsWith("gpt-") && !id.contains("image") && !id.contains("audio") && !id.contains("tts") && !id.contains("realtime") && !id.contains("transcribe")),
                filter(ids, id -> id.contains("image")), filter(ids, id -> id.contains("tts")), voices(), Instant.now(), true);
            expiresAt = Instant.now().plusSeconds(300);
            return cached;
        } catch (Exception ignored) { return fallback(); }
    }
    private Catalog fallback() { return new Catalog(List.of("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"), List.of("gpt-image-2"), List.of("gpt-4o-mini-tts"), voices(), Instant.now(), false); }
    private static List<String> filter(List<String> source, java.util.function.Predicate<String> test) { return source.stream().filter(test).toList(); }
    private static List<String> voices() { return List.of("cedar", "marin", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse"); }
}
