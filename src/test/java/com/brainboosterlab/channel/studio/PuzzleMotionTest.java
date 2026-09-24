package com.brainboosterlab.channel.studio;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PuzzleMotionTest {
    @org.junit.jupiter.api.Test void schedulesOnlySparseMidEpisodeCallsToAction() {
        assertThat(PuzzleMotion.ctaAfter(3, 0)).isNull();
        assertThat(PuzzleMotion.ctaAfter(3, 1)).isEqualTo(PuzzleMotion.Cta.COMMENT);
        assertThat(PuzzleMotion.ctaAfter(5, 1)).isEqualTo(PuzzleMotion.Cta.COMMENT);
        assertThat(PuzzleMotion.ctaAfter(5, 3)).isEqualTo(PuzzleMotion.Cta.LIKE);
        assertThat(PuzzleMotion.ctaAfter(5, 0)).isNull();
    }

    @TempDir Path dir;
    @Test void wipeHasExactEndpointsAndProgressesLeftToRight() {
        var a=solid(Color.RED);var b=solid(Color.BLUE);
        assertThat(PuzzleMotion.transition(a,b,0)).isSameAs(a);
        assertThat(PuzzleMotion.transition(a,b,1)).isSameAs(b);
        var fadingOut=PuzzleMotion.transition(a,b,.25);
        var fadingIn=PuzzleMotion.transition(a,b,.75);
        assertThat(fadingOut.getRGB(100,500)).isNotEqualTo(Color.BLUE.getRGB());
        assertThat(fadingIn.getRGB(1800,500)).isNotEqualTo(Color.RED.getRGB());
        assertThat(a.getRGB(100,500)).isEqualTo(Color.RED.getRGB());
    }
    @Test void introIsFullSizeAndSpeechGapsAndPuzzleTransitionsUseDeliberateTiming() {
        var logo = solid(Color.MAGENTA);
        var banner = solid(Color.CYAN);
        var intro = PuzzleMotion.intro(.5, logo, banner);
        assertThat(intro.getWidth()).isEqualTo(1920);
        assertThat(intro.getHeight()).isEqualTo(1080);
        assertThat(PuzzleMotion.intro(0, logo, banner).getRGB(960, 500)).isEqualTo(Color.CYAN.getRGB());
        assertThat(PuzzleMotion.intro(1, logo, banner).getRGB(960, 300)).isEqualTo(Color.MAGENTA.getRGB());
        assertThat(PuzzleMotion.SPEECH_GAP_TICKS / (double) PuzzleMotion.FPS).isEqualTo(.5);
        assertThat(PuzzleMotion.TRANSITION_TICKS / (double) PuzzleMotion.FPS).isEqualTo(3.0);
    }
    @Test void clueAnnotationsRejectWrongArtworkOrAnswer() throws Exception {
        var json=JsonMapper.builder().build();var art=dir.resolve("art-0.png");
        ImageIO.write(solid(Color.BLACK),"png",art.toFile());
        var overlay=new SceneOverlay(SceneOverlay.hash(art),"B",new SceneOverlay.Region(.4,.8,.2,.1));
        Files.writeString(dir.resolve("overlay-0.json"),json.writeValueAsString(overlay));
        assertThat(SceneOverlay.read(dir,0,KidsFrameRendererTest.puzzle())).isEqualTo(overlay);
        Files.writeString(dir.resolve("overlay-0.json"),json.writeValueAsString(new SceneOverlay(overlay.artworkSha256(),"C",overlay.clue())));
        assertThatThrownBy(()->SceneOverlay.read(dir,0,KidsFrameRendererTest.puzzle())).hasMessageContaining("does not match");
        Files.writeString(dir.resolve("overlay-0.json"),json.writeValueAsString(overlay));
        ImageIO.write(solid(Color.RED),"png",art.toFile());
        assertThatThrownBy(()->SceneOverlay.read(dir,0,KidsFrameRendererTest.puzzle())).hasMessageContaining("does not match");
    }
    @Test void rejectsInvalidClueCoordinates() {
        for(var r:java.util.List.of(new SceneOverlay.Region(-.1,.2,.2,.2),new SceneOverlay.Region(.8,.8,.3,.3),new SceneOverlay.Region(.2,.2,Double.NaN,.1)))
            assertThatThrownBy(r::validate).isInstanceOf(IllegalArgumentException.class);
    }
    private BufferedImage solid(Color color) {
        var image=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(color);g.fillRect(0,0,1920,1080);g.dispose();return image;
    }
}
