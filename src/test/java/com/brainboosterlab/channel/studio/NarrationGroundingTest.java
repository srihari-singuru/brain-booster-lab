package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationGroundingTest {
    @Test void acceptsOnlyAVisuallySupportedOptionOnlyNarration() {
        EpisodeSpec spec = PilotFixtures.kids();
        EpisodeNarration narration = new EpisodeNarration(
            "Welcome to Brain Booster Lab, where tiny clues lead to big happy discoveries.", List.of(
                new EpisodeNarration.PuzzleNarration(1, "On a sunny terrace, three cheerful choices are sharing cocoa while a gentle magical mystery quietly waits nearby. Which option belongs to the friendly ghost today?", "Your ten seconds start now.", "The answer is OPTION B. This choice alone has no shadow on the floor, exactly as the magical cafe rule explains for our friendly mystery."),
                new EpisodeNarration.PuzzleNarration(2, "At a lively costume party, three shining robot outfits are ready for a playful surprise in the picture. Which option is made from cardboard today?", "Your ten seconds start now.", "The answer is OPTION C. The open flap on this choice shows corrugated cardboard inside, while the other two outfits have smooth panels."),
                new EpisodeNarration.PuzzleNarration(3, "In a cozy cat corner, three charming choices are waiting with one delightful mechanical surprise hiding in plain view. Which option is the wind-up toy today?", "Your ten seconds start now.", "The answer is OPTION A. A little brass winding key is attached to this choice, making the toy clue clear and cheerful.")),
            "Great puzzle work today. Keep your curious eyes ready for the next bright little mystery.");
        var grounding = new NarrationGrounding(narration, List.of(
            new NarrationGrounding.Finding(1, true, true, true, "Shadow is visible and narration uses OPTION B."),
            new NarrationGrounding.Finding(2, true, true, true, "Cardboard flap is visible and narration uses OPTION C."),
            new NarrationGrounding.Finding(3, true, true, true, "Winding key is visible and narration uses OPTION A.")));

        assertThat(grounding.passes(spec)).isTrue();
    }
}
