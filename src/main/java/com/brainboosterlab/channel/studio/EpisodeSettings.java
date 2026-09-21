package com.brainboosterlab.channel.studio;

/** Saved, per-episode production choices. Secrets always remain in the local environment. */
public record EpisodeSettings(String channelName, int puzzleCount, String textModel, String imageModel,
                              String narrationModel, String speechModel, String speechVoice, double speechSpeed) {
    public void validate() {
        text(channelName, 48, "Channel name");
        EpisodeSpec.require(puzzleCount >= 1 && puzzleCount <= 10, "Choose between 1 and 10 puzzles");
        text(textModel, 120, "Text model");
        text(imageModel, 120, "Image model");
        text(narrationModel, 120, "Narration model");
        text(speechModel, 120, "Speech model");
        text(speechVoice, 80, "Speech voice");
        EpisodeSpec.require(Double.isFinite(speechSpeed) && speechSpeed >= .75 && speechSpeed <= 1.25,
            "Voice speed must be between 0.75 and 1.25");
    }

    private static void text(String value, int max, String label) {
        EpisodeSpec.require(value != null && !value.isBlank() && value.length() <= max,
            label + " is missing or too long");
        EpisodeSpec.require(value.codePoints().allMatch(c -> c >= 32 && c != 127), label + " must be a single line");
    }
}
