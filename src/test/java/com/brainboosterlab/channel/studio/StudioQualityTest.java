package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudioQualityTest {
    @Test void fixturesHaveThreeDistinctReasoningPuzzles() { PilotFixtures.sample().validate(); }
    @Test void sdkAcceptsOurStructuredSchemas() {
        assertThatCode(() -> ResponseCreateParams.builder().model("test-model").input("test").text(EpisodeSpec.class).build()).doesNotThrowAnyException();
        assertThatCode(() -> ResponseCreateParams.builder().model("test-model").input("test").text(StudioAi.Review.class).build()).doesNotThrowAnyException();
    }
    @Test void preservesThreeByTwoAndWideAndPortraitProportions() {
        for (int[] size : List.of(new int[]{1536, 1024}, new int[]{1536, 864}, new int[]{1024, 1536})) {
            var box = new Rectangle(0, 0, 1920, 1080);
            var fit = ImageLayout.contain(size[0], size[1], box);
            assertThat(box.contains(fit)).isTrue();
            assertThat((double) fit.width / fit.height).isCloseTo((double) size[0] / size[1], within(.002));
        }
    }
    @Test void regressionLegacyArtworkIsNotWidenedFiftySevenPercent() {
        var fit = ImageLayout.contain(1536, 1024, new Rectangle(150, 230, 1620, 690));
        assertThat(fit.width).isEqualTo(1035);
        assertThat(fit.height).isEqualTo(690);
    }
    @Test void rejectsEmptyOrAmbiguousStructureAndObjectSearch() {
        assertThatThrownBy(() -> new EpisodeSpec("Title", List.of()).validate()).isInstanceOf(IllegalArgumentException.class);
        var original = PilotFixtures.sample(); var p = original.puzzles().getFirst();
        var puzzles = new ArrayList<>(original.puzzles());
        puzzles.set(0, new EpisodeSpec.Puzzle(p.kind(),p.title(),p.setup(),"Find the hidden key",p.facts(),p.choices(),p.answerId(),p.explanation(),p.sceneDescription(),15));
        assertThatThrownBy(() -> new EpisodeSpec(original.title(),puzzles).validate()).hasMessageContaining("reasoning question");
    }
    @Test void everyFixtureFrameFitsWithoutDroppingEvidence() {
        var renderer = new StudioRenderer("ffmpeg");
        var art = new BufferedImage(1536, 864, BufferedImage.TYPE_INT_RGB);
        for (var puzzle : PilotFixtures.sample().puzzles()) for (String phase : List.of("setup", "question", "reveal")) {
            var frame = renderer.frame(puzzle, art, 0, phase, 15, true);
            assertThat(frame.getWidth()).isEqualTo(1920);
            assertThat(frame.getHeight()).isEqualTo(1080);
        }
    }
    @Test void refusesToSilentlyTruncateUnrenderableEvidence() {
        var art = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);var g=art.createGraphics();
        try { assertThatThrownBy(() -> StudioRenderer.text(g,"Unrenderable evidence",0,0,10,10,40,Color.WHITE,Font.BOLD))
            .isInstanceOf(IllegalArgumentException.class); } finally {g.dispose();}
    }
    @Test void criticMustAgreeWithEveryAnswer() {
        var review = new StudioAi.Review(List.of(new StudioAi.Finding(1,"B",true,"proof"),new StudioAi.Finding(2,"A",true,"proof"),new StudioAi.Finding(3,"C",true,"proof")));
        assertThat(review.passes(PilotFixtures.sample())).isTrue();
        assertThat(new StudioAi.Review(List.of()).passes(PilotFixtures.sample())).isFalse();
        assertThat(new StudioAi.Review(List.of(new StudioAi.Finding(1,"A",true,"alternate"),new StudioAi.Finding(2,"A",true,"proof"),new StudioAi.Finding(3,"C",true,"proof"))).passes(PilotFixtures.sample())).isFalse();
    }
}
