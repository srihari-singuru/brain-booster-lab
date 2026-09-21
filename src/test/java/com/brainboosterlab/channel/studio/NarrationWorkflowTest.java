package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class NarrationWorkflowTest {
    @TempDir Path directory;

    @Test void savesWriterDraftAndIndependentEditorReview() throws Exception {
        StudioRepository repository = mock(StudioRepository.class);
        StudioAi puzzles = mock(StudioAi.class);
        NarrationAi narrator = mock(NarrationAi.class);
        SpeechAi speaker = mock(SpeechAi.class);
        StudioRenderer renderer = mock(StudioRenderer.class);
        StudioEpisode episode = new StudioEpisode("Family mystery");
        EpisodeSpec spec = PilotFixtures.kids();
        var json = JsonMapper.builder().build();
        episode.specJson = json.writeValueAsString(spec);
        episode.reviewJson = json.writeValueAsString(new StudioAi.Review(java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new StudioAi.Finding(i + 1, spec.puzzles().get(i).answerId(), true, "Fair")).toList()));
        when(repository.findById(episode.id)).thenReturn(Optional.of(episode));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        EpisodeNarration draft = new EpisodeNarration(
            "Welcome to Brain Booster Lab, where tiny clues lead to big happy discoveries.", List.of(
                new EpisodeNarration.PuzzleNarration(1, "On a sunny terrace, three cheerful choices are sharing cocoa while a gentle magical mystery quietly waits nearby. Which option belongs to the friendly ghost today?", "Your ten seconds start now.", "The answer is OPTION B. This choice alone has no shadow on the floor, exactly as the magical cafe rule explains for our friendly mystery."),
                new EpisodeNarration.PuzzleNarration(2, "At a lively costume party, three shining robot outfits are ready for a playful surprise in the picture. Which option is made from cardboard today?", "Your ten seconds start now.", "The answer is OPTION C. The open flap on this choice shows corrugated cardboard inside, while the other two outfits have smooth panels."),
                new EpisodeNarration.PuzzleNarration(3, "In a cozy cat corner, three charming choices are waiting with one delightful mechanical surprise hiding in plain view. Which option is the wind-up toy today?", "Your ten seconds start now.", "The answer is OPTION A. A little brass winding key is attached to this choice, making the toy clue clear and cheerful.")),
            "Great puzzle work today. Keep your curious eyes ready for the next bright little mystery.");
        when(narrator.write(spec)).thenReturn(new NarrationAi.Draft(draft, "writer-model", "writer-response"));
        when(narrator.review(spec, draft)).thenReturn(new NarrationAi.Review(java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new NarrationAi.Finding(i + 1, true, true, true, true, "Clear and warm")).toList()));

        StudioService service = new StudioService(repository, puzzles, narrator, speaker, renderer, directory.toString());
        var view = service.narration(episode.id);

        assertThat(view.status()).isEqualTo("NARRATION_REVIEW");
        assertThat(view.narration()).isEqualTo(draft);
        assertThat(view.narrationReview().passes(spec)).isTrue();
        assertThat(view.narrationModel()).isEqualTo("writer-model");
        verify(renderer).writeNarration(spec, draft, directory.resolve(episode.id.toString()));
    }

    @Test void creatorCanContinuePastGroundingWarningsWhenOptionOnlyDeliveryIsIntact() throws Exception {
        StudioRepository repository = mock(StudioRepository.class);
        StudioAi puzzles = mock(StudioAi.class);
        NarrationAi narrator = mock(NarrationAi.class);
        SpeechAi speaker = mock(SpeechAi.class);
        StudioRenderer renderer = mock(StudioRenderer.class);
        StudioEpisode episode = new StudioEpisode("Family mystery");
        EpisodeSpec spec = PilotFixtures.kids();
        var json = JsonMapper.builder().build();
        EpisodeNarration narration = new EpisodeNarration("Welcome to our bright puzzle show today.",
            java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(index -> {
                String answer = spec.puzzles().get(index).answerId();
                return new EpisodeNarration.PuzzleNarration(index + 1,
                    "A cheerful little scene is waiting. One option has a clever surprise. Can you solve this friendly puzzle before the timer starts?",
                    "Take eight seconds and choose your answer now.",
                    "OPTION " + answer + " is right. The clear picture clue shows why it fits this playful mystery, while the other choices do not.");
            }).toList(), "Wonderful thinking. Come back for another puzzle soon.");
        var grounding = new NarrationGrounding(narration, java.util.stream.IntStream.range(0, spec.puzzles().size())
            .mapToObj(index -> new NarrationGrounding.Finding(index + 1, false, false, true, "Artwork clue needs your visual judgment."))
            .toList());
        episode.specJson = json.writeValueAsString(spec);
        episode.narrationJson = json.writeValueAsString(narration);
        episode.narrationGroundingJson = json.writeValueAsString(grounding);
        when(repository.findById(episode.id)).thenReturn(Optional.of(episode));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        StudioService service = new StudioService(repository, puzzles, narrator, speaker, renderer, directory.toString());
        var view = service.continueWithGroundingWarnings(episode.id);

        assertThat(view.narrationGroundingOverridden()).isTrue();
        assertThat(view.status()).isEqualTo("ART_REVIEW");
        verify(renderer).writeNarration(spec, narration, directory.resolve(episode.id.toString()));
    }
}
