package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
    // One rate across every phase prevents perceptible pacing shifts; pitch remains instruction-guided.
    private static final double STANDARD_SPEECH_SPEED = 1.00;
    private static final double TARGET_WORDS_PER_MINUTE = 155.0;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
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
        this.client = "live".equals(mode) ? OpenAIOkHttpClient.builder().fromEnv().maxRetries(0).build() : null;
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
            double question = synthesize(beat.questionLeadIn(), directory.resolve("speech-question-" + i + ".wav"), questionDirection() + operatorSuffix(operatorDirection), profile);
            double timer = synthesize(beat.timerCue(), directory.resolve("speech-timer-" + i + ".wav"), timerDirection() + operatorSuffix(operatorDirection), profile);
            double reveal = synthesize(beat.revealExplanation(), directory.resolve("speech-reveal-" + i + ".wav"), revealDirection() + operatorSuffix(operatorDirection), profile);
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
            var beat = narration.puzzles().get(i);
            double question = normalizePace(directory.resolve("speech-question-" + i + ".wav"), beat.questionLeadIn(), directory, profile.speed());
            double timer = normalizePace(directory.resolve("speech-timer-" + i + ".wav"), beat.timerCue(), directory, profile.speed());
            double reveal = normalizePace(directory.resolve("speech-reveal-" + i + ".wav"), beat.revealExplanation(), directory, profile.speed());
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

    private double synthesize(String input, Path target, String instructions, Profile profile) throws Exception {
        Path pending = target.resolveSibling(target.getFileName() + ".pending");
        try (HttpResponse response = client.audio().speech().create(SpeechCreateParams.builder()
                .model(profile.model()).voice(profile.voice()).input(input).instructions(instructions).speed(profile.speed())
                .responseFormat(SpeechCreateParams.ResponseFormat.WAV).build())) {
            Files.copy(response.body(), pending, StandardCopyOption.REPLACE_EXISTING);
        }
        double seconds = normalizePace(pending, input, target.getParent(), profile.speed());
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return seconds;
    }

    private double normalizePace(Path wav, String spokenText, Path directory, double requestedSpeed) throws Exception {
        if (!Files.isRegularFile(wav)) throw new IllegalStateException("Missing local speech clip " + wav.getFileName());
        long words = WORD.matcher(spokenText).results().count();
        if (words == 0) throw new IllegalStateException("Speech text has no words to pace");
        double original = duration(wav);
        double targetSeconds = words * 60.0 / (TARGET_WORDS_PER_MINUTE * requestedSpeed);
        double tempo = original / targetSeconds;
        if (Math.abs(tempo - 1.0) < .015) return original;
        EpisodeSpec.require(tempo >= .50 && tempo <= 2.0, "Generated speech pace is outside the safe correction range");
        Path paced = wav.resolveSibling(wav.getFileName() + ".paced.wav");
        var process = new ProcessBuilder(ffmpeg, "-y", "-v", "warning", "-i", wav.toString(), "-filter:a",
            "atempo=" + String.format(Locale.ROOT, "%.6f", tempo), "-c:a", "pcm_s16le", paced.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("speech-pace-ffmpeg.log").toFile()).start();
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException("FFmpeg timed out normalizing speech pace"); }
        } catch (InterruptedException ex) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw ex;
        }
        if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg could not normalize local speech pace");
        double normalized = duration(paced);
        Files.move(paced, wav, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return normalized;
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
        return "Bright, energetic adult male family-challenge host for children ages 6 to 18 and parents. Sound delighted, playful and naturally animated, with clear English, a natural bright medium pitch, and a steady, consistent medium pace throughout. Keep the same confident rhythm from the first word to the last. Lift exciting words and questions, smile in the delivery, and make every discovery feel fun. Never sound flat, sleepy, robotic, babyish, classroom-like, or like a frantic game-show host. Be confidently audible without shouting. Speak exactly the supplied words and do not add a greeting.";
    }
    private static String timerDirection() {
        return "Energetic adult male timer cue for a family visual challenge. Deliver the line with a bright, exciting launch, a natural bright medium pitch, and the same steady medium pace as the narration. Keep the rhythm consistent and the words crisp and clear. Say it exactly once; do not count, add sound effects, or add extra words.";
    }
    private static String revealDirection() {
        return "Bright, expressive adult male family-challenge host for children ages 6 to 18 and parents. Make the answer feel like a cheerful aha moment: warmly celebrate the discovery, then clearly explain the proof. Natural English, a natural bright medium pitch, lively emphasis, and the same steady medium pace as the question narration. Do not speed up at the reveal; no baby talk, flat delivery, classroom tone, or exaggerated game-show shouting. Speak exactly the supplied words.";
    }
    private static String operatorSuffix(String direction) {
        return direction == null || direction.isBlank() ? "" : " Additional operator direction: " + direction.trim();
    }
}
