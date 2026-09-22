package com.brainboosterlab.channel.studio;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KidsFrameRendererTest {
    static EpisodeSpec.Puzzle puzzle() {
        return new EpisodeSpec.Puzzle("visual","Magic Cafe","Three friends are having cocoa. One is a friendly ghost!",
            "Who is the friendly ghost?",List.of("In this magic cafe, only ghosts have no shadow."),
            List.of(new EpisodeSpec.Choice("A","Maya","Teal coat"),new EpisodeSpec.Choice("B","Leo","Amber coat"),new EpisodeSpec.Choice("C","Zara","Coral coat")),
            "B","Leo has no shadow! Did you spot it?","Three friends, A left, B center, C right. Only B lacks a shadow.",12);
    }
    @Test void acceptsThreeEasyVisualPuzzles() {
        PilotFixtures.kids().validate();
        var spec=new EpisodeSpec("Little mysteries",List.of(puzzle(),puzzle(),puzzle()));spec.validate();
        assertThat(StudioRenderer.layoutVersion(spec)).isEqualTo("kids-thumbnail-10");
        assertThat(StudioRenderer.layoutVersion(PilotFixtures.sample())).isEqualTo("reasoning-2");
    }
    @Test void fullBleedArtworkKeepsSideEdgesAndFloorEvidenceVisible() {
        var source=new BufferedImage(1536,864,BufferedImage.TYPE_INT_RGB);
        var g=source.createGraphics();g.setColor(Color.MAGENTA);g.fillRect(0,0,1536,864);g.dispose();
        var frame=KidsFrameRenderer.frame(puzzle(),source,0,"question",12,true);
        assertThat(KidsFrameRenderer.artBox(1536,864)).isEqualTo(new Rectangle(160,145,1600,900));
        var fit=ImageLayout.contain(1536,864,KidsFrameRenderer.artBox(1536,864));
        assertThat(fit).isEqualTo(new Rectangle(160,145,1600,900));
        assertThat(frame.getRGB(fit.x+200,fit.y+200)).isEqualTo(Color.MAGENTA.getRGB());
        assertThat(frame.getRGB(0,700)).isNotEqualTo(Color.MAGENTA.getRGB());
        assertThat(frame.getRGB(1919,700)).isNotEqualTo(Color.MAGENTA.getRGB());
        assertThat(frame.getRGB(960,840)).isEqualTo(Color.MAGENTA.getRGB());
        for(int i=0;i<3;i++) {
            var badge=KidsFrameRenderer.badgeBox(i,3,fit);
            assertThat(fit.contains(badge)).isTrue();
            assertThat(frame.getRGB(badge.x+50,badge.y+50)).isNotEqualTo(Color.MAGENTA.getRGB());
        }
    }
    @Test void shortRevealFitsAndKeepsOriginalPixelsUnmodified() {
        var art=new BufferedImage(1536,864,BufferedImage.TYPE_INT_RGB);
        assertThatCode(()->KidsFrameRenderer.frame(puzzle(),art,0,"reveal",0,true)).doesNotThrowAnyException();
        assertThat(art.getRGB(0,0)).isEqualTo(Color.BLACK.getRGB());
    }
    @Test void rejectsNonWidescreenArtworkRatherThanDistortingOrCroppingClues() {
        assertThatThrownBy(()->KidsFrameRenderer.artBox(1024,1536)).hasMessageContaining("16:9");
    }
    @Test void revealHighlightsOnlyTheCorrectBadgeAndKeepsFloorUncovered() {
        var art=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
        var g=art.createGraphics();g.setColor(Color.MAGENTA);g.fillRect(0,0,1920,1080);g.dispose();
        var question=KidsFrameRenderer.frame(puzzle(),art,0,"question",0,false);
        var reveal=KidsFrameRenderer.frame(puzzle(),art,0,"reveal",0,false);
        for(int i=0;i<3;i++) {
            var b=KidsFrameRenderer.badgeBox(i,3,KidsFrameRenderer.artBox(1920,1080));
            if(i==1) assertThat(reveal.getRGB(b.x+50,b.y+58)).isNotEqualTo(question.getRGB(b.x+50,b.y+58));
            else assertThat(reveal.getRGB(b.x+50,b.y+58)).isEqualTo(question.getRGB(b.x+50,b.y+58));
        }
        assertThat(reveal.getRGB(1200,800)).isEqualTo(Color.MAGENTA.getRGB());
    }
    @Test void oversizedOverlayFailsInsteadOfTruncatingOrSqueezingLetters() {
        var g=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            assertThatThrownBy(()->KidsFrameRenderer.headline(g,"Too much text ".repeat(30),new Rectangle(0,0,100,30),50,30,Color.WHITE,6))
                .hasMessageContaining("without distortion");
        } finally { g.dispose(); }
    }
    @Test void refusesWordyQuestions() {
        var p=puzzle();
        var longQuestion=new EpisodeSpec.Puzzle(p.kind(),p.title(),p.setup(),"Which of the three people in this cafe has no shadow?",p.facts(),p.choices(),p.answerId(),p.explanation(),p.sceneDescription(),p.thinkSeconds());
        assertThatThrownBy(()->new EpisodeSpec("Title",List.of(longQuestion,p,p)).validate()).hasMessageContaining("ten words");
    }
    @Test void clueCircleAppearsOnlyOnRevealAndAnimatesWithoutFillingTheClue() {
        var art=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
        var overlay=new SceneOverlay("test","B",new SceneOverlay.Region(.45,.84,.18,.12));
        var question=KidsFrameRenderer.frame(puzzle(),art,0,"question",10,false,overlay,1.2);
        var start=KidsFrameRenderer.frame(puzzle(),art,0,"reveal",0,false,overlay,0);
        var end=KidsFrameRenderer.frame(puzzle(),art,0,"reveal",0,false,overlay,1.2);
        var fit=ImageLayout.contain(1920,1080,KidsFrameRenderer.artBox(1920,1080));
        var clue=KidsFrameRenderer.keepClueOnStage(overlay.clue().bounds(fit),KidsFrameRenderer.artBox(1920,1080));
        int x=(int)(clue.getCenterX()+clue.getWidth()*.61),y=(int)clue.getCenterY();
        assertThat(end.getRGB(x,y)).isNotEqualTo(start.getRGB(x,y));
        assertThat(end.getRGB(x,1050)).isNotEqualTo(Color.BLACK.getRGB());
    }
}
