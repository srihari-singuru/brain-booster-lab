package com.brainboosterlab.channel.studio;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.*;

class StudioRenderIntegrationTest {
    @TempDir Path directory;
    static java.util.stream.Stream<EpisodeSpec> formats() { return java.util.stream.Stream.of(PilotFixtures.sample(),PilotFixtures.kids()); }
    @ParameterizedTest @MethodSource("formats")
    void rendersSilentVideoFromRetainedAssetsWithoutModifyingThem(EpisodeSpec spec) throws Exception {
        try {
            var check = new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Assumptions.assumeTrue(check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0, "FFmpeg required for integration test");
        } catch (java.io.IOException e) { Assumptions.abort("FFmpeg not installed"); }
        for (int i=0;i<3;i++) ImageIO.write(new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB),"png",directory.resolve("art-"+i+".png").toFile());
        byte[] before=Files.readAllBytes(directory.resolve("art-0.png"));
        var renderer=new StudioRenderer("ffmpeg");
        renderer.previews(spec,directory);
        Path video=renderer.render(spec,directory,true);
        assertThat(Files.size(video)).isGreaterThan(10000);
        assertThat(Files.readAllBytes(directory.resolve("art-0.png"))).isEqualTo(before);
        assertThat(Files.exists(directory.resolve("final.mp4"))).isFalse();
        var probe=new ProcessBuilder("ffprobe","-v","error","-show_entries","stream=codec_type,width,height:format=duration","-of","json",video.toString()).start();
        String metadata=new String(probe.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(probe.waitFor()).isZero();
        double expectedSeconds=spec.puzzles().stream().mapToDouble(p->"visual".equals(p.kind())
            ? StudioRenderer.VISUAL_SETUP_SECONDS + StudioRenderer.VISUAL_TIMER_CUE_SECONDS + StudioRenderer.VISUAL_QUESTION_SECONDS + StudioRenderer.VISUAL_REVEAL_SECONDS
            : p.thinkSeconds()+20).sum();
        expectedSeconds+=(spec.puzzles().size()-1)*(double)PuzzleMotion.TRANSITION_TICKS/PuzzleMotion.FPS;
        double actualSeconds=Double.parseDouble(metadata.replaceAll("(?s).*\"duration\"\\s*:\\s*\"([0-9.]+)\".*", "$1"));
        assertThat(actualSeconds).isCloseTo(expectedSeconds, within(.08));
        assertThat(metadata).contains("1920","1080").doesNotContain("audio");
        if("visual".equals(spec.puzzles().getFirst().kind())) {
            String manifest=Files.readString(directory.resolve("preview-puzzle-1-frames/frames.txt"));
            assertThat(manifest).contains("option framerate 30","duration 0.033333333");
        }
        assertThat(Files.exists(directory.resolve("preview-puzzle-1.mp4"))).isTrue();
        assertThat(Files.readString(directory.resolve("preview-render-version.txt")).trim()).isEqualTo(StudioRenderer.RENDER_VERSION);
        assertThat(Files.readString(directory.resolve("narration.txt"))).contains("QUESTION LEAD-IN — VOICE-TIMED");
    }

    @org.junit.jupiter.api.Test
    void rendersEachPuzzleToItsOwnMeasuredSpeechDurationBeforeMerging() throws Exception {
        assumeFfmpeg();
        EpisodeSpec spec = PilotFixtures.kids();
        for (int i = 0; i < spec.puzzles().size(); i++)
            ImageIO.write(new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("art-" + i + ".png").toFile());
        var renderer = new StudioRenderer("ffmpeg");
        renderer.previews(spec, directory);
        double[] question = {2.0, 2.5, 3.0}, cue = {.5, .75, 1.0}, reveal = {3.0, 3.5, 4.0};
        var tracks = new ArrayList<EpisodeSpeech.PuzzleSpeech>();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            writeSilentWav(directory.resolve("speech-question-" + i + ".wav"), question[i]);
            writeSilentWav(directory.resolve("speech-timer-" + i + ".wav"), cue[i]);
            writeSilentWav(directory.resolve("speech-reveal-" + i + ".wav"), reveal[i]);
            tracks.add(new EpisodeSpeech.PuzzleSpeech(i + 1, question[i], cue[i], reveal[i]));
        }
        EpisodeSpeech speech = new EpisodeSpeech("gpt-4o-mini-tts", "cedar", tracks);
        renderer.writeSpeechTrack(spec, speech, directory);
        Path merged = renderer.render(spec, directory, true, speech);
        for (int i = 0; i < tracks.size(); i++)
            assertThat(duration(directory.resolve("preview-puzzle-" + (i + 1) + ".mp4")))
                .isCloseTo(question[i] + cue[i] + StudioRenderer.VISUAL_QUESTION_SECONDS + reveal[i], within(.08));
        double expected = 0;
        for (int i = 0; i < tracks.size(); i++) expected += question[i] + cue[i] + StudioRenderer.VISUAL_QUESTION_SECONDS + reveal[i];
        expected += (tracks.size() - 1) * (double) PuzzleMotion.TRANSITION_TICKS / PuzzleMotion.FPS;
        assertThat(duration(merged)).isCloseTo(expected, within(.12));
    }

    private static void assumeFfmpeg() throws Exception {
        try {
            var check = new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Assumptions.assumeTrue(check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0, "FFmpeg required for integration test");
        } catch (java.io.IOException e) { Assumptions.abort("FFmpeg not installed"); }
    }

    private static double duration(Path video) throws Exception {
        var probe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nokey=1:noprint_wrappers=1", video.toString()).start();
        String output = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertThat(probe.waitFor()).isZero();
        return Double.parseDouble(output);
    }

    /** Minimal deterministic PCM WAV fixture: no Speech API and no external encoder needed. */
    private static void writeSilentWav(Path target, double seconds) throws Exception {
        int sampleRate = 24_000, samples = (int) Math.round(sampleRate * seconds), dataSize = samples * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + dataSize).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataSize);
        Files.write(target, wav.array());
    }
}
