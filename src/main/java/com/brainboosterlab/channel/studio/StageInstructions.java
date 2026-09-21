package com.brainboosterlab.channel.studio;

/** Operator-owned directions appended to a single AI stage's protected production prompt. */
record StageInstructions(String generate, String review, String artwork, String narration, String grounding, String speech) {
    static final StageInstructions EMPTY = new StageInstructions("", "", "", "", "", "");

    void validate() {
        for (String value : new String[]{generate, review, artwork, narration, grounding, speech})
            EpisodeSpec.require(value != null && value.length() <= 3000 && value.codePoints().allMatch(c -> c >= 32 && c != 127),
                "Stage direction must be a single line or paragraph of at most 3000 characters");
    }
    String forAction(String action) {
        return switch (action) {
            case "generate" -> generate; case "review" -> review; case "artwork" -> artwork;
            case "narration" -> narration; case "ground-narration" -> grounding; case "speech" -> speech;
            default -> "";
        };
    }
}
