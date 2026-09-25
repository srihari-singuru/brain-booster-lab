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
        var sample=PilotFixtures.sample();
        episode.settingsJson=JsonMapper.builder().build().writeValueAsString(new EpisodeSettings("PUZZLE POP",3,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        when(ai.generate(eq("Test"),eq(1),eq("gpt-4o-mini"),anyString(),anyString()))
            .thenReturn(new StudioAi.Draft(new EpisodeSpec(sample.title(),List.of(sample.puzzles().get(0))),"test-model","test-response"))
            .thenReturn(new StudioAi.Draft(new EpisodeSpec(sample.title(),List.of(sample.puzzles().get(1))),"test-model","test-response"))
            .thenReturn(new StudioAi.Draft(new EpisodeSpec(sample.title(),List.of(sample.puzzles().get(2))),"test-model","test-response"));
        var result=service.generate(episode.id);
        assertThat(result.status()).isEqualTo("SCRIPT_REVIEW");
        assertThat(result.spec().puzzles()).hasSize(3);
        assertThat(result.scriptModel()).isEqualTo("test-model");
        verify(ai,times(3)).generate(eq("Test"),eq(1),eq("gpt-4o-mini"),anyString(),anyString());
        verify(ai, never()).review(any(),any(),any());
    }
    @Test void longEpisodeReviewRunsInBatchesAndResumesAfterATimeout() {
        var json=JsonMapper.builder().build();
        var puzzle=PilotFixtures.kids().puzzles().getFirst();
        var spec=new EpisodeSpec("Seven cases",java.util.Collections.nCopies(7,puzzle));
        episode.specJson=json.writeValueAsString(spec);
        episode.settingsJson=json.writeValueAsString(new EpisodeSettings("PUZZLE POP",7,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(ai.review(any(),any(),any())).thenAnswer(invocation -> {
            EpisodeSpec batch=invocation.getArgument(0);
            if (calls.incrementAndGet()==2) throw new RuntimeException(new java.net.SocketTimeoutException("deadline"));
            return new StudioAi.Review(java.util.stream.IntStream.range(0,batch.puzzles().size())
                .mapToObj(i -> new StudioAi.Finding(i+1,batch.puzzles().get(i).answerId(),true,"Fair")).toList());
        });

        var failed=service.review(episode.id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastError()).contains("request deadline","3 of 7 puzzle reviews are saved");

        var reviewed=service.review(episode.id);
        assertThat(reviewed.status()).isEqualTo("SCRIPT_REVIEW");
        assertThat(reviewed.review().findings()).extracting(StudioAi.Finding::puzzleNumber).containsExactly(1,2,3,4,5,6,7);
        // 1 saved batch + 1 timed out + 2 remaining batches; the saved first batch is never paid for again.
        verify(ai,times(4)).review(any(),any(),any());
        verify(ai,times(1)).review(argThat(batch -> batch != null && batch.puzzles().size()==1),any(),any());
        assertThat(Files.exists(directory.resolve(episode.id.toString()).resolve("review-progress.json"))).isFalse();
    }
    @Test void reviewSelectionKeepsChosenPuzzlesAndRecordsAnOverrideOnlyForKeptFailures() {
        var json=JsonMapper.builder().build();
        var spec=PilotFixtures.kids();
        episode.specJson=json.writeValueAsString(spec);
        episode.settingsJson=json.writeValueAsString(new EpisodeSettings("PUZZLE POP",3,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        episode.reviewJson=json.writeValueAsString(new StudioAi.Review(List.of(
            new StudioAi.Finding(1,spec.puzzles().get(0).answerId(),true,"Fair"),
            new StudioAi.Finding(2,"NONE",false,"Ambiguous"),
            new StudioAi.Finding(3,spec.puzzles().get(2).answerId(),true,"Fair"))));
        episode.status="CHANGES_NEEDED";

        var passedOnly=service.selectReviewedPuzzles(episode.id,List.of(1,3));
        assertThat(passedOnly.id()).isNotEqualTo(episode.id);
        assertThat(passedOnly.status()).isEqualTo("SCRIPT_REVIEW");
        assertThat(passedOnly.spec().puzzles()).containsExactly(spec.puzzles().get(0),spec.puzzles().get(2));
        assertThat(passedOnly.settings().puzzleCount()).isEqualTo(2);
        assertThat(passedOnly.review().passes(passedOnly.spec())).isTrue();
        assertThat(passedOnly.puzzleReviewOverridden()).isFalse();

        var withFailure=service.selectReviewedPuzzles(episode.id,List.of(2,3));
        assertThat(withFailure.spec().puzzles()).containsExactly(spec.puzzles().get(1),spec.puzzles().get(2));
        assertThat(withFailure.review().passes(withFailure.spec())).isFalse();
        assertThat(withFailure.puzzleReviewOverridden()).isTrue();

        assertThatThrownBy(() -> service.selectReviewedPuzzles(episode.id,List.of())).hasMessageContaining("at least one puzzle");
        assertThat(episode.status).isEqualTo("CHANGES_NEEDED");
        verifyNoInteractions(ai);
    }
    @Test void reviewAfterRegenerationChecksOnlyPuzzlesWithoutAPassingResult() throws Exception {
        var json=JsonMapper.builder().build();
        var spec=PilotFixtures.kids();
        episode.specJson=json.writeValueAsString(spec);
        episode.settingsJson=json.writeValueAsString(new EpisodeSettings("PUZZLE POP",3,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(episode.specJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var carried=new java.util.ArrayList<StudioAi.Finding>(java.util.Arrays.asList(
            new StudioAi.Finding(1,spec.puzzles().get(0).answerId(),true,"Fair"),null,
            new StudioAi.Finding(3,spec.puzzles().get(2).answerId(),true,"Fair")));
        Path dir=directory.resolve(episode.id.toString()); Files.createDirectories(dir);
        Files.writeString(dir.resolve("review-progress.json"),json.writeValueAsString(java.util.Map.of("specHash",hash,"findings",carried)));
        when(ai.review(any(),any(),any())).thenAnswer(invocation -> {
            EpisodeSpec batch=invocation.getArgument(0);
            return new StudioAi.Review(List.of(new StudioAi.Finding(1,batch.puzzles().getFirst().answerId(),true,"Fair")));
        });

        var reviewed=service.review(episode.id);
        assertThat(reviewed.status()).isEqualTo("SCRIPT_REVIEW");
        verify(ai,times(1)).review(argThat(batch -> batch != null && batch.puzzles().equals(List.of(spec.puzzles().get(1)))),any(),
            argThat(direction -> direction != null && direction.contains("reviewing puzzles 2 of 3")));
        assertThat(Files.exists(dir.resolve("review-progress.json"))).isFalse();
    }
    @Test void longEpisodeGroundingRunsInBatchesAndResumesAfterATimeout() throws Exception {
        var json=JsonMapper.builder().build();
        var puzzle=PilotFixtures.kids().puzzles().getFirst();
        var spec=new EpisodeSpec("Seven cases",java.util.Collections.nCopies(7,puzzle));
        String leadIn="Oh no! The party snacks are gone. The guests arrive in five minutes. Mom is very sad. There are three suspects here. Look at the picture. Who took the snacks?";
        String reveal="It was Suspect B! Look at the floor. B has no shadow. The other two friends have shadows.";
        var beats=java.util.stream.IntStream.range(0,7).mapToObj(i -> new EpisodeNarration.PuzzleNarration(i+1,leadIn,"You have ten seconds. Go!",reveal)).toList();
        var narration=new EpisodeNarration("Welcome to Puzzle Pop, detectives!",beats,"Thanks for solving with us today!");
        narration.validate(spec);
        episode.specJson=json.writeValueAsString(spec);
        episode.narrationJson=json.writeValueAsString(narration);
        episode.settingsJson=json.writeValueAsString(new EpisodeSettings("PUZZLE POP",7,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        Path dir=directory.resolve(episode.id.toString()); Files.createDirectories(dir);
        for (int i=0;i<7;i++) Files.write(dir.resolve("question-"+i+".png"),new byte[]{(byte)i});
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(narrator.ground(any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            EpisodeSpec batch=invocation.getArgument(0); EpisodeNarration batchNarration=invocation.getArgument(1); List<Path> frames=invocation.getArgument(2);
            assertThat(frames).hasSize(batch.puzzles().size());
            if (calls.incrementAndGet()==2) throw new RuntimeException(new java.net.SocketTimeoutException("deadline"));
            return new NarrationAi.GroundedDraft(new NarrationGrounding(batchNarration, java.util.stream.IntStream.range(0,batch.puzzles().size())
                .mapToObj(i -> new NarrationGrounding.Finding(i+1,true,true,true,"Clue visible.")).toList()),"test-model","resp-"+calls.get());
        });

        var failed=service.groundNarration(episode.id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastError()).contains("request deadline","3 of 7 puzzles are already grounded");

        var grounded=service.groundNarration(episode.id);
        assertThat(grounded.status()).isEqualTo("ART_REVIEW");
        assertThat(grounded.narrationGrounding().findings()).extracting(NarrationGrounding.Finding::puzzleNumber).containsExactly(1,2,3,4,5,6,7);
        assertThat(grounded.narrationGrounding().narration().puzzles()).extracting(EpisodeNarration.PuzzleNarration::puzzleNumber).containsExactly(1,2,3,4,5,6,7);
        verify(narrator,times(4)).ground(any(),any(),any(),any(),any());
        assertThat(Files.exists(dir.resolve("grounding-progress.json"))).isFalse();
    }
    @Test void recordsTheExactFailedStageForManualRecovery() {
        when(ai.generate(eq("Test"),eq(1),eq("gpt-4o-mini"),anyString(),anyString())).thenThrow(new IllegalStateException("Provider unavailable"));
        var result=service.generate(episode.id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStage()).isEqualTo("GENERATING");
    }
    @Test void keepsEachValidPuzzleWhenALaterGenerationRequestFails() {
        var sample=PilotFixtures.sample();
        episode.settingsJson=JsonMapper.builder().build().writeValueAsString(new EpisodeSettings("PUZZLE POP",3,"gpt-4o-mini","gpt-image-1","gpt-4o-mini","gpt-4o-mini-tts","cedar",1));
        when(ai.generate(eq("Test"),eq(1),eq("gpt-4o-mini"),anyString(),anyString()))
            .thenReturn(new StudioAi.Draft(new EpisodeSpec(sample.title(),List.of(sample.puzzles().getFirst())),"test-model","first-response"))
            .thenThrow(new IllegalStateException("Provider unavailable"));
        var result=service.generate(episode.id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStage()).isEqualTo("GENERATING");
        assertThat(result.spec().puzzles()).hasSize(1);
        assertThat(result.lastError()).contains("Stage failed");
    }
    @Test void puzzleNoveltyHistoryIncludesBoundedVisualMechanismAndCandidateFingerprint() {
        var puzzle = PilotFixtures.sample().puzzles().getFirst();
        String history = StudioService.puzzleHistoryLine(puzzle);
        assertThat(history).contains("KIND:", "CANDIDATES:", "VISUAL CLUE/SCENE MECHANISM:", puzzle.choices().getFirst().label());
        var longScene = new EpisodeSpec.Puzzle(puzzle.kind(), puzzle.title(), puzzle.setup(), puzzle.question(), puzzle.facts(),
            puzzle.choices(), puzzle.answerId(), puzzle.explanation(), "x".repeat(2_000), puzzle.thinkSeconds());
        assertThat(StudioService.puzzleHistoryLine(longScene)).hasSizeLessThan(1_100).endsWith("…");
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
    @Test void recoveringInterruptedPuzzleBatchMarksTheActualStage() {
        episode.status="REGENERATING_PUZZLES";episode.specJson="saved source script";
        when(repository.findAll()).thenReturn(List.of(episode));service.recoverInterrupted();
        assertThat(episode.status).isEqualTo("INTERRUPTED");
        assertThat(episode.failedStage).isEqualTo("REGENERATING_PUZZLES");
        assertThat(episode.specJson).isEqualTo("saved source script");
    }
    @Test void artworkSelectionCanContinueWithOnlyPassingPuzzles() throws Exception {
        var spec = prepareArtworkSelection(2);
        var selected = service.selectArtworkPuzzles(episode.id, List.of(1, 3));
        assertThat(selected.spec().puzzles()).containsExactly(spec.puzzles().get(0), spec.puzzles().get(2));
        assertThat(selected.artworkSelectionFinalized()).isTrue();
        assertThat(selected.artworkReady()).isTrue();
        assertThat(selected.visualReviews()).hasSize(2).allMatch(StudioAi.VisualReview::acceptable);
    }
    @Test void artworkSelectionRejectsOnlyTheFailedPuzzleRatherThanHidingAllChoices() throws Exception {
        prepareArtworkSelection(2);
        assertThatThrownBy(() -> service.selectArtworkPuzzles(episode.id, List.of(2)))
            .hasMessageContaining("Puzzle 2 did not pass the visual check")
            .hasMessageContaining("select only puzzles that passed");
    }
    @Test void missingVisualReviewKeepsThePuzzlePositionAndDisablesSelection() throws Exception {
        var spec = prepareArtworkSelection(0);
        Files.delete(directory.resolve(episode.id.toString()).resolve("visual-review-1.json"));
        var view = service.get(episode.id);
        assertThat(view.artworkReady()).isTrue();
        assertThat(view.visualReviews()).hasSize(spec.puzzles().size());
        assertThat(view.visualReviews().get(0).acceptable()).isTrue();
        assertThat(view.visualReviews().get(1).acceptable()).isFalse();
        assertThat(view.visualReviews().get(1).notes()).contains("missing");
        assertThat(view.visualReviews().get(2).acceptable()).isTrue();
    }
    @Test void staleClueCircleAfterARepairImageIsReplacedInsteadOfFailingTheStage() throws Exception {
        var mapper = JsonMapper.builder().build(); var spec = PilotFixtures.kids();
        episode.specJson = mapper.writeValueAsString(spec);
        episode.reviewJson = mapper.writeValueAsString(new StudioAi.Review(java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new StudioAi.Finding(i + 1, spec.puzzles().get(i).answerId(), true, "Fair")).toList()));
        episode.settingsJson = mapper.writeValueAsString(new EpisodeSettings("PUZZLE POP", 3, "gpt-4o-mini", "gpt-image-1", "gpt-4o-mini", "gpt-4o-mini-tts", "cedar", 1));
        Path dir = directory.resolve(episode.id.toString()); Files.createDirectories(dir);
        for (int i = 0; i < 3; i++) {
            Files.write(dir.resolve("art-" + i + ".png"), new byte[]{(byte) (i + 1), 9, 9});
            Files.writeString(dir.resolve("artwork-conformance-" + i + ".json"), mapper.writeValueAsString(new StudioAi.ArtworkConformance(true, "", "ok")));
            // Circle copied from an earlier version whose image was later replaced by a repair image.
            Files.writeString(dir.resolve("overlay-" + i + ".json"), mapper.writeValueAsString(
                new SceneOverlay("0".repeat(64), spec.puzzles().get(i).answerId(), new SceneOverlay.Region(.2, .2, .2, .2))));
        }
        doAnswer(invocation -> {
            EpisodeSpec rendered = invocation.getArgument(0); Path target = invocation.getArgument(1);
            for (int i = 0; i < rendered.puzzles().size(); i++) {
                SceneOverlay.read(target, i, rendered.puzzles().get(i)); // the real renderer fails on a stale circle
                Files.write(target.resolve("question-" + i + ".png"), new byte[]{(byte) (i + 5), 4, 3});
            }
            return null;
        }).when(renderer).previews(any(EpisodeSpec.class), any(Path.class), anyString());
        when(ai.inspectVisualFrame(any(), any(), any())).thenReturn(new StudioAi.VisualInspection(
            new StudioAi.VisualReview(true, "Clear."), new StudioAi.ClueLocation(.5, .5, .1, .1, "clue"), true));

        var result = service.artwork(episode.id);
        assertThat(result.status()).isEqualTo("ART_REVIEW");
        for (int i = 0; i < 3; i++) {
            var overlay = SceneOverlay.read(dir, i, spec.puzzles().get(i));
            assertThat(overlay.artworkSha256()).isEqualTo(SceneOverlay.hash(dir.resolve("art-" + i + ".png")));
        }
        verify(ai, never()).repairArtwork(any(), any(), anyInt(), any(), any(), any());
    }
    @Test void artworkSelectionCarriesPaidArtChecksForward() throws Exception {
        prepareArtworkSelection(0);
        Path source = directory.resolve(episode.id.toString());
        for (int i = 0; i < 3; i++) {
            Files.writeString(source.resolve("artwork-conformance-" + i + ".json"), "{\"acceptable\":true,\"repairBrief\":\"\",\"notes\":\"ok " + i + "\"}");
            String hash = Files.readString(source.resolve("visual-review-" + i + ".sha256")).trim();
            Files.writeString(source.resolve("visual-inspection-" + i + "-" + hash + ".json"), "{\"inspection\":" + i + "}");
        }
        var selected = service.selectArtworkPuzzles(episode.id, List.of(1, 3));
        Path target = directory.resolve(selected.id().toString());
        assertThat(Files.readString(target.resolve("artwork-conformance-1.json"))).contains("ok 2");
        String newHash = Files.readString(target.resolve("visual-review-1.sha256")).trim();
        assertThat(Files.readString(target.resolve("visual-inspection-1-" + newHash + ".json"))).contains("\"inspection\":2");
    }
    private EpisodeSpec prepareArtworkSelection(int failedPuzzleNumber) throws Exception {
        var mapper = JsonMapper.builder().build(); var spec = PilotFixtures.sample();
        episode.specJson = mapper.writeValueAsString(spec);
        episode.reviewJson = mapper.writeValueAsString(new StudioAi.Review(spec.puzzles().stream()
            .map(puzzle -> new StudioAi.Finding(spec.puzzles().indexOf(puzzle) + 1, puzzle.answerId(), true, "Fair"))
            .toList()));
        episode.settingsJson = mapper.writeValueAsString(new EpisodeSettings("PUZZLE POP", spec.puzzles().size(),
            "gpt-4o-mini", "gpt-image-1", "gpt-4o-mini", "gpt-4o-mini-tts", "cedar", 1));
        Path source = directory.resolve(episode.id.toString()); Files.createDirectories(source);
        Files.writeString(source.resolve("layout-version.txt"), StudioRenderer.layoutVersion(spec));
        for (int i = 0; i < spec.puzzles().size(); i++) {
            byte[] image = new byte[]{(byte)(i + 1), 2, 3};
            Files.write(source.resolve("art-" + i + ".png"), image);
            Path question = source.resolve("question-" + i + ".png"); Files.write(question, image);
            var visual = new StudioAi.VisualReview(i + 1 != failedPuzzleNumber, "check");
            Files.writeString(source.resolve("visual-review-" + i + ".json"), mapper.writeValueAsString(visual));
            Files.writeString(source.resolve("visual-review-" + i + ".sha256"), java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(image)));
        }
        doAnswer(invocation -> {
            EpisodeSpec selected = invocation.getArgument(0); Path target = invocation.getArgument(1);
            Files.createDirectories(target);
            Files.writeString(target.resolve("layout-version.txt"), StudioRenderer.layoutVersion(selected));
            for (int i = 0; i < selected.puzzles().size(); i++) {
                Files.write(target.resolve("question-" + i + ".png"), new byte[]{(byte)(i + 5), 4, 3});
                Files.write(target.resolve("reveal-" + i + ".png"), new byte[]{(byte)(i + 8), 4, 3});
            }
            return null;
        }).when(renderer).previews(any(EpisodeSpec.class), any(Path.class), anyString());
        return spec;
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
        assertThat(revision.approvedAt()).isNull();assertThat(revision.visualReviews()).hasSize(3)
            .allMatch(check -> !check.acceptable() && check.notes().contains("missing"));
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
