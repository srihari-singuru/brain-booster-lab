package com.brainboosterlab.channel.studio;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.brainboosterlab.channel.PuzzlePopApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Explicit, one-time generator for reusable CTA clips. Never calls the API in a normal test run. */
class CtaAudioAssetGenerationTest {
    private static final Map<String, String> LINES = Map.of(
        "comment", "Which clue caught your eye? Tell us in the comments!",
        "like", "Solved it? Tap like and celebrate your sharp eyes!",
        "outro", "Subscribe for the next puzzle, and share this challenge with your family!",
        "intro", "Welcome to Puzzle Pop! Today, let’s solve some fun puzzles together. Look closely, think it through, and see how many clever clues you can spot. Ready? Let’s play!");

    @Test void generateOnlyWhenExplicitlyRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("puzzlepop.generateCtaAssets"), "One-time CTA generation is opt-in");
        PuzzlePopApplication.loadLocalEnvironment();
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getProperty("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required for the explicitly requested generation");

        String model = setting("OPENAI_TTS_MODEL", "gpt-4o-mini-tts");
        String voice = setting("OPENAI_TTS_VOICE", "cedar");
        SpeechAi.Profile profile = new SpeechAi.Profile(model, voice, 1.0);
        SpeechAi speaker = new SpeechAi("live", "live", model, voice, "ffmpeg");
        Path assets = Path.of("src/main/resources/cta-audio");
        Files.createDirectories(assets);
        for (var line : LINES.entrySet()) {
            Path target = assets.resolve(line.getKey() + ".wav");
            if (!Files.isRegularFile(target)) speaker.synthesizeReusableAsset(line.getValue(), target, profile,
                line.getKey().equals("intro") ? SpeechAi.introDirection() : SpeechAi.ctaDirection());
        }
        Files.writeString(assets.resolve("README.md"), """
            # Reusable Puzzle Pop voice clips

            Generated once with the OpenAI Speech API; renders reuse these local WAV assets and do not call the API.
            Profile: %s, voice %s, speed 1.0. Keep the same male family-host delivery as puzzle narration.

            - `intro.wav`: “Welcome to Puzzle Pop! Today, let’s solve some fun puzzles together. Look closely, think it through, and see how many clever clues you can spot. Ready? Let’s play!”
            - `comment.wav`: “Which clue caught your eye? Tell us in the comments!”
            - `like.wav`: “Solved it? Tap like and celebrate your sharp eyes!”
            - `outro.wav`: “Subscribe for the next puzzle, and share this challenge with your family!”

            To intentionally generate missing clips once, run only `mvn -Dtest=CtaAudioAssetGenerationTest -Dpuzzlepop.generateCtaAssets=true test`.
            """.formatted(model, voice));
    }

    private static String setting(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
