package com.brainboosterlab.channel.studio;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioServiceTest {
    @TempDir Path directory;
    StudioRepository repository; StudioAi ai; NarrationAi narrator; SpeechAi speaker; StudioRenderer renderer; StudioService service; StudioEpisode episode;
    @BeforeEach void setup() {
        repository=mock(StudioRepository.class);ai=mock(StudioAi.class);narrator=mock(NarrationAi.class);speaker=mock(SpeechAi.class);renderer=mock(StudioRenderer.class);
        service=new StudioService(repository,ai,narrator,speaker,renderer,directory.toString());episode=new StudioEpisode("Test");
        when(repository.findById(episode.id)).thenReturn(Optional.of(episode));
        when(repository.saveAndFlush(any())).thenAnswer(a->a.getArgument(0));
    }
    @Test void generatedScriptWaitsForManualReview() {
        when(ai.generate("Test")).thenReturn(new StudioAi.Draft(PilotFixtures.sample(),"test-model","test-response"));
        var result=service.generate(episode.id);
        assertThat(result.status()).isEqualTo("SCRIPT_REVIEW");
        assertThat(result.spec()).isNotNull();
        assertThat(result.scriptModel()).isEqualTo("test-model");
        verify(ai, never()).review(any());
    }
    @Test void recordsTheExactFailedStageForManualRecovery() {
        when(ai.generate("Test")).thenThrow(new IllegalStateException("Provider unavailable"));
        var result=service.generate(episode.id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStage()).isEqualTo("GENERATING");
    }
    @Test void cannotSpendOnImagesWithoutPassingReview() {
        episode.specJson=JsonMapper.builder().build().writeValueAsString(PilotFixtures.sample());
        assertThatThrownBy(()->service.artwork(episode.id)).hasMessageContaining("reasoning review");
        verifyNoInteractions(ai);
    }
    @Test void cannotApproveBeforeArtwork() {
        episode.specJson=JsonMapper.builder().build().writeValueAsString(PilotFixtures.sample());
        assertThatThrownBy(()->service.approve(episode.id)).hasMessageContaining("reasoning review");
        assertThat(episode.approvedAt).isNull();
    }
    @Test void rejectsTraversalAndArbitraryLocalFiles() {
        assertThatThrownBy(()->service.media(episode.id,"../../.env")).hasMessageContaining("Unknown media");
        assertThatThrownBy(()->service.media(episode.id,"ffmpeg.log")).hasMessageContaining("Unknown media");
    }
    @Test void finalRenderRequiresHumanApprovalEvenWhenImagesExist() throws Exception {
        episode.specJson=JsonMapper.builder().build().writeValueAsString(PilotFixtures.sample());
        Path folder=directory.resolve(episode.id.toString());Files.createDirectories(folder);
        Files.writeString(folder.resolve("layout-version.txt"),StudioRenderer.LAYOUT_VERSION);
        for(int i=0;i<3;i++) {Files.write(folder.resolve("art-"+i+".png"),new byte[]{1});Files.write(folder.resolve("question-"+i+".png"),new byte[]{1});}
        assertThatThrownBy(()->service.render(episode.id,false)).hasMessageContaining("Human approval");
        verifyNoInteractions(renderer);
    }
    @Test void recoveringInterruptedWorkRetainsScript() {
        episode.status="PREPARING_ART";episode.specJson="saved script";
        when(repository.findAll()).thenReturn(List.of(episode));service.recoverInterrupted();
        assertThat(episode.status).isEqualTo("INTERRUPTED");assertThat(episode.specJson).isEqualTo("saved script");
    }
    @Test void revisionPreservesOriginalAndRequiresFreshChecks() {
        episode.specJson=JsonMapper.builder().build().writeValueAsString(PilotFixtures.sample());
        episode.approvedAt=java.time.Instant.now();String original=episode.specJson;
        var revision=service.revise(episode.id,PilotFixtures.kids());
        assertThat(revision.id()).isNotEqualTo(episode.id);
        assertThat(revision.approvedAt()).isNull();assertThat(revision.review()).isNull();
        assertThat(revision.artworkReady()).isFalse();assertThat(episode.specJson).isEqualTo(original);
        assertThat(episode.approvedAt).isNotNull();
    }
    @Test void revisionReusesOnlyUnchangedArt() throws Exception {
        var original=PilotFixtures.kids();episode.specJson=JsonMapper.builder().build().writeValueAsString(original);
        Path source=directory.resolve(episode.id.toString());Files.createDirectories(source);
        for(int i=0;i<3;i++) Files.write(source.resolve("art-"+i+".png"),new byte[]{1,2,3});
        var puzzles=new java.util.ArrayList<>(original.puzzles());
        puzzles.set(1,original.puzzles().get(2));
        var revised=service.revise(episode.id,new EpisodeSpec("Revision",puzzles));
        Path target=directory.resolve(revised.id().toString());
        assertThat(Files.exists(target.resolve("art-0.png"))).isTrue();
        assertThat(Files.exists(target.resolve("art-1.png"))).isFalse();
        assertThat(Files.exists(target.resolve("art-2.png"))).isTrue();
        assertThat(Files.readAllBytes(source.resolve("art-1.png"))).containsExactly(1,2,3);
    }
    @Test void restyleReusesContentWithoutPaidCallsButRequiresFreshVisualApproval() throws Exception {
        var json=JsonMapper.builder().build();var spec=PilotFixtures.kids();
        episode.specJson=json.writeValueAsString(spec);episode.scriptModel="original-model";episode.responseId="original-response";
        var review=new StudioAi.Review(java.util.stream.IntStream.range(0,3)
            .mapToObj(i->new StudioAi.Finding(i+1,spec.puzzles().get(i).answerId(),true,"Reviewed concept")).toList());
        episode.reviewJson=json.writeValueAsString(review);episode.approvedAt=java.time.Instant.now();
        Path source=directory.resolve(episode.id.toString());Files.createDirectories(source);
        for(int i=0;i<3;i++) javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(320,180,1),"png",source.resolve("art-"+i+".png").toFile());
        Files.writeString(source.resolve("preview.mp4"),"original-video");
        Files.writeString(source.resolve("visual-review-0.json"),"old-frame-review");
        var saved=new java.util.HashMap<java.util.UUID,StudioEpisode>();saved.put(episode.id,episode);
        when(repository.findById(any())).thenAnswer(a->Optional.ofNullable(saved.get(a.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(a->{StudioEpisode e=a.getArgument(0);saved.put(e.id,e);return e;});
        service=new StudioService(repository,ai,narrator,speaker,new StudioRenderer("ffmpeg"),directory.toString());
        var revision=service.restyle(episode.id);
        assertThat(revision.spec()).isEqualTo(spec);assertThat(revision.review()).isEqualTo(review);
        assertThat(revision.scriptModel()).isEqualTo("original-model");assertThat(revision.responseId()).isEqualTo("original-response");
        assertThat(revision.approvedAt()).isNull();assertThat(revision.visualReviews()).isEmpty();
        assertThat(revision.artworkReady()).isTrue();assertThat(revision.previewReady()).isFalse();
        assertThatThrownBy(()->service.approve(revision.id())).hasMessageContaining("visual review");
        assertThat(Files.readString(source.resolve("preview.mp4"))).isEqualTo("original-video");
        assertThat(episode.approvedAt).isNotNull();
        for(int i=0;i<3;i++) assertThat(Files.readAllBytes(directory.resolve(revision.id().toString()).resolve("art-"+i+".png")))
            .isEqualTo(Files.readAllBytes(source.resolve("art-"+i+".png")));
        verifyNoInteractions(ai);
    }
    @Test void restyleRefusesMissingArtworkWithoutSpending() {
        episode.specJson=JsonMapper.builder().build().writeValueAsString(PilotFixtures.kids());
        assertThatThrownBy(()->service.restyle(episode.id)).hasMessageContaining("all three saved artworks");
        verifyNoInteractions(ai,renderer);verify(repository,never()).saveAndFlush(any());
    }
}
