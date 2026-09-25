package com.brainboosterlab.channel.studio;

import com.brainboosterlab.channel.OpenAiClientFactory;
import com.openai.client.OpenAIClient;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates a grounded, copy-ready upload pack; it never publishes or uploads anything. */
@Component
class YouTubeMetadataAi {
    static final int MAX_PROMPT_CHARS = 16_000;
    private final String mode;
    private final String fallbackModel;
    private final OpenAIClient client;

    YouTubeMetadataAi(@Value("${brain-booster.generation.mode:mock}") String mode,
                      @Value("${brain-booster.generation.model:}") String fallbackModel) {
        this.mode = mode;
        this.fallbackModel = fallbackModel;
        this.client = "live".equals(mode) ? OpenAiClientFactory.create(java.time.Duration.ofSeconds(120)) : null;
    }

    String model(String selected) {
        return selected == null || selected.isBlank() ? fallbackModel : selected.trim();
    }

    String defaultPrompt(EpisodeSpec spec, EpisodeNarration narration, String channel) {
        return buildPrompt(spec, narration, channel);
    }

    YouTubeUploadPack generate(EpisodeSpec spec, EpisodeNarration narration, String channel, String selectedModel, String exactPrompt) {
        String activeModel = model(selectedModel);
        if (!"live".equals(mode)) return fixture(spec, channel);
        String prompt = exactPrompt == null || exactPrompt.isBlank() ? buildPrompt(spec, narration, channel) : exactPrompt.trim();
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(2600).text(YouTubeUploadPack.class).build());
        YouTubeUploadPack pack = response.output().stream().flatMap(i -> i.message().stream())
            .flatMap(m -> m.content().stream()).flatMap(c -> c.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No upload pack was returned"));
        pack = pack.withSafeThumbnailDirections();
        pack.validate(spec);
        return pack;
    }

    private static String buildPrompt(EpisodeSpec spec, EpisodeNarration narration, String channel) {
        String puzzleFacts = java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i -> {
            EpisodeSpec.Puzzle p = spec.puzzles().get(i);
            return "PUZZLE " + (i + 1) + " | TITLE: " + promptField(p.title(), 48)
                + " | SETUP: " + promptField(p.setup(), 100)
                + " | QUESTION: " + promptField(p.question(), 90)
                + " | SCENE: " + promptField(p.sceneDescription(), 160)
                + " | WHY: " + promptField(p.explanation(), 100)
                + " | PRIVATE ANSWER: OPTION " + p.answerId();
        }).collect(java.util.stream.Collectors.joining("\n\n"));
        String prompt = """
            You are the YouTube packaging editor for %s, a family detective-mystery channel for children, teens and
            parents. Each video is a set of short whodunit cases: 3–4 suspects, ten seconds to pick the culprit, then the
            reveal. Create an accurate, high-click-through upload pack for the exact finished video described below.
            Lead with the most intriguing case: a specific mystery plus a direct challenge beats generic words like
            "puzzles" (e.g. "Who Ate the Birthday Cake? Solve 3 Detective Cases"). Good angles: "who did it", "who is
            lying", "find the fake", "can you solve it in 10 seconds". Use very simple, common words that young children
            and beginner English learners understand. Be curiosity-led, never deceptive: do not promise
            a result, prize, guest, danger, or visual that the episode does not contain. Use plain, natural English that a
            child and parent can both understand. Avoid keyword stuffing, all-caps titles, false urgency, fake statistics
            such as '99%% fail', answer spoilers, and claims like 'only geniuses can solve this'. The thumbnail and title must
            set the same honest expectation and remain readable on a phone.

            Return exactly: one recommended title; a short reason; one unique description (first two lines should explain
            the video before any call to action); exactly three relevant hashtags; up to 15 useful search tags (tags are
            secondary metadata; include spelling variants only when genuinely useful); a concise playlist suggestion;
            one category suggestion (exactly Entertainment or Education), language (English), and one friendly pinned
            comment that invites viewers to comment their suspect picks or which case fooled them, without exposing answers;
            and exactly three A/B concepts. Each A/B concept has a title, 2–5 words of punchy mystery thumbnail text (such as
            "WHO DID IT?" or "WHO IS LYING?"), a puzzle number to
            feature, and one visual-direction sentence of 10–20 words and no more than 160 characters, based on the
            supplied scene. Make concept 1's title exactly match the
            recommended title. The three concepts must differ meaningfully in truthful angle and featured puzzle where
            possible. Avoid excessive text, arrows, fake reactions, UI badges, logos you cannot supply, and answer clues.
            Preserve the artwork's scene and the puzzle's actual subject. The actual thumbnail will be composed locally
            from the selected episode artwork and your short copy; do not request generated image URLs.

            Description must be ready to paste as-is, include a warm one-line invitation and a simple family-friendly
            engagement question, and end with the supplied hashtags. Do not invent social links, schedules, or claims.
            Do not decide YouTube's 'made for kids' audience setting; the uploader must choose it truthfully in YouTube Studio.
            Keep the full description below 1800 characters. Return only the requested structured data.

            CHANNEL: %s
            EPISODE: %s
            PUZZLES (answers are private grounding only):
            %s
            """.formatted(channel, channel, spec.title(), puzzleFacts);
        prompt = sanitizePrompt(prompt);
        EpisodeSpec.require(prompt.length() <= MAX_PROMPT_CHARS,
            "This episode's upload prompt is too long. Shorten the episode title or puzzle text before preparing YouTube details.");
        return prompt;
    }

    static String sanitizePrompt(String value) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\r') return;
            if (codePoint == '\t') { clean.append(' '); return; }
            if (codePoint == '\n' || !Character.isISOControl(codePoint)
                && Character.getType(codePoint) != Character.FORMAT) clean.appendCodePoint(codePoint);
        });
        return clean.toString();
    }

    private static String promptField(String value, int maxCharacters) {
        String clean = sanitizePrompt(value);
        if (clean.length() <= maxCharacters) return clean;
        int end = clean.offsetByCodePoints(0, Math.min(maxCharacters - 1, clean.codePointCount(0, clean.length())));
        return clean.substring(0, end) + "…";
    }

    private static YouTubeUploadPack fixture(EpisodeSpec spec, String channel) {
        var variants = java.util.List.of(
            new YouTubeUploadPack.Variant(1, "Can Your Family Solve These Picture Puzzles?", "CAN YOU SPOT IT?", "Feature the scene's main characters with one clear focal point."),
            new YouTubeUploadPack.Variant(Math.min(2, spec.puzzles().size()), "Three Clever Puzzles to Solve Together", "LOOK CLOSER!", "Use a tighter crop on the puzzle scene; keep the key clue unobstructed."),
            new YouTubeUploadPack.Variant(spec.puzzles().size(), "A Fun Family Puzzle Challenge", "WHAT'S THE CLUE?", "Choose a bright, expressive scene and leave open space for the headline."));
        return new YouTubeUploadPack("Can Your Family Solve These Picture Puzzles?",
            "Clear, family-friendly promise that matches the visual puzzle format.",
            "Join " + channel + " for a cheerful family puzzle challenge. Look closely at each picture, choose your answer, and enjoy the reveal together.\n\nWhich puzzle was your favorite? Tell us below!\n\n#PuzzlePop #FamilyPuzzles #BrainTeasers",
            java.util.List.of("#PuzzlePop", "#FamilyPuzzles", "#BrainTeasers"),
            java.util.List.of("family puzzles", "picture puzzles", "brain teasers", "puzzles for families", "visual riddles"),
            "Family Puzzle Challenges", "Entertainment", "English", "Which puzzle did your family solve first? Share your favorite below!",
            variants);
    }
}
