package com.brainboosterlab.channel.studio;
import java.awt.image.BufferedImage;
import java.nio.file.*;
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
        double expectedSeconds=spec.puzzles().stream().mapToInt(p->"visual".equals(p.kind())
            ? StudioRenderer.VISUAL_SETUP_SECONDS + StudioRenderer.VISUAL_QUESTION_SECONDS + StudioRenderer.VISUAL_REVEAL_SECONDS
            : p.thinkSeconds()+20).sum();
        for(int i=1;i<3;i++) if("visual".equals(spec.puzzles().get(i).kind())&&"visual".equals(spec.puzzles().get(i-1).kind()))
            expectedSeconds+=(double)PuzzleMotion.TRANSITION_TICKS/PuzzleMotion.FPS;
        assertThat(metadata).contains("1920","1080",String.format(java.util.Locale.ROOT,"%.6f",expectedSeconds)).doesNotContain("audio");
        if("visual".equals(spec.puzzles().getFirst().kind())) {
            String manifest=Files.readString(directory.resolve("draft-frames/frames.txt"));
            assertThat(manifest).contains("option framerate 30","duration 0.033333333");
        }
        assertThat(Files.readString(directory.resolve("narration.txt"))).contains(spec.puzzles().getFirst().setup());
    }
}
