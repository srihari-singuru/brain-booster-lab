package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OpenAI Speech API adapter. It stores only local WAVs and their measured durations. */
@Component
class SpeechAi {
    record Draft(EpisodeSpeech speech) {}
    record Profile(String model, String voice, double speed) {
        Profile {
            model = model == null ? "" : model.trim();
            voice = voice == null ? "" : voice.trim().toLowerCase(Locale.ROOT);
        }
        void validate() {
            EpisodeSpec.require(model.equals("gpt-4o-mini-tts") || model.equals("gpt-4o-mini-tts-2025-12-15"),
                "Speech model must support expressive TTS instructions");
            EpisodeSpec.require(VOICES.contains(voice), "Choose a supported built-in OpenAI voice");
            EpisodeSpec.require(Double.isFinite(speed) && speed >= .75 && speed <= 1.25,
                "Voice speed must be between 0.75 and 1.25");
        }
    }
    private static final Set<String> VOICES = Set.of("alloy", "ash", "ballad", "coral", "echo", "fable",
        "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar");
    // One selected speed across every phase. Preserve the model's natural cadence rather
    // than time-stretching different-length sentences into identical slots afterwards.
    private static final double STANDARD_SPEECH_SPEED = 1.0;
    private final String mode;
    private final String model;
    private final String voice;
    private final OpenAIClient client;
    private final String ffmpeg;

    SpeechAi(@Value("${brain-booster.generation.mode:mock}") String generationMode,
             @Value("${brain-booster.speech.mode:}") String speechMode,
             @Value("${brain-booster.speech.model:gpt-4o-mini-tts}") String speechModel,
             @Value("${brain-booster.speech.voice:cedar}") String speechVoice,
             @Value("${brain-booster.render.ffmpeg-path:ffmpeg}") String ffmpeg) {
        this.mode = speechMode == null || speechMode.isBlank() ? generationMode : speechMode;
        this.model = speechModel == null || speechModel.isBlank() ? "gpt-4o-mini-tts" : speechModel.trim();
        this.voice = speechVoice == null || speechVoice.isBlank() ? "cedar" : speechVoice.trim().toLowerCase(Locale.ROOT);
        this.client = "live".equals(mode) ? OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(90)).maxRetries(0).build() : null;
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
    }

    Draft speak(EpisodeSpec spec, EpisodeNarration narration, Path directory) throws Exception {
        return speak(spec, narration, directory, new Profile(model, voice, STANDARD_SPEECH_SPEED));
    }

    Draft speak(EpisodeSpec spec, EpisodeNarration narration, Path directory, Profile profile) throws Exception {
        return speak(spec, narration, directory, profile, "");
    }

    Draft speak(EpisodeSpec spec, EpisodeNarration narration, Path directory, Profile profile, String operatorDirection) throws Exception {
        narration.validate(spec);
        EpisodeSpec.require("live".equals(mode),
            "Speech is disabled because SPEECH_MODE resolves to mock. Set SPEECH_MODE=live and provide OPENAI_API_KEY.");
        profile.validate();
        Files.createDirectories(directory);
        List<EpisodeSpeech.PuzzleSpeech> tracks = new ArrayList<>();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            EpisodeNarration.PuzzleNarration beat = narration.puzzles().get(i);
            double question = synthesize(beat.questionLeadIn(), directory.resolve("speech-question-" + i + ".wav"), questionDirection() + operatorSuffix(operatorDirection), profile, "question");
            double timer = synthesize(beat.timerCue(), directory.resolve("speech-timer-" + i + ".wav"), timerDirection() + operatorSuffix(operatorDirection), profile, "timer");
            double reveal = synthesize(beat.revealExplanation(), directory.resolve("speech-reveal-" + i + ".wav"), revealDirection() + operatorSuffix(operatorDirection), profile, "reveal");
            requireSlot(question, StudioRenderer.VISUAL_SETUP_SECONDS, "question", i + 1);
            requireSlot(timer, StudioRenderer.VISUAL_TIMER_CUE_SECONDS, "timer cue", i + 1);
            requireSlot(reveal, StudioRenderer.VISUAL_REVEAL_SECONDS, "answer", i + 1);
            tracks.add(new EpisodeSpeech.PuzzleSpeech(i + 1, question, timer, reveal));
        }
        EpisodeSpeech speech = new EpisodeSpeech(profile.model(), profile.voice(), tracks);
        speech.validate(spec);
        return new Draft(speech);
    }

    Draft normalizeExisting(EpisodeSpec spec, EpisodeNarration narration, Path directory) throws Exception {
        return normalizeExisting(spec, narration, directory, new Profile(model, voice, STANDARD_SPEECH_SPEED));
    }

    Draft normalizeExisting(EpisodeSpec spec, EpisodeNarration narration, Path directory, Profile profile) throws Exception {
        narration.validate(spec);
        List<EpisodeSpeech.PuzzleSpeech> tracks = new ArrayList<>();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            double question = duration(directory.resolve("speech-question-" + i + ".wav"));
            double timer = duration(directory.resolve("speech-timer-" + i + ".wav"));
            double reveal = duration(directory.resolve("speech-reveal-" + i + ".wav"));
            tracks.add(new EpisodeSpeech.PuzzleSpeech(i + 1, question, timer, reveal));
        }
        EpisodeSpeech speech = new EpisodeSpeech(profile.model(), profile.voice(), tracks);
        speech.validate(spec);
        return new Draft(speech);
    }

    Draft recoverExisting(EpisodeSpec spec, Path directory) throws Exception {
        List<EpisodeSpeech.PuzzleSpeech> tracks = new ArrayList<>();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            Path question = directory.resolve("speech-question-" + i + ".wav");
            Path timer = directory.resolve("speech-timer-" + i + ".wav");
            Path reveal = directory.resolve("speech-reveal-" + i + ".wav");
            if (!Files.isRegularFile(question) || !Files.isRegularFile(timer) || !Files.isRegularFile(reveal))
                throw new IllegalStateException("The interrupted speech clips are incomplete; generate the AI voice again");
            tracks.add(new EpisodeSpeech.PuzzleSpeech(i + 1, duration(question), duration(timer), duration(reveal)));
        }
        EpisodeSpeech speech = new EpisodeSpeech(model, voice, tracks);
        speech.validate(spec);
        return new Draft(speech);
    }

    private double synthesize(String input, Path target, String instructions, Profile profile, String slot) throws Exception {
        Path pending = target.resolveSibling(target.getFileName() + ".pending");
        try (HttpResponse response = client.audio().speech().create(SpeechCreateParams.builder()
                .model(profile.model()).voice(profile.voice()).input(input).instructions(instructions).speed(profile.speed())
                .responseFormat(SpeechCreateParams.ResponseFormat.WAV).build())) {
            Files.copy(response.body(), pending, StandardCopyOption.REPLACE_EXISTING);
        }
        normalizeLoudness(pending, target.getParent());
        double seconds = duration(pending);
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return seconds;
    }

    /** Levels clips consistently without changing their natural pace, pitch, or expression. */
    private void normalizeLoudness(Path wav, Path directory) throws Exception {
        if (!Files.isRegularFile(wav)) throw new IllegalStateException("Missing local speech clip " + wav.getFileName());
        Path normalized = wav.resolveSibling(wav.getFileName() + ".loud.wav");
        var process = new ProcessBuilder(ffmpeg, "-y", "-v", "warning", "-i", wav.toString(), "-filter:a",
            "loudnorm=I=-16:TP=-1.5:LRA=7", "-c:a", "pcm_s16le", normalized.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("speech-loudness-ffmpeg.log").toFile()).start();
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException("FFmpeg timed out leveling speech"); }
        } catch (InterruptedException ex) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw ex;
        }
        if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg could not level local speech");
        Files.move(normalized, wav, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static double duration(Path wav) throws Exception {
        byte[] bytes = Files.readAllBytes(wav);
        if (bytes.length < 44 || !chunk(bytes, 0, "RIFF") || !chunk(bytes, 8, "WAVE"))
            throw new IllegalStateException("Speech response was not a readable WAV file");
        int offset = 12, channels = 0, bits = 0; long sampleRate = 0, dataBytes = -1;
        while (offset + 8 <= bytes.length) {
            long declaredSize = Integer.toUnsignedLong(littleEndianInt(bytes, offset + 4));
            int body = offset + 8;
            long available = bytes.length - (long) body;
            if (available < 0) break;
            if (chunk(bytes, offset, "data")) { dataBytes = Math.min(declaredSize, available); break; }
            if (declaredSize > available || declaredSize > Integer.MAX_VALUE) break;
            int size = (int) declaredSize;
            if (chunk(bytes, offset, "fmt ") && size >= 16) {
                channels = littleEndianShort(bytes, body + 2);
                sampleRate = Integer.toUnsignedLong(littleEndianInt(bytes, body + 4));
                bits = littleEndianShort(bytes, body + 14);
            }
            offset = body + size + (size & 1);
        }
        long bytesPerSecond = sampleRate * channels * (bits / 8L);
        if (dataBytes <= 0 || bytesPerSecond <= 0) throw new IllegalStateException("Speech WAV has no measurable duration");
        return dataBytes / (double) bytesPerSecond;
    }

    private static boolean chunk(byte[] bytes, int offset, String value) {
        return offset + 4 <= bytes.length && bytes[offset] == value.charAt(0) && bytes[offset + 1] == value.charAt(1)
            && bytes[offset + 2] == value.charAt(2) && bytes[offset + 3] == value.charAt(3);
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
            | (Byte.toUnsignedInt(bytes[offset + 2]) << 16) | (Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static String questionDirection() {
        return "Bright, energetic adult male family-challenge host for children ages 6 to 18 and parents. Deliver this as one continuous show with the same confident, lively rhythm as every other question and reveal. Use a bright, high-leaning natural pitch, clear diction, a smiling voice, and one steady medium conversational pace. Sound excited and warmly inviting, never flat, sleepy, timid, babyish, classroom-like, or frantic. Leave natural space between thoughts; do not rush key words, cram sentences together, or stretch pauses. Be clearly audible without shouting. Speak exactly the supplied words and do not add a greeting.";
    }
    private static String timerDirection() {
        return "Energetic adult male timer cue for the same family-challenge host. Match the question narration's bright, high-leaning natural pitch, clear volume, and one steady medium pace. Launch crisply but never rush. Say it exactly once; do not count, add sound effects, insert a dramatic pause, or add extra words.";
    }
    private static String revealDirection() {
        return "Bright, expressive adult male family-challenge host for children ages 6 to 18 and parents. Continue the exact same bright, high-leaning natural pitch, clear volume, and one steady medium pace as the question. Make the answer a cheerful aha moment, warmly celebrate the discovery, then explain the proof with lively emphasis without speeding up. Use only normal, brief sentence pauses; never add a dramatic pause. No baby talk, flat delivery, classroom tone, timid voice, or exaggerated game-show shouting. Speak exactly the supplied words.";
    }

    private static void requireSlot(double seconds, double slotSeconds, String slot, int puzzleNumber) {
        EpisodeSpec.require(seconds <= slotSeconds + .02,
            "Puzzle " + puzzleNumber + " " + slot + " voice is " + String.format(Locale.ROOT, "%.2f", seconds)
                + " seconds, but its fixed slot is " + String.format(Locale.ROOT, "%.1f", slotSeconds)
                + " seconds. Regenerate narration with a shorter " + slot + " line, then generate voice again.");
    }
    private static String operatorSuffix(String direction) {
        return direction == null || direction.isBlank() ? "" : " Additional operator direction: " + direction.trim();
    }
}
