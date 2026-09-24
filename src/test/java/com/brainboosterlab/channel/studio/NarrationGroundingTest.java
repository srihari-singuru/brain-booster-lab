package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationGroundingTest {
    @Test void allowsLongerStoryLeadInButKeepsTheTenSecondThinkWindowSeparate() {
        EpisodeSpec spec = PilotFixtures.sample();
        String story = "At the moonlit market, three clever makers are preparing surprises for a bright evening show, but one small clue may change which plan works. Which option fits this story best?";
        var narration = new EpisodeNarration("Welcome to Puzzle Pop, where every clue starts a new adventure.",
            java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i ->
                new EpisodeNarration.PuzzleNarration(i + 1, story, "Take ten seconds and choose your answer.",
                    "OPTION " + spec.puzzles().get(i).answerId() + " is right. The picture's clear clue proves it and rules out the other choices."))
                .toList(), "Thanks for solving along. Join us for another bright puzzle soon.");
        org.assertj.core.api.Assertions.assertThatCode(() -> narration.validate(spec)).doesNotThrowAnyException();

        String tooLong = story + " today once again";
        var over = new EpisodeNarration(narration.episodeOpening(),
            java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i ->
                new EpisodeNarration.PuzzleNarration(i + 1, tooLong, "Take ten seconds and choose your answer.",
                    "OPTION " + spec.puzzles().get(i).answerId() + " is right. The picture's clear clue proves it and rules out the other choices."))
                .toList(), narration.episodeClosing());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> over.validate(spec)).hasMessageContaining("24–32");
        assertThat(StudioRenderer.VISUAL_QUESTION_SECONDS).isEqualTo(10);
    }

    @Test void acceptsOnlyAVisuallySupportedOptionOnlyNarration() {
        EpisodeSpec spec = PilotFixtures.kids();
        EpisodeNarration narration = new EpisodeNarration(
            "Welcome to Puzzle Pop, where tiny clues lead to big happy discoveries.", List.of(
                new EpisodeNarration.PuzzleNarration(1, "On a sunny terrace, three cheerful choices are sharing cocoa while a magical mystery quietly waits nearby. Which option belongs to the friendly ghost today?", "Take ten seconds and choose your answer.", "OPTION B is right. This choice alone has no shadow on the floor, just as the magical cafe rule explains."),
                new EpisodeNarration.PuzzleNarration(2, "At a lively costume party, three shining robot outfits are ready for a playful surprise in the picture. Which option is made from cardboard today?", "Take ten seconds and choose your answer.", "OPTION C is right. Its open flap shows corrugated cardboard inside, while the other two outfits have smooth panels."),
                new EpisodeNarration.PuzzleNarration(3, "In a cat corner, three charming choices are waiting with one delightful mechanical surprise hiding in plain view. Which option is the wind-up toy today?", "Take ten seconds and choose your answer.", "OPTION A is right. A brass winding key is attached to this choice, making the toy clue clear.")),
            "Great puzzle work today. Keep your curious eyes ready for the next bright little mystery.");
        var grounding = new NarrationGrounding(narration, List.of(
            new NarrationGrounding.Finding(1, true, true, true, "Shadow is visible and narration uses OPTION B."),
            new NarrationGrounding.Finding(2, true, true, true, "Cardboard flap is visible and narration uses OPTION C."),
            new NarrationGrounding.Finding(3, true, true, true, "Winding key is visible and narration uses OPTION A.")));

        assertThat(grounding.passes(spec)).isTrue();
    }

    @Test void groundingKeepsReviewedScriptWhenEditorRewriteBreaksSpeechSlot() {
        EpisodeSpec spec = PilotFixtures.sample();
        EpisodeNarration reviewed = new EpisodeNarration(
            "Welcome to Puzzle Pop, where tiny clues lead to big happy discoveries.",
            java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i ->
                new EpisodeNarration.PuzzleNarration(i + 1,
                    "At the moonlit market, three clever makers are preparing surprises for a bright evening show, but one small clue may change which plan works. Which option fits this story best?",
                    "Take ten seconds and choose your answer.",
                    "OPTION " + spec.puzzles().get(i).answerId() + " is right. The picture's clear clue proves it and rules out the other choices."))
                .toList(), "Thanks for solving along. Join us for another bright puzzle soon.");
        EpisodeNarration invalidRewrite = new EpisodeNarration(reviewed.episodeOpening(),
            java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i ->
                new EpisodeNarration.PuzzleNarration(i + 1,
                    reviewed.puzzles().get(i).questionLeadIn(), reviewed.puzzles().get(i).timerCue(),
                    "OPTION " + spec.puzzles().get(i).answerId() + " is right."))
                .toList(), reviewed.episodeClosing());
        List<NarrationGrounding.Finding> findings = java.util.stream.IntStream.range(0, spec.puzzles().size())
            .mapToObj(i -> new NarrationGrounding.Finding(i + 1, true, true, true, "The clue is visible."))
            .toList();

        NarrationGrounding safe = new NarrationGrounding(invalidRewrite, findings)
            .preserveValidSpeechSlots(reviewed, spec);

        assertThat(safe.narration()).isEqualTo(reviewed);
        org.assertj.core.api.Assertions.assertThatCode(() -> safe.validate(spec)).doesNotThrowAnyException();
        assertThat(safe.findings()).allSatisfy(finding -> {
            assertThat(finding.visualClueConfirmed()).isTrue();
            assertThat(finding.narrationMatchesFrame()).isFalse();
            assertThat(finding.notes()).contains("speech slot");
        });
    }
}
