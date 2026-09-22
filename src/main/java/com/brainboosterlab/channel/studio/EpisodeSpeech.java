package com.brainboosterlab.channel.studio;

import java.util.List;

/** Measured, local metadata for one completed AI voice track. */
public record EpisodeSpeech(String model, String voice, List<PuzzleSpeech> puzzles) {
    public record PuzzleSpeech(int puzzleNumber, double questionSeconds, double timerCueSeconds,
                               double revealSeconds) {}

    public void validate(EpisodeSpec spec) {
        EpisodeSpec.require(model != null && !model.isBlank() && model.length() <= 120,
            "Speech model is missing or too long");
        EpisodeSpec.require(voice != null && !voice.isBlank() && voice.length() <= 120,
            "Speech voice is missing or too long");
        EpisodeSpec.require(puzzles != null && puzzles.size() == spec.puzzles().size(),
            "Speech must contain one track for every puzzle");
        for (int i = 0; i < puzzles.size(); i++) {
            PuzzleSpeech track = puzzles.get(i);
            EpisodeSpec.require(track != null && track.puzzleNumber() == i + 1,
                "Speech puzzle numbers must be consecutive and in order");
            duration(track.questionSeconds(), "Question speech", 2, 30);
            duration(track.timerCueSeconds(), "Timer cue speech", .25, 10);
            duration(track.revealSeconds(), "Reveal speech", 2, 30);
        }
    }

    private static void duration(double value, String label, double minimum, double maximum) {
        EpisodeSpec.require(Double.isFinite(value) && value >= minimum && value <= maximum,
            label + " duration is outside its safe range");
    }
}
