package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationGroundingTest {
    @Test void acceptsOnlyAVisuallySupportedOptionOnlyNarration() {
        EpisodeSpec spec = PilotFixtures.kids();
        EpisodeNarration narration = new EpisodeNarration(
            "Welcome to Puzzle Pop, where tiny clues lead to big happy discoveries.", List.of(
                new EpisodeNarration.PuzzleNarration(1, "On a sunny terrace, three cheerful choices are sharing cocoa while a magical mystery quietly waits nearby. Which option belongs to the friendly ghost today?", "Take eight seconds and choose your answer.", "OPTION B is right. This choice alone has no shadow on the floor, just as the magical cafe rule explains."),
                new EpisodeNarration.PuzzleNarration(2, "At a lively costume party, three shining robot outfits are ready for a playful surprise in the picture. Which option is made from cardboard today?", "Take eight seconds and choose your answer.", "OPTION C is right. Its open flap shows corrugated cardboard inside, while the other two outfits have smooth panels."),
                new EpisodeNarration.PuzzleNarration(3, "In a cat corner, three charming choices are waiting with one delightful mechanical surprise hiding in plain view. Which option is the wind-up toy today?", "Take eight seconds and choose your answer.", "OPTION A is right. A brass winding key is attached to this choice, making the toy clue clear.")),
            "Great puzzle work today. Keep your curious eyes ready for the next bright little mystery.");
        var grounding = new NarrationGrounding(narration, List.of(
            new NarrationGrounding.Finding(1, true, true, true, "Shadow is visible and narration uses OPTION B."),
            new NarrationGrounding.Finding(2, true, true, true, "Cardboard flap is visible and narration uses OPTION C."),
            new NarrationGrounding.Finding(3, true, true, true, "Winding key is visible and narration uses OPTION A.")));

        assertThat(grounding.passes(spec)).isTrue();
    }
}
