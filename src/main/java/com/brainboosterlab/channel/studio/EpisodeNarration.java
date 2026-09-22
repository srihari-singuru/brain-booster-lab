package com.brainboosterlab.channel.studio;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Speech-first copy for a finished episode. The renderer does not synthesize this copy yet. */
public record EpisodeNarration(String episodeOpening, List<PuzzleNarration> puzzles, String episodeClosing) {
    public record PuzzleNarration(int puzzleNumber, String questionLeadIn, String timerCue, String revealExplanation) {}

    public void validate(EpisodeSpec spec) {
        line(episodeOpening, 4, 28, "Episode opening");
        EpisodeSpec.require(puzzles != null && puzzles.size() == spec.puzzles().size(),
            "Narration must contain one beat for every puzzle");
        for (int i = 0; i < puzzles.size(); i++) {
            PuzzleNarration beat = puzzles.get(i);
            EpisodeSpec.require(beat != null && beat.puzzleNumber() == i + 1,
                "Narration puzzle numbers must be consecutive and in order");
            // The generator keeps future scripts compact enough for the fixed production speech slots.
            line(beat.questionLeadIn(), 20, 25, "Question lead-in");
            line(beat.timerCue(), 5, 7, "Timer cue");
            // New drafts target 13–16 words. Keep compatible saved scripts readable; the measured
            // local audio duration remains the final, strict eight-second production gate.
            line(beat.revealExplanation(), 15, 25, "Reveal explanation");
            forbidChoiceLabels(beat, spec.puzzles().get(i));
        }
        line(episodeClosing, 4, 28, "Episode closing");
    }

    private static void forbidChoiceLabels(PuzzleNarration beat, EpisodeSpec.Puzzle puzzle) {
        String spoken = (beat.questionLeadIn() + " " + beat.timerCue() + " " + beat.revealExplanation()).toLowerCase(Locale.ROOT);
        for (EpisodeSpec.Choice choice : puzzle.choices()) {
            String label = choice.label().trim().toLowerCase(Locale.ROOT);
            if (label.length() >= 3 && spoken.matches("(?s).*\\b" + Pattern.quote(label) + "\\b.*"))
                throw new IllegalArgumentException("Narration must refer to choices by their OPTION letter, never by name or label");
        }
    }

    private static void line(String text, int minimumWords, int maximumWords, String label) {
        EpisodeSpec.require(text != null && !text.isBlank() && text.length() <= 240,
            label + " is missing or too long");
        EpisodeSpec.require(text.codePoints().allMatch(c -> c >= 32 && c != 127),
            label + " must be a single spoken line");
        int words = text.trim().split("\\s+").length;
        EpisodeSpec.require(words >= minimumWords && words <= maximumWords,
            label + " must be " + minimumWords + "–" + maximumWords + " words for its speech slot");
    }
}
